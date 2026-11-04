package terminal

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"os"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/coder/websocket"

	"github.com/justcallmesu/aristotle/server/internal/relay"
)

// session is one terminal: the phone's socket and the shell on its PTY.
//
// As in the relay, ioCtx is passed to every socket Read/Write and cancelling it (kill) cuts the socket;
// stopCtx only signals that a close was requested. The goroutine that runs run() starts the shell only
// while stopCtx is live and tears it down once stopCtx ends, so a close requested at any moment leaves
// no shell behind.
type session struct {
	*relay.PhoneConn // liveness clocks (real time) and the TCP connection

	hub      *Hub
	pc       *websocket.Conn
	id       string
	dev      string
	listener string
	log      *slog.Logger
	start    time.Time // opts.Now

	ioCtx    context.Context
	cancelIO context.CancelFunc
	stopCtx  context.Context
	stop     context.CancelFunc

	closeMu sync.Mutex
	why     closeReason // first requested close wins
	by      string      // who ended it: phone, shell, server
	set     bool

	proc       *shellProc    // set by run before the pumps start
	pid        atomic.Int64  // proc's pid for List and InTerminal (0 before the start)
	outputDone chan struct{} // closed when pumpOutput returns
	report     killReport    // written by teardown, read by logClose

	lastInput     atomic.Int64 // opts.Now unix nanos of the last input byte (idle limit)
	blocked       atomic.Bool  // the phone is not being read (input waits for the rate or the PTY)
	lastUnblocked atomic.Int64 // real unix nanos when the last such wait ended
	bytesIn       atomic.Int64
	bytesOut      atomic.Int64
	pings         sync.WaitGroup
	pingsOut      atomic.Int32
}

func newSession(h *Hub, dev, listener string) *session {
	s := &session{PhoneConn: relay.NewPhoneConn(nil), hub: h, id: newTerminalID(), dev: dev, listener: listener,
		log: h.opts.Log, start: h.opts.Now(), outputDone: make(chan struct{})}
	s.lastInput.Store(s.start.UnixNano())
	s.ioCtx, s.cancelIO = context.WithCancel(context.Background())
	s.stopCtx, s.stop = context.WithCancel(context.Background())
	return s
}

func (s *session) requestClose(why closeReason) { s.requestCloseBy("server", why) }

func (s *session) requestCloseBy(by string, why closeReason) {
	s.closeMu.Lock()
	if !s.set {
		s.why, s.by, s.set = why, by, true
	}
	s.closeMu.Unlock()
	s.stop()
}

func (s *session) closeState() (closeReason, string) {
	s.closeMu.Lock()
	defer s.closeMu.Unlock()
	return s.why, s.by
}

// run starts the shell (unless a close is already requested), pumps both ways until a close is
// requested, then ends the shell's processes and the socket, and waits for every goroutine it started.
func (s *session) run() {
	defer s.logClose()
	if s.stopCtx.Err() == nil {
		if err := s.startShell(); err != nil {
			s.log.Warn("terminal shell did not start", "device_id", s.dev, "terminal_id", s.id, "errno", errnoName(err))
			s.requestClose(closeNoShell)
		}
	}
	if s.proc == nil {
		close(s.outputDone)
		s.finishPhone()
		return
	}
	s.log.Info("terminal open", "device_id", s.dev, "terminal_id", s.id, "listener", s.listener, "pid", s.proc.pid,
		"cleanup", cleanupMethod(s.proc))
	var wg sync.WaitGroup
	wg.Go(s.pumpOutput)
	wg.Go(s.pumpInput)
	wg.Go(s.keepalive)
	wg.Go(s.watchLimits)
	wg.Go(s.watchExit)
	<-s.stopCtx.Done()
	s.teardown()
	wg.Wait()
	s.pings.Wait()
}

// startShell starts the shell in a cgroup of its own when the service has a delegated one. If clone3
// into the cgroup fails, the shell starts without it (session cleanup) rather than not at all.
func (s *session) startShell() error {
	path, args := s.hub.opts.Command()
	home := homeDir()
	spec := spawnSpec{path: path, args: args, dir: home, env: shellEnv(os.Environ(), s.id, path, home),
		cols: defaultCols, rows: defaultRows}
	if s.hub.cg != nil {
		cg, err := s.hub.cg.create(s.id)
		if err != nil {
			s.log.Warn("terminal cgroup not created; session cleanup", "terminal_id", s.id, "errno", errnoName(err))
		}
		spec.cgroup = cg
	}
	p, err := startShell(spec)
	if err != nil && spec.cgroup != nil {
		s.log.Warn("terminal start in its cgroup failed; session cleanup", "terminal_id", s.id, "errno", errnoName(err))
		spec.cgroup.destroy()
		spec.cgroup = nil
		p, err = startShell(spec)
	}
	if spec.cgroup != nil {
		spec.cgroup.closeFD()
	}
	if err != nil {
		return err
	}
	s.proc = p
	s.pid.Store(int64(p.pid))
	return nil
}

// errnoName is a failure's errno for the log: never the error text, which names the shell's path.
func errnoName(err error) string {
	var errno syscall.Errno
	if errors.As(err, &errno) {
		return errno.Error()
	}
	return "other"
}

func cleanupMethod(p *shellProc) string {
	if p.cg != nil {
		return "cgroup"
	}
	return "session"
}

// pumpOutput sends the banner, then PTY output as binary frames. One fixed buffer and a blocking write:
// while the phone does not keep up, the PTY is not read and the shell blocks on its own writes, so no
// output queues in the server (backpressure).
func (s *session) pumpOutput() {
	defer close(s.outputDone)
	if err := s.pc.Write(s.ioCtx, websocket.MessageBinary, []byte(Banner)); err != nil {
		s.requestCloseBy("phone", phoneClose(err))
		return
	}
	buf := make([]byte, outputChunk)
	for {
		n, err := s.proc.master.Read(buf)
		if n > 0 {
			if s.stopCtx.Err() != nil {
				return
			}
			if werr := s.pc.Write(s.ioCtx, websocket.MessageBinary, buf[:n]); werr != nil {
				s.requestCloseBy("phone", phoneClose(werr))
				return
			}
			s.bytesOut.Add(int64(n))
		}
		if err != nil {
			return // EIO once no process holds the PTY open, or the master closed at teardown
		}
	}
}

// pumpInput passes binary frames to the PTY and applies resize control frames. It returns only when
// the phone read fails: once a close is requested it keeps reading and discarding, so pongs and the
// phone's close frame are still processed.
func (s *session) pumpInput() {
	rate := newByteRate(InputBytesPerSecond, InputBurstBytes)
	for {
		typ, msg, err := s.pc.Read(s.ioCtx)
		if err != nil {
			s.requestCloseBy("phone", phoneClose(err))
			return
		}
		if s.stopCtx.Err() != nil {
			continue
		}
		if typ == websocket.MessageBinary {
			s.input(rate, msg)
		} else {
			s.control(msg)
		}
	}
}

// input writes one frame of keystrokes or paste to the PTY, after the byte-rate wait. The phone is not
// read meanwhile, which keepalive allows for.
func (s *session) input(rate *byteRate, msg []byte) {
	if len(msg) == 0 {
		return
	}
	s.blocked.Store(true)
	defer func() {
		s.lastUnblocked.Store(time.Now().UnixNano()) // before blocked drops, for keepalive
		s.blocked.Store(false)
	}()
	if d := rate.take(time.Now(), len(msg)); d > 0 && !s.sleep(d) {
		return
	}
	if _, err := s.proc.master.Write(msg); err != nil {
		return // the PTY is closing; watchExit or teardown ends the terminal
	}
	s.bytesIn.Add(int64(len(msg)))
	s.lastInput.Store(s.hub.opts.Now().UnixNano())
}

type controlMsg struct {
	Type string `json:"type"`
	Cols int    `json:"cols"`
	Rows int    `json:"rows"`
}

// control applies a text frame: {"type":"resize","cols":C,"rows":R} sets the PTY size (clamped). Other
// types are ignored; a frame that is not a JSON object closes 1008. Its text is never logged.
func (s *session) control(msg []byte) {
	var m controlMsg
	if json.Unmarshal(msg, &m) != nil {
		s.requestClose(closeBadControl)
		return
	}
	if m.Type != "resize" {
		return
	}
	cols, rows := clampSize(m.Cols, m.Rows)
	_ = setWinsize(s.proc.master, cols, rows)
}

// watchExit ends the terminal when the shell exits: after the last output (bounded), the phone gets
// {"type":"exit","code":N} and close 1000.
func (s *session) watchExit() {
	select {
	case <-s.stopCtx.Done():
		return
	case <-s.proc.exited:
	}
	t := time.NewTimer(exitDrainWait)
	defer t.Stop()
	select {
	case <-s.outputDone:
	case <-t.C:
	case <-s.stopCtx.Done():
		return
	}
	s.requestCloseBy("shell", closeExit)
}

// watchLimits closes the terminal after IdleTimeout without input (output does not count: a stolen
// phone running `top` must not keep its shell) or after MaxSession in all (spec Q55).
func (s *session) watchLimits() {
	t := time.NewTicker(s.hub.opts.CheckEvery)
	defer t.Stop()
	for {
		select {
		case <-s.stopCtx.Done():
			return
		case <-t.C:
		}
		if why, over := s.overLimit(s.hub.opts.Now()); over {
			s.requestClose(why)
			return
		}
	}
}

func (s *session) overLimit(now time.Time) (closeReason, bool) {
	switch {
	case now.Sub(s.start) >= s.hub.opts.MaxSession:
		return closeReason{CodeTimeLimit, reasonMaxLength}, true
	case now.Sub(time.Unix(0, s.lastInput.Load())) >= s.hub.opts.IdleTimeout:
		return closeReason{CodeTimeLimit, reasonIdle}, true
	}
	return closeReason{}, false
}

// keepalive is the relay's rule (docs/protocol.md §12 Keepalive): a ping every PingInterval, and close
// 1011 when within PongTimeout of it no byte came from the phone and no write that waited for the phone
// got bytes out, unless the phone was not being read in that window.
func (s *session) keepalive() {
	t := time.NewTicker(s.hub.opts.PingInterval)
	defer t.Stop()
	for {
		select {
		case <-s.stopCtx.Done():
			return
		case <-t.C:
		}
		sent := time.Now()
		s.ping()
		if !s.sleep(s.hub.opts.PongTimeout) {
			return
		}
		if s.DeadSince(sent) && !s.blocked.Load() && s.lastUnblocked.Load() < sent.UnixNano() {
			s.requestClose(closePongLost)
			return
		}
	}
}

// maxPings bounds the pings waiting for a pong.
const maxPings = 4

func (s *session) ping() {
	if s.pingsOut.Add(1) > maxPings {
		s.pingsOut.Add(-1)
		return
	}
	s.pings.Go(func() {
		defer s.pingsOut.Add(-1)
		_ = s.pc.Ping(s.ioCtx)
	})
}

// sleep waits d and reports false when a close was requested first.
func (s *session) sleep(d time.Duration) bool {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-s.stopCtx.Done():
		return false
	case <-t.C:
		return true
	}
}

// teardown ends the shell's processes and closes the phone at the same time. Revoke and disable kill
// at once; any other close gives the shell KillGrace after SIGHUP.
func (s *session) teardown() {
	why, _ := s.closeState()
	grace := s.hub.opts.KillGrace
	if why.code == CodeRevoked || why.code == CodeForbidden {
		grace = 0
	}
	var wg sync.WaitGroup
	wg.Go(func() { s.report = s.proc.terminate(grace) })
	wg.Go(s.finishPhone)
	wg.Wait()
}

// finishPhone tells the phone why (an exit or closing message), closes with the recorded code, and cuts
// the socket after closeGrace (revokeGrace for revoke and disable) if the phone does not answer.
func (s *session) finishPhone() {
	why, by := s.closeState()
	done := make(chan struct{})
	go func() {
		defer close(done)
		s.sendNotice(why, by)
		if !sendable(why.code) {
			_ = s.pc.CloseNow()
			return
		}
		_ = s.pc.Close(why.code, why.reason)
	}()
	grace := closeGrace
	if why.code == CodeRevoked || why.code == CodeForbidden {
		grace = revokeGrace
	}
	t := time.NewTimer(grace)
	defer t.Stop()
	select {
	case <-done:
	case <-t.C:
	}
	s.kill()
	<-done
}

type exitMsg struct {
	Type string `json:"type"`
	Code int    `json:"code"`
}

type closingMsg struct {
	Type   string `json:"type"`
	Reason string `json:"reason"`
}

// sendNotice writes the last text frame: exit with the shell's status, or closing with the reason of a
// close the server decided. Nothing when the phone ended it.
func (s *session) sendNotice(why closeReason, by string) {
	var msg any
	switch by {
	case "shell":
		msg = exitMsg{Type: "exit", Code: s.proc.code}
	case "server":
		msg = closingMsg{Type: "closing", Reason: why.reason}
	default:
		return
	}
	b, _ := json.Marshal(msg)
	_ = s.pc.Write(s.ioCtx, websocket.MessageText, b)
}

// kill cuts the socket: every Read and Write in flight fails at once.
func (s *session) kill() {
	s.cancelIO()
	s.Cut()
}

// logClose writes the audit lines: ids, pid, duration, byte counts and how it ended. Never terminal
// content, control-frame text, the shell's path or its working directory.
func (s *session) logClose() {
	why, by := s.closeState()
	exitCode := -1
	if s.proc != nil && isClosed(s.proc.exited) {
		exitCode = s.proc.code
		if by == "shell" {
			s.log.Info("terminal exit", "device_id", s.dev, "terminal_id", s.id, "pid", s.proc.pid, "exit_code", exitCode)
		}
	}
	if s.proc != nil {
		s.log.Info("terminal kill", "device_id", s.dev, "terminal_id", s.id, "pid", s.proc.pid,
			"method", s.report.method, "killed", s.report.killed, "ended_before_kill", s.report.settled)
		if s.report.method == "session" {
			s.log.Warn("terminal ended without a cgroup: background processes that left its session may survive",
				"device_id", s.dev, "terminal_id", s.id)
		}
	}
	s.log.Info("terminal closed",
		"device_id", s.dev,
		"terminal_id", s.id,
		"listener", s.listener,
		"pid", s.pid.Load(),
		"duration", s.hub.opts.Now().Sub(s.start).Round(time.Millisecond).String(),
		"bytes_in", s.bytesIn.Load(),
		"bytes_out", s.bytesOut.Load(),
		"close_code", int(why.code),
		"close_reason", why.reason,
		"closed_by", by,
		"exit_code", exitCode,
	)
}
