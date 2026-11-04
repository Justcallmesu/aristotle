//go:build linux

package terminal

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/coder/websocket"

	"github.com/justcallmesu/aristotle/server/internal/devices"
	"github.com/justcallmesu/aristotle/server/internal/httpapi"
	"github.com/justcallmesu/aristotle/server/internal/relay"
)

type lockedLog struct {
	mu sync.Mutex
	b  strings.Builder
}

func (l *lockedLog) Write(p []byte) (int, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.b.Write(p)
}

func (l *lockedLog) String() string {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.b.String()
}

type fakeClock struct {
	mu sync.Mutex
	t  time.Time
}

func (c *fakeClock) Now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.t
}

func (c *fakeClock) Add(d time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.t = c.t.Add(d)
}

type harness struct {
	hub    *Hub
	grants *Grants
	store  *devices.Store
	srv    *httptest.Server
	logs   *lockedLog
	clock  *fakeClock
	dev    string
	token  string
	spawns atomic.Int32
}

// script runs a /bin/sh script as the terminal's program.
func script(s string) func() (string, []string) {
	return func() (string, []string) { return "/bin/sh", []string{"sh", "-c", s} }
}

// interactive runs /bin/sh on the PTY as the "login shell".
func interactive() (string, []string) { return "/bin/sh", []string{"sh"} }

func newHarness(t *testing.T, command func() (string, []string), tune func(*Options)) *harness {
	t.Helper()
	dir := t.TempDir()
	h := &harness{logs: &lockedLog{}, clock: &fakeClock{t: time.Date(2026, 11, 4, 9, 0, 0, 0, time.UTC)}}
	log := slog.New(slog.NewTextHandler(h.logs, nil))
	var err error
	if h.store, err = devices.Open(dir, nil, log); err != nil {
		t.Fatal(err)
	}
	if h.grants, err = OpenGrants(dir); err != nil {
		t.Fatal(err)
	}
	dev, token, err := h.store.Add("phone")
	if err != nil {
		t.Fatal(err)
	}
	h.dev, h.token = dev.ID, token
	changes(t)(h.grants.SetEnabled(true))
	changes(t)(h.grants.Grant(dev.ID))
	opts := Options{Grants: h.grants, Devices: h.store, Log: log, Now: h.clock.Now, NoCgroup: true, KillGrace: 300 * time.Millisecond,
		Command: func() (string, []string) { h.spawns.Add(1); return command() }}
	if tune != nil {
		tune(&opts)
	}
	h.hub = New(opts)
	api := httpapi.NewRemoteRouter("t", log, httpapi.Deps{Devices: h.store, Terminal: h.hub, Listener: "lan"})
	h.srv = httptest.NewUnstartedServer(api)
	h.srv.Listener = relay.WrapListener(h.srv.Listener)
	h.srv.Start()
	t.Cleanup(func() {
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if err := h.hub.Shutdown(ctx); err != nil {
			t.Errorf("hub shutdown: %v", err)
		}
		h.srv.Close()
	})
	return h
}

func (h *harness) addDevice(t *testing.T, name string) (string, string) {
	t.Helper()
	dev, token, err := h.store.Add(name)
	if err != nil {
		t.Fatal(err)
	}
	changes(t)(h.grants.Grant(dev.ID))
	return dev.ID, token
}

func (h *harness) dial(t *testing.T, token string) (*websocket.Conn, *http.Response, error) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	c, resp, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(h.srv.URL, "http")+"/v1/terminal",
		&websocket.DialOptions{HTTPHeader: http.Header{"Authorization": {"Bearer " + token}}})
	if err == nil {
		c.SetReadLimit(1 << 20)
		t.Cleanup(func() { c.CloseNow() })
	}
	return c, resp, err
}

// phone is a client that reads everything in the background, as the app must.
type phone struct {
	c     *websocket.Conn
	mu    sync.Mutex
	out   bytes.Buffer
	texts []string
	done  chan struct{}
	err   error
}

func (h *harness) open(t *testing.T, token string) *phone {
	t.Helper()
	c, _, err := h.dial(t, token)
	if err != nil {
		t.Fatal(err)
	}
	p := &phone{c: c, done: make(chan struct{})}
	go p.readAll()
	return p
}

func (p *phone) readAll() {
	defer close(p.done)
	for {
		typ, msg, err := p.c.Read(context.Background())
		p.mu.Lock()
		if err != nil {
			p.err = err
			p.mu.Unlock()
			return
		}
		if typ == websocket.MessageBinary {
			p.out.Write(msg)
		} else {
			p.texts = append(p.texts, string(msg))
		}
		p.mu.Unlock()
	}
}

func (p *phone) output() string {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.out.String()
}

func (p *phone) waitOutput(t *testing.T, re string) []string {
	t.Helper()
	rx := regexp.MustCompile(re)
	for deadline := time.Now().Add(5 * time.Second); time.Now().Before(deadline); time.Sleep(5 * time.Millisecond) {
		if m := rx.FindStringSubmatch(p.output()); m != nil {
			return m
		}
	}
	t.Fatalf("output never matched %q:\n%q", re, p.output())
	return nil
}

func (p *phone) send(t *testing.T, s string) {
	t.Helper()
	if err := p.c.Write(context.Background(), websocket.MessageBinary, []byte(s)); err != nil {
		t.Fatal(err)
	}
}

func (p *phone) control(t *testing.T, v any) {
	t.Helper()
	b, _ := json.Marshal(v)
	if err := p.c.Write(context.Background(), websocket.MessageText, b); err != nil {
		t.Fatal(err)
	}
}

// waitClose waits for the socket to end and returns the close code, reason and the text frames.
func (p *phone) waitClose(t *testing.T, within time.Duration) (websocket.StatusCode, string, []string) {
	t.Helper()
	select {
	case <-p.done:
	case <-time.After(within):
		t.Fatalf("socket still open after %v", within)
	}
	var ce websocket.CloseError
	code, reason := websocket.StatusCode(-1), ""
	if errors.As(p.err, &ce) {
		code, reason = ce.Code, ce.Reason
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	return code, reason, append([]string(nil), p.texts...)
}

func (p *phone) stillOpen(t *testing.T, d time.Duration) {
	t.Helper()
	select {
	case <-p.done:
		t.Fatalf("socket closed: %v (texts %v)", p.err, p.texts)
	case <-time.After(d):
	}
}

func (h *harness) only(t *testing.T) *session {
	t.Helper()
	var all []*session
	for deadline := time.Now().Add(5 * time.Second); time.Now().Before(deadline); time.Sleep(5 * time.Millisecond) {
		if all = h.hub.all(); len(all) == 1 && all[0].pid.Load() > 0 {
			return all[0]
		}
	}
	t.Fatalf("%d terminals, want 1 running", len(all))
	return nil
}

// gone waits until pid no longer exists (or is a zombie no one has reaped yet).
func gone(t *testing.T, pid int, within time.Duration) {
	t.Helper()
	for deadline := time.Now().Add(within); time.Now().Before(deadline); time.Sleep(10 * time.Millisecond) {
		if info, err := readProcStat(pid); err != nil || info.zombie {
			return
		}
	}
	t.Fatalf("pid %d still running after %v", pid, within)
}

func atoi(t *testing.T, s string) int {
	t.Helper()
	n, err := strconv.Atoi(s)
	if err != nil {
		t.Fatal(err)
	}
	return n
}

func readBody(r *http.Response) string {
	if r == nil || r.Body == nil {
		return ""
	}
	b, _ := io.ReadAll(r.Body)
	return string(b)
}

// survivors lists processes whose environment carries marker (started by this test's shells).
func survivors(marker string) []int {
	entries, _ := os.ReadDir("/proc")
	var out []int
	for _, e := range entries {
		pid, err := strconv.Atoi(e.Name())
		if err != nil {
			continue
		}
		b, err := os.ReadFile("/proc/" + e.Name() + "/environ")
		if err != nil || !bytes.Contains(b, []byte(marker)) {
			continue
		}
		if info, err := readProcStat(pid); err == nil && !info.zombie {
			out = append(out, pid)
		}
	}
	return out
}
