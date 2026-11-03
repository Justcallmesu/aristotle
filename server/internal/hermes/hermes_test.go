package hermes

import (
	"context"
	"errors"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
)

func testCtx(t *testing.T) context.Context {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	t.Cleanup(cancel)
	return ctx
}

func TestIdentifyAndDialHappyPath(t *testing.T) {
	f := newFakeHermes(t)
	c := f.connector()
	ctx := testCtx(t)

	id, err := c.Identify(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if id.RuntimeProtocol != 1 || id.InstanceID == "" || id.APIOrigin != f.srv.URL || len(id.ServedProfiles) != 2 {
		t.Fatalf("identity: %+v", id)
	}

	conn, err := c.Dial(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()

	ready, err := conn.Read(ctx)
	if err != nil || !strings.Contains(string(ready), "gateway.ready") {
		t.Fatalf("first frame = %q, %v (gateway.ready must survive the handshake)", ready, err)
	}
	if err := conn.Write(ctx, []byte(`{"jsonrpc":"2.0","id":7,"method":"session.list","params":{}}`)); err != nil {
		t.Fatal(err)
	}
	echo, err := conn.Read(ctx)
	if err != nil || !strings.Contains(string(echo), `"id":7`) {
		t.Fatalf("echo = %q, %v", echo, err)
	}

	f.mu.Lock()
	defer f.mu.Unlock()
	if len(f.firstFrames) < 1 || !strings.Contains(f.firstFrames[0], `"method":"client.capabilities"`) ||
		!strings.Contains(f.firstFrames[0], `"server_requests":true`) {
		t.Fatalf("first client frame must be client.capabilities, got %v", f.firstFrames)
	}
	want := mintRequest{Purpose: "interactive", Scope: "host", ProfileID: f.home}
	if len(f.minted) != 1 || f.minted[0] != want {
		t.Fatalf("minted = %+v, want [%+v]", f.minted, want)
	}
}

func TestTicketIsSingleUse(t *testing.T) {
	f := newFakeHermes(t)
	c := f.connector()
	ctx := testCtx(t)
	id, _ := c.Identify(ctx)
	ticket, err := c.mintTicket(ctx, id, f.home, purposeInteractive, scopeHost)
	if err != nil {
		t.Fatal(err)
	}
	ws, err := c.upgrade(ctx, id.APIOrigin, ticket)
	if err != nil {
		t.Fatal(err)
	}
	ws.CloseNow()
	_, err = c.upgrade(ctx, id.APIOrigin, ticket)
	if !errors.Is(err, ErrUpgradeRefused) || !strings.Contains(err.Error(), "403") {
		t.Fatalf("second redeem err = %v, want ErrUpgradeRefused HTTP 403", err)
	}
	if strings.Contains(err.Error(), ticket) {
		t.Fatal("error leaks the ticket")
	}
}

func TestDialNeverSendsOrigin(t *testing.T) {
	f := newFakeHermes(t)
	conn, err := f.connector().Dial(testCtx(t))
	if err != nil {
		t.Fatal(err)
	}
	conn.Close()
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.originSeen {
		t.Fatal("Origin header reached /api/ws")
	}
}

func TestUnsupportedProtocolFailsClosed(t *testing.T) {
	f := newFakeHermes(t)
	f.setRuntime(2)
	c := f.connector()
	if _, err := c.Identify(testCtx(t)); !errors.Is(err, ErrUnsupportedProtocol) || !strings.Contains(err.Error(), "2") {
		t.Fatalf("Identify err = %v", err)
	}
	if _, err := c.Dial(testCtx(t)); !errors.Is(err, ErrUnsupportedProtocol) {
		t.Fatalf("Dial err = %v", err)
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	if len(f.minted) != 0 {
		t.Fatal("a ticket was minted despite an unsupported protocol")
	}
}

func TestSocketMissingIsNotRunning(t *testing.T) {
	home, err := os.MkdirTemp("", "fh") // short path: sun_path is ~108 bytes
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { os.RemoveAll(home) })
	c, _ := New(Options{Home: home})
	if _, err := c.Identify(testCtx(t)); !errors.Is(err, ErrHermesNotRunning) {
		t.Fatalf("Identify err = %v", err)
	}
	if _, err := c.Dial(testCtx(t)); !errors.Is(err, ErrHermesNotRunning) {
		t.Fatalf("Dial err = %v", err)
	}
}

func TestStaleSocketFileIsNotRunning(t *testing.T) {
	f := newFakeHermes(t)
	f.ln.Close() // the file stays, nobody listens: ECONNREFUSED
	if _, err := f.connector().Identify(testCtx(t)); !errors.Is(err, ErrHermesNotRunning) {
		t.Fatalf("err = %v", err)
	}
}

func TestSocketPathFileIsHonoured(t *testing.T) {
	f := newFakeHermes(t)
	other := t.TempDir()
	if err := os.WriteFile(filepath.Join(other, "gateway.sock.path"), []byte(filepath.Join(f.home, "gateway.sock")+"\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	c, _ := New(Options{Home: other})
	if _, err := c.Identify(testCtx(t)); err != nil {
		t.Fatal(err)
	}
}

func TestRestartRedialsNewOrigin(t *testing.T) {
	f := newFakeHermes(t)
	c := f.connector()
	ctx := testCtx(t)
	first, err := c.Dial(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer first.Close()
	oldOrigin := f.srv.URL
	f.restart()
	if f.srv.URL == oldOrigin {
		t.Fatal("fake did not change api_origin")
	}
	second, err := c.Dial(ctx)
	if err != nil {
		t.Fatalf("Dial after restart: %v", err)
	}
	second.Close()
}

func TestDoMintsFreshTicketPerRequest(t *testing.T) {
	f := newFakeHermes(t)
	c := f.connector()
	ctx := testCtx(t)
	for i := 0; i < 2; i++ {
		req, _ := http.NewRequestWithContext(ctx, http.MethodGet, "http://placeholder.invalid/api/echo?x=1", nil)
		req.Header.Set("Origin", "http://evil.example")
		resp, err := c.Do(ctx, "", req)
		if err != nil {
			t.Fatal(err)
		}
		body, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		if resp.StatusCode != 200 || string(body) != "/api/echo?x=1" {
			t.Fatalf("status %d body %q", resp.StatusCode, body)
		}
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	if len(f.restHeaders) != 2 || f.restHeaders[0] == f.restHeaders[1] {
		t.Fatalf("tickets per request = %v, want two distinct", f.restHeaders)
	}
	for _, m := range f.minted {
		if m.Purpose != "native-http" || m.Scope != "" {
			t.Fatalf("REST ticket minted as %+v", m)
		}
	}
}

func TestBackoffMonotonicAndCapped(t *testing.T) {
	prev := time.Duration(0)
	for attempt := 0; attempt < 100; attempt++ {
		d := Backoff(attempt)
		if d < prev {
			t.Fatalf("Backoff(%d)=%v < previous %v", attempt, d, prev)
		}
		if d > 30*time.Second {
			t.Fatalf("Backoff(%d)=%v exceeds cap", attempt, d)
		}
		prev = d
	}
	if Backoff(0) != 500*time.Millisecond || Backoff(99) != 30*time.Second || Backoff(-3) != Backoff(0) {
		t.Fatalf("Backoff endpoints: %v %v %v", Backoff(0), Backoff(99), Backoff(-3))
	}
}

func doGet(t *testing.T, c *Connector, profile, path string, hdr map[string]string) *http.Response {
	t.Helper()
	ctx := testCtx(t)
	req, _ := http.NewRequestWithContext(ctx, http.MethodGet, "http://placeholder.invalid"+path, nil)
	for k, v := range hdr {
		req.Header.Set(k, v)
	}
	resp, err := c.Do(ctx, profile, req)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { resp.Body.Close() })
	return resp
}

func TestDoMintsTicketForTargetProfile(t *testing.T) {
	f := newFakeHermes(t)
	c := f.connector()
	resp := doGet(t, c, "pm", "/api/echo", nil)
	if resp.StatusCode != 200 {
		t.Fatalf("status %d", resp.StatusCode)
	}
	doGet(t, c, "", "/api/echo", nil)
	f.mu.Lock()
	defer f.mu.Unlock()
	if len(f.minted) != 2 || f.minted[0].ProfileID != f.home+"/profiles/pm" || f.minted[1].ProfileID != f.home {
		t.Fatalf("minted = %+v, want pm then launch profile", f.minted)
	}
	if f.minted[0].Purpose != "native-http" || f.minted[0].Scope != "" {
		t.Fatalf("minted = %+v", f.minted[0])
	}
}

func TestDoUnknownProfileMintsNothing(t *testing.T) {
	f := newFakeHermes(t)
	closed := &closeSpy{Reader: strings.NewReader("x")}
	req, _ := http.NewRequest(http.MethodPost, "http://x/api/echo", closed)
	_, err := f.connector().Do(testCtx(t), "nope", req)
	if !errors.Is(err, ErrUnknownProfile) {
		t.Fatalf("err = %v", err)
	}
	if !closed.closed {
		t.Fatal("request body not closed on early failure")
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	if len(f.minted) != 0 {
		t.Fatal("ticket minted for unknown profile")
	}
}

type closeSpy struct {
	io.Reader
	closed bool
}

func (c *closeSpy) Close() error { c.closed = true; return nil }

func TestDoDoesNotFollowRedirects(t *testing.T) {
	f := newFakeHermes(t)
	resp := doGet(t, f.connector(), "", "/api/redirect", nil)
	if resp.StatusCode != http.StatusTemporaryRedirect || resp.Header.Get("Location") != "/api/echo" {
		t.Fatalf("status %d location %q, want the 307 itself", resp.StatusCode, resp.Header.Get("Location"))
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.redeemRefused != 0 || len(f.restSeen) != 0 {
		t.Fatalf("redirect was followed: refused=%d echoes=%d", f.redeemRefused, len(f.restSeen))
	}
}

func TestDoStripsCallerCredentialHeaders(t *testing.T) {
	f := newFakeHermes(t)
	doGet(t, f.connector(), "", "/api/echo", map[string]string{
		"Authorization": "Bearer phone", "Cookie": "s=1", "X-Hermes-Gateway-Ticket": "forged",
		"x-hermes-other": "1", "Proxy-Authorization": "p", "X-Keep": "yes",
	})
	f.mu.Lock()
	defer f.mu.Unlock()
	if len(f.restSeen) != 1 {
		t.Fatalf("requests seen: %d", len(f.restSeen))
	}
	h := f.restSeen[0]
	for _, k := range []string{"Authorization", "Cookie", "X-Hermes-Other", "Proxy-Authorization"} {
		if h.Get(k) != "" {
			t.Errorf("header %s reached Hermes", k)
		}
	}
	if h.Get(ticketHTTPHeader) == "forged" || h.Get(ticketHTTPHeader) == "" {
		t.Errorf("ticket header = %q, want the minted ticket", h.Get(ticketHTTPHeader))
	}
	if h.Get("X-Keep") != "yes" {
		t.Error("harmless header was dropped")
	}
}

func TestDialKeepsSeveralPreAckFrames(t *testing.T) {
	f := newFakeHermes(t)
	f.preAck = 5
	ctx := testCtx(t)
	conn, err := f.connector().Dial(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	for i := 0; i < 5; i++ {
		frame, err := conn.Read(ctx)
		if err != nil || !strings.Contains(string(frame), `"n":`+string(rune('0'+i))) {
			t.Fatalf("pre-ack frame %d = %q, %v", i, frame, err)
		}
	}
}

func TestDialPreAckOverflowFails(t *testing.T) {
	f := newFakeHermes(t)
	f.preAck = maxPendingFrames + 5
	if _, err := f.connector().Dial(testCtx(t)); !errors.Is(err, ErrPendingOverflow) {
		t.Fatalf("err = %v, want ErrPendingOverflow", err)
	}
}

func TestDialAckTimeout(t *testing.T) {
	f := newFakeHermes(t)
	f.noAck = true
	c, _ := New(Options{Home: f.home, HandshakeTimeout: 200 * time.Millisecond})
	start := time.Now()
	_, err := c.Dial(context.Background())
	if err == nil || !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("err = %v, want deadline exceeded", err)
	}
	if time.Since(start) > 5*time.Second {
		t.Fatalf("handshake took %v", time.Since(start))
	}
}

func TestCloseCodesSurface(t *testing.T) {
	for _, tc := range []struct {
		code websocket.StatusCode
		want error
	}{{1013, ErrDraining}, {4403, ErrForbidden}} {
		f := newFakeHermes(t)
		f.closeCode = tc.code
		_, err := f.connector().Dial(testCtx(t))
		if !errors.Is(err, tc.want) {
			t.Fatalf("close %d: err = %v, want %v", tc.code, err, tc.want)
		}
		if code, ok := CloseCode(err); !ok || code != int(tc.code) {
			t.Fatalf("CloseCode = %d,%v want %d", code, ok, tc.code)
		}
	}
	if _, ok := CloseCode(errors.New("plain")); ok {
		t.Fatal("CloseCode reported a code for a plain error")
	}
}

func TestSocketDiscoveryPrefersDirectOverStalePointer(t *testing.T) {
	f := newFakeHermes(t)
	if err := os.WriteFile(filepath.Join(f.home, "gateway.sock.path"), []byte("/nonexistent/stale.sock\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := f.connector().Identify(testCtx(t)); err != nil {
		t.Fatalf("stale pointer broke discovery: %v", err)
	}
}

func TestStalePointerWithoutDirectIsNotRunning(t *testing.T) {
	home, _ := os.MkdirTemp("", "fh")
	t.Cleanup(func() { os.RemoveAll(home) })
	if err := os.WriteFile(filepath.Join(home, "gateway.sock.path"), []byte("/nonexistent/stale.sock\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	c, _ := New(Options{Home: home})
	if _, err := c.Identify(testCtx(t)); !errors.Is(err, ErrHermesNotRunning) {
		t.Fatalf("err = %v", err)
	}
}

func TestForeignUIDSocketRefused(t *testing.T) {
	f := newFakeHermes(t)
	old := ownUID
	ownUID = func() int { return os.Getuid() + 1 }
	t.Cleanup(func() { ownUID = old })
	_, err := f.connector().Identify(testCtx(t))
	if !errors.Is(err, ErrForeignSocket) {
		t.Fatalf("err = %v", err)
	}
}

func TestControlHonoursContextCancel(t *testing.T) {
	home, _ := os.MkdirTemp("", "fh")
	t.Cleanup(func() { os.RemoveAll(home) })
	ln, err := net.Listen("unix", filepath.Join(home, controlSocketName))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	go func() { // accept and never answer
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			t.Cleanup(func() { c.Close() })
		}
	}()
	c, _ := New(Options{Home: home})
	ctx, cancel := context.WithCancel(context.Background())
	time.AfterFunc(100*time.Millisecond, cancel)
	start := time.Now()
	_, err = c.Identify(ctx)
	if !errors.Is(err, context.Canceled) || time.Since(start) > 3*time.Second {
		t.Fatalf("err = %v after %v, want prompt context.Canceled", err, time.Since(start))
	}
}
