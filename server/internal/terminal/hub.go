// Package terminal serves GET /v1/terminal: a login shell on the laptop, on its own PTY, streamed to a
// paired and granted phone (spec Q35, Q52-Q55; docs/protocol.md §14; docs/terminal.md). The shell runs
// as a child of the service, so it has the user's full account access but no sudo (NoNewPrivileges).
package terminal

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"log/slog"
	"net/http"
	"os"
	"slices"
	"sync"
	"time"

	"github.com/coder/websocket"

	"github.com/justcallmesu/aristotle/server/internal/devices"
)

// Defaults for Options (docs/protocol.md §14).
const (
	DefaultMaxPerDevice = 2
	DefaultMaxTotal     = 4
	DefaultIdleTimeout  = 30 * time.Minute // counted from input only (spec Q55)
	DefaultMaxSession   = 8 * time.Hour
	DefaultPingInterval = 25 * time.Second // as the relay (§12 Keepalive)
	DefaultPongTimeout  = 15 * time.Second
	DefaultKillGrace    = 5 * time.Second // SIGHUP to SIGKILL
	DefaultCheckEvery   = 5 * time.Second // idle and session-length checks

	// MaxFrameBytes caps one phone frame; the phone splits larger pastes.
	MaxFrameBytes = 64 << 10
	// InputBytesPerSecond and InputBurstBytes bound PTY input (a byte rate, not a frame rate: every
	// keystroke may be its own frame).
	InputBytesPerSecond = 64 << 10
	InputBurstBytes     = 64 << 10

	// outputChunk is the PTY read size and so the largest output frame.
	outputChunk = 32 << 10
	// closeGrace bounds the close handshake; revokeGrace is the same for revoke and disable.
	closeGrace  = 2 * time.Second
	revokeGrace = 500 * time.Millisecond
	// exitDrainWait bounds the wait for the last output after the shell exits (a background job may
	// keep the PTY open).
	exitDrainWait = time.Second
	// maxAncestry bounds the parent walk in InTerminal.
	maxAncestry = 128
)

// Banner is the first output of every terminal (spec Q54).
const Banner = "Aristotle terminal — sudo is unavailable (service sandbox); this shell has full access to your account.\r\n"

// Options configures a Hub. Zero values take the defaults.
type Options struct {
	Grants  *Grants
	Devices *devices.Store // revocations close the device's terminals
	Log     *slog.Logger

	MaxPerDevice int
	MaxTotal     int
	IdleTimeout  time.Duration
	MaxSession   time.Duration
	CheckEvery   time.Duration
	PingInterval time.Duration
	PongTimeout  time.Duration
	KillGrace    time.Duration
	// Now is the clock of the idle and session-length limits (tests move it); liveness uses real time.
	Now func() time.Time
	// Command returns the program and its argv; nil is the login shell with -l.
	Command func() (string, []string)
	// DelegatedUnit is the systemd unit whose cgroup is delegated to the server (the installed unit file
	// says Delegate=yes); "" relies on systemd's delegate xattr alone.
	DelegatedUnit string
	// NoCgroup skips cgroup detection: cleanup is by process session only (tests).
	NoCgroup bool

	beforeRegister func() // tests: runs between the HTTP admission check and register
}

func (o *Options) setDefaults() {
	setDefault(&o.MaxPerDevice, DefaultMaxPerDevice)
	setDefault(&o.MaxTotal, DefaultMaxTotal)
	setDefault(&o.IdleTimeout, DefaultIdleTimeout)
	setDefault(&o.MaxSession, DefaultMaxSession)
	setDefault(&o.CheckEvery, DefaultCheckEvery)
	setDefault(&o.PingInterval, DefaultPingInterval)
	setDefault(&o.PongTimeout, DefaultPongTimeout)
	setDefault(&o.KillGrace, DefaultKillGrace)
	if o.Now == nil {
		o.Now = time.Now
	}
	if o.Command == nil {
		o.Command = func() (string, []string) {
			passwd, _ := os.ReadFile("/etc/passwd")
			sh := loginShell(os.Getenv, passwd, os.Getuid())
			return sh, []string{sh, "-l"}
		}
	}
}

func setDefault[T int | time.Duration](v *T, def T) {
	if *v <= 0 {
		*v = def
	}
}

// Hub owns every live terminal: admission, caps, revoke/disable/ungrant, shutdown and the control
// socket's "is this process inside a terminal" check.
type Hub struct {
	opts Options
	cg   *cgroupParent // nil: no delegated cgroup, cleanup by process session

	mu     sync.Mutex
	byDev  map[string]map[*session]struct{}
	total  int
	closed bool
	wg     sync.WaitGroup // one per registered terminal
}

// New returns a Hub, subscribed to revocations and grant changes.
func New(opts Options) *Hub {
	opts.setDefaults()
	h := &Hub{opts: opts, byDev: map[string]map[*session]struct{}{}}
	why := "disabled for tests"
	if !opts.NoCgroup {
		h.cg, why = detectCgroup(opts.DelegatedUnit)
	}
	if h.cg != nil {
		if n := h.cg.sweep(); n > 0 {
			opts.Log.Warn("terminal cgroups left by an earlier run were killed", "count", n)
		}
		opts.Log.Info("terminal cleanup", "method", "cgroup")
	} else {
		opts.Log.Info("terminal cleanup", "method", "session", "why", why,
			"note", "processes that leave a terminal's session (setsid, daemons) can outlive it until the service stops")
	}
	opts.Devices.OnRevoke(h.revoked)
	opts.Grants.OnChange(h.recheck)
	return h
}

// Status reports the switch and whether deviceID is granted (GET /v1/me).
func (h *Hub) Status(deviceID string) (enabled, granted bool) {
	enabled, _ = h.opts.Grants.Status()
	return enabled, h.opts.Grants.Granted(deviceID)
}

// ServeTerminal upgrades an authenticated request and runs a shell until either side ends. The caller
// has checked the device and the grant once; the order here closes the race with revoke, disable and
// ungrant (as the relay does, relay.go ServeRelay): register, re-check, and only then start the shell.
func (h *Hub) ServeTerminal(w http.ResponseWriter, r *http.Request, deviceID, listener string) {
	s := newSession(h, deviceID, listener)
	pc, err := websocket.Accept(s.Hijacker(w), r, &websocket.AcceptOptions{CompressionMode: websocket.CompressionDisabled})
	if err != nil {
		return // Accept has answered with an HTTP error
	}
	pc.SetReadLimit(MaxFrameBytes)
	s.pc = pc
	if h.opts.beforeRegister != nil {
		h.opts.beforeRegister()
	}
	if why, ok := h.register(s); !ok {
		h.opts.Log.Info("terminal refused", "device_id", deviceID, "listener", listener, "close_code", int(why.code),
			"close_reason", why.reason)
		s.requestClose(why)
		s.finishPhone()
		return
	}
	defer h.unregister(s)
	// A revoke, disable or ungrant that landed before register found no terminal to close.
	if why, ok := h.admit(deviceID); !ok {
		s.requestClose(why)
	}
	s.run()
}

// admit is the live check: the device is still paired, the terminal enabled and the device granted.
func (h *Hub) admit(deviceID string) (closeReason, bool) {
	if !h.opts.Devices.Has(deviceID) {
		return closeRevoked, false
	}
	if err := h.opts.Grants.Allowed(deviceID); err != nil {
		return closeReason{CodeForbidden, err.Error()}, false
	}
	return closeReason{}, true
}

func (h *Hub) register(s *session) (closeReason, bool) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.closed {
		return closeGoingAway, false
	}
	set := h.byDev[s.dev]
	if len(set) >= h.opts.MaxPerDevice || h.total >= h.opts.MaxTotal {
		return closeTooMany, false
	}
	if set == nil {
		set = map[*session]struct{}{}
		h.byDev[s.dev] = set
	}
	set[s] = struct{}{}
	h.total++
	h.wg.Add(1)
	return closeReason{}, true
}

func (h *Hub) unregister(s *session) {
	h.mu.Lock()
	delete(h.byDev[s.dev], s)
	if len(h.byDev[s.dev]) == 0 {
		delete(h.byDev, s.dev)
	}
	h.total--
	h.mu.Unlock()
	h.wg.Done()
}

// revoked closes every terminal of a revoked device with 4401.
func (h *Hub) revoked(id string) {
	h.mu.Lock()
	defer h.mu.Unlock()
	for s := range h.byDev[id] {
		s.requestClose(closeRevoked)
	}
}

// recheck closes every terminal that is no longer admitted (4403 after disable or ungrant).
func (h *Hub) recheck() {
	h.mu.Lock()
	defer h.mu.Unlock()
	for dev, set := range h.byDev {
		why, ok := h.admit(dev)
		if ok {
			continue
		}
		for s := range set {
			s.requestClose(why)
		}
	}
}

func (h *Hub) all() []*session {
	h.mu.Lock()
	defer h.mu.Unlock()
	var out []*session
	for _, set := range h.byDev {
		for s := range set {
			out = append(out, s)
		}
	}
	return out
}

// Shutdown closes every terminal with 1001 and refuses new ones, then waits for them to end (their
// processes killed). When ctx expires first the remaining sockets are cut and ctx's error is returned.
func (h *Hub) Shutdown(ctx context.Context) error {
	h.mu.Lock()
	h.closed = true
	h.mu.Unlock()
	all := h.all()
	for _, s := range all {
		s.requestClose(closeGoingAway)
	}
	done := make(chan struct{})
	go func() { h.wg.Wait(); close(done) }()
	select {
	case <-done:
		return nil
	case <-ctx.Done():
		for _, s := range all {
			s.kill()
		}
		return ctx.Err()
	}
}

// Open describes one live terminal (aristotle-server terminal status).
type Open struct {
	ID       string    `json:"id"`
	DeviceID string    `json:"device_id"`
	Listener string    `json:"listener"`
	Since    time.Time `json:"since"`
	PID      int       `json:"pid"`
}

// List returns the live terminals, oldest first.
func (h *Hub) List() []Open {
	var out []Open
	for _, s := range h.all() {
		out = append(out, Open{ID: s.id, DeviceID: s.dev, Listener: s.listener, Since: s.start, PID: int(s.pid.Load())})
	}
	slices.SortFunc(out, func(a, b Open) int { return a.Since.Compare(b.Since) })
	return out
}

// InTerminal reports whether process pid runs inside an Aristotle terminal, and how that was seen, so
// the control socket can refuse it pair, terminal enable and grant-terminal (T19 red-team B2). It
// checks the ARISTOTLE_TERMINAL marker in the environment pid started with, the terminal cgroups, and
// pid's ancestry and session against every live shell. Anything it cannot read counts as inside (fail
// closed). A same-uid process can still escape every check (setsid plus an unset marker without a
// cgroup, systemd-run --user, editing rc files): this is a speed bump, not a boundary (docs/terminal.md).
func (h *Hub) InTerminal(pid int) (string, bool) {
	if pid <= 0 {
		return "unknown_peer", true
	}
	if _, marked, err := envMarker(pid); err != nil {
		return "unreadable_peer", true
	} else if marked {
		return "env", true
	}
	if h.cg != nil {
		if in, err := h.cg.holds(pid); err != nil {
			return "unreadable_peer", true
		} else if in {
			return "cgroup", true
		}
	}
	shells := map[int]string{}
	for _, s := range h.all() {
		if p := int(s.pid.Load()); p > 0 {
			shells[p] = s.id
		}
	}
	p := pid
	for range maxAncestry {
		if p <= 1 {
			break
		}
		info, err := readProcStat(p)
		if err != nil {
			if p == pid {
				return "unreadable_peer", true
			}
			break // an ancestor exited meanwhile
		}
		if id, ok := shells[p]; ok {
			return "ancestry:" + id, true
		}
		if id, ok := shells[info.sid]; ok {
			return "session:" + id, true
		}
		p = info.ppid
	}
	return "", false
}

func newTerminalID() string {
	b := make([]byte, 4)
	_, _ = rand.Read(b)
	return "term_" + hex.EncodeToString(b)
}
