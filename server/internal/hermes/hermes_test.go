package hermes

import (
	"context"
	"errors"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
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
	ticket, err := c.mintTicket(ctx, id, purposeInteractive, scopeHost)
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
		resp, err := c.Do(ctx, req)
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
