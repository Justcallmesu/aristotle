package app

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"github.com/justcallmesu/aristotle/server/internal/control"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/justcallmesu/aristotle/server/internal/hermes"
	"github.com/justcallmesu/aristotle/server/internal/listen"
)

const (
	isActiveCmd  = "systemctl --user is-active aristotle-server.service"
	isEnabledCmd = "systemctl --user is-enabled aristotle-server.service"
)

// runStatusWith runs `status args...` with a fake systemd and a canned control answer.
func runStatusWith(t *testing.T, active string, answer *statusResult, qerr error, args ...string) (int, string) {
	t.Helper()
	f, _, _ := useFakes(t)
	f.out = map[string]string{isActiveCmd: active + "\n", isEnabledCmd: "enabled\n"}
	old := queryStatus
	queryStatus = func(res *statusResult) error {
		if answer != nil {
			*res = *answer
		}
		return qerr
	}
	t.Cleanup(func() { queryStatus = old })
	var out, errOut bytes.Buffer
	code := Run(append([]string{"status"}, args...), "v1", &out, &errOut)
	return code, out.String() + errOut.String()
}

func fullAnswer(h *hermesStatus) *statusResult {
	return &statusResult{Version: "1.2.3", Port: 8443, Hermes: h, Listeners: []statusListener{
		{Kind: "lan", Iface: "wlan0", IP: "192.168.1.5", Subnet: "192.168.1.0/24"},
		{Kind: "tailscale", Iface: "tailscale0", IP: "100.74.61.96"},
		{Kind: "tailscale", Iface: "tailscale0", IP: "fd7a:115c:a1e0::d628:3d61"},
	}}
}

func TestStatusPorcelainRunning(t *testing.T) {
	code, out := runStatusWith(t, "active", fullAnswer(&hermesStatus{State: "reachable"}), nil, "--porcelain")
	want := "service active\nenabled enabled\nserver running\nversion 1.2.3\nport 8443\n" +
		"lan wlan0 192.168.1.5 192.168.1.0/24\ntailscale tailscale0 100.74.61.96\n" +
		"tailscale tailscale0 fd7a:115c:a1e0::d628:3d61\nhermes reachable\n"
	if code != 0 || out != want {
		t.Fatalf("code %d\n got %q\nwant %q", code, out, want)
	}
}

func TestStatusPorcelainNotRunning(t *testing.T) {
	cases := []struct {
		err  error
		word string
	}{{fmt.Errorf("x: %w", control.ErrNotRunning), "not-running"}, {errors.New("boom"), "no-answer"}}
	for _, c := range cases {
		code, out := runStatusWith(t, "inactive", nil, c.err, "--porcelain")
		want := "service inactive\nenabled enabled\nserver " + c.word + "\n"
		if code != 1 || out != want {
			t.Errorf("%v: code %d got %q want %q", c.err, code, out, want)
		}
	}
}

func TestStatusPorcelainOldServer(t *testing.T) {
	code, out := runStatusWith(t, "active", &statusResult{Version: "0.9"}, nil, "--porcelain")
	if code != 0 || out != "service active\nenabled enabled\nserver running\nversion 0.9\n" {
		t.Fatalf("code %d %q", code, out)
	}
	if strings.Contains(out, "port") || strings.Contains(out, "hermes") {
		t.Fatal(out)
	}
}

func TestStatusHermesErrorStillExitsZero(t *testing.T) {
	code, _ := runStatusWith(t, "active", fullAnswer(&hermesStatus{State: "error", Reason: "x"}), nil, "--porcelain")
	if code != 0 {
		t.Fatalf("code %d", code)
	}
}

func TestStatusBadArgs(t *testing.T) {
	for _, a := range [][]string{{"--json"}, {"--porcelain", "x"}, {"x"}} {
		if code, _ := runStatusWith(t, "active", nil, nil, a...); code != 2 {
			t.Errorf("%v: code %d", a, code)
		}
	}
}

func TestStatusPlainHermesLine(t *testing.T) {
	cases := []struct {
		h    *hermesStatus
		want string
	}{
		{&hermesStatus{State: "reachable"}, "Hermes:   reachable\n"},
		{&hermesStatus{State: "not-running", Reason: "dial: no socket"}, "Hermes:   not running (dial: no socket)\n"},
		{&hermesStatus{State: "error", Reason: "bad\x1b[31m"}, "Hermes:   error (bad?[31m)\n"},
		{nil, ""},
	}
	for _, c := range cases {
		_, out := runStatusWith(t, "active", fullAnswer(c.h), nil)
		if c.want == "" {
			if strings.Contains(out, "Hermes:") {
				t.Errorf("old server: %q", out)
			}
			continue
		}
		if !strings.HasSuffix(out, c.want) {
			t.Errorf("%+v: %q", c.h, out)
		}
	}
}

// stubHermes records the verbs used; block makes Identify wait for its context.
type stubHermes struct {
	mu    sync.Mutex
	calls []string
	err   error
	block bool
}

func (s *stubHermes) Identify(ctx context.Context) (hermes.Identity, error) {
	s.mu.Lock()
	s.calls = append(s.calls, "identify")
	s.mu.Unlock()
	if s.block {
		<-ctx.Done()
		return hermes.Identity{}, ctx.Err()
	}
	return hermes.Identity{}, s.err
}

// Calls returns a copy of the recorded verbs, read under the lock Identify writes with.
func (s *stubHermes) Calls() []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]string(nil), s.calls...)
}

// waitCalls waits (bounded) until the stub has recorded n calls: a probe that gave up on its timeout may return before
// the Identify goroutine has run.
func (s *stubHermes) waitCalls(n int) []string {
	deadline := time.Now().Add(2 * time.Second)
	for len(s.Calls()) < n && time.Now().Before(deadline) {
		time.Sleep(5 * time.Millisecond)
	}
	return s.Calls()
}

func TestProbeHermes(t *testing.T) {
	cases := []struct {
		name string
		stub *stubHermes
		want string
	}{
		{"reachable", &stubHermes{}, hermesReachable},
		{"not running", &stubHermes{err: fmt.Errorf("dial: %w", hermes.ErrHermesNotRunning)}, hermesNotRunning},
		{"other error", &stubHermes{err: errors.New("state \"starting\"")}, hermesError},
		{"hangs", &stubHermes{block: true}, hermesError},
	}
	for _, c := range cases {
		start := time.Now()
		got := probeHermes(c.stub, 50*time.Millisecond)
		if got.State != c.want {
			t.Errorf("%s: %+v", c.name, got)
		}
		if got.State != hermesReachable && got.Reason == "" {
			t.Errorf("%s: no reason", c.name)
		}
		if time.Since(start) > time.Second {
			t.Errorf("%s: took %v", c.name, time.Since(start))
		}
		if calls := c.stub.waitCalls(1); len(calls) != 1 || calls[0] != "identify" {
			t.Errorf("%s: calls %v", c.name, calls)
		}
	}
}

// identifier has one method, so the status check cannot mint a ticket or open a session.
var _ identifier = (*stubHermes)(nil)

func TestControlStatusCarriesPortListenersHermes(t *testing.T) {
	ifaces := []listen.Iface{
		{Name: "wlan0", Up: true, Physical: true, DefaultRoute: true, Addrs: prefixes("192.168.1.61/24")},
		{Name: "tailscale0", Up: true, Tun: true, Addrs: prefixes("100.74.61.96/32")},
	}
	stub := &stubHermes{err: hermes.ErrHermesNotRunning}
	ts := startServer(t, func() ([]listen.Iface, error) { return ifaces, nil }, "", func(s *server) { s.hermesProbe = stub })
	srv := ts.srv
	for deadline := time.Now().Add(5 * time.Second); len(srv.listen.Active()) < 2 && time.Now().Before(deadline); {
		time.Sleep(10 * time.Millisecond)
	}
	raw, err := srv.controlHandlers()[cmdStatus](nil)
	if err != nil {
		t.Fatal(err)
	}
	b, _ := json.Marshal(raw)
	var res statusResult
	if err := json.Unmarshal(b, &res); err != nil {
		t.Fatal(err)
	}
	if res.Hermes == nil || res.Hermes.State != hermesNotRunning {
		t.Fatalf("hermes %+v", res.Hermes)
	}
	if res.Port != srv.cfg.Port || len(res.Listeners) != 2 {
		t.Fatalf("%+v", res)
	}
	lan := res.Listeners[0]
	if lan.Kind != "lan" || lan.Iface != "wlan0" || lan.IP != "192.168.1.61" || lan.Subnet != "192.168.1.0/24" {
		t.Fatalf("lan %+v", lan)
	}
	if ts := res.Listeners[1]; ts.Kind != "tailscale" || ts.IP != "100.74.61.96" {
		t.Fatalf("ts %+v", ts)
	}
	if calls := stub.waitCalls(1); len(calls) != 1 {
		t.Fatalf("calls %v", calls)
	}
}

func TestControlStatusHangingHermesAnswersWithinTimeout(t *testing.T) {
	stub := &stubHermes{block: true}
	ts := startServer(t, func() ([]listen.Iface, error) { return nil, nil }, "", func(s *server) {
		s.hermesProbe, s.hermesTimeout = stub, 50*time.Millisecond
	})
	start := time.Now()
	res := ts.srv.status()
	if res.Hermes == nil || res.Hermes.State != hermesError || time.Since(start) > time.Second {
		t.Fatalf("%+v after %v", res.Hermes, time.Since(start))
	}
}
