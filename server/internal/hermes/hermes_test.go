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

	"github.com/justcallmesu/aristotle/server/internal/hermes/hermestest"
)

// connector returns a Connector for the fake's home.
func connector(t *testing.T, f *hermestest.Fake) *Connector {
	t.Helper()
	c, err := New(Options{Home: f.Home})
	if err != nil {
		t.Fatal(err)
	}
	return c
}

func testCtx(t *testing.T) context.Context {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	t.Cleanup(cancel)
	return ctx
}

func TestIdentifyAndDialHappyPath(t *testing.T) {
	f := hermestest.New(t)
	c := connector(t, f)
	ctx := testCtx(t)

	id, err := c.Identify(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if id.RuntimeProtocol != 1 || id.InstanceID == "" || id.APIOrigin != f.URL() || len(id.ServedProfiles) != 2 {
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

	f.Mu.Lock()
	defer f.Mu.Unlock()
	if len(f.FirstFrames) < 1 || !strings.Contains(f.FirstFrames[0], `"method":"client.capabilities"`) ||
		!strings.Contains(f.FirstFrames[0], `"server_requests":true`) {
		t.Fatalf("first client frame must be client.capabilities, got %v", f.FirstFrames)
	}
	want := hermestest.MintRequest{Purpose: "interactive", Scope: "host", ProfileID: f.Home}
	if len(f.Minted) != 1 || f.Minted[0] != want {
		t.Fatalf("minted = %+v, want [%+v]", f.Minted, want)
	}
}

func TestTicketIsSingleUse(t *testing.T) {
	f := hermestest.New(t)
	c := connector(t, f)
	ctx := testCtx(t)
	id, _ := c.Identify(ctx)
	ticket, err := c.mintTicket(ctx, id, f.Home, purposeInteractive, scopeHost)
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
	f := hermestest.New(t)
	conn, err := connector(t, f).Dial(testCtx(t))
	if err != nil {
		t.Fatal(err)
	}
	conn.Close()
	f.Mu.Lock()
	defer f.Mu.Unlock()
	if f.OriginSeen {
		t.Fatal("Origin header reached /api/ws")
	}
}

func TestUnsupportedProtocolFailsClosed(t *testing.T) {
	f := hermestest.New(t)
	f.SetRuntime(2)
	c := connector(t, f)
	if _, err := c.Identify(testCtx(t)); !errors.Is(err, ErrUnsupportedProtocol) || !strings.Contains(err.Error(), "2") {
		t.Fatalf("Identify err = %v", err)
	}
	if _, err := c.Dial(testCtx(t)); !errors.Is(err, ErrUnsupportedProtocol) {
		t.Fatalf("Dial err = %v", err)
	}
	f.Mu.Lock()
	defer f.Mu.Unlock()
	if len(f.Minted) != 0 {
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
	f := hermestest.New(t)
	f.Ln.Close() // the file stays, nobody listens: ECONNREFUSED
	if _, err := connector(t, f).Identify(testCtx(t)); !errors.Is(err, ErrHermesNotRunning) {
		t.Fatalf("err = %v", err)
	}
}

func TestSocketPathFileIsHonoured(t *testing.T) {
	f := hermestest.New(t)
	other := t.TempDir()
	if err := os.WriteFile(filepath.Join(other, "gateway.sock.path"), []byte(filepath.Join(f.Home, "gateway.sock")+"\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	c, _ := New(Options{Home: other})
	if _, err := c.Identify(testCtx(t)); err != nil {
		t.Fatal(err)
	}
}

func TestRestartRedialsNewOrigin(t *testing.T) {
	f := hermestest.New(t)
	c := connector(t, f)
	ctx := testCtx(t)
	first, err := c.Dial(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer first.Close()
	oldOrigin := f.URL()
	f.Restart()
	if f.URL() == oldOrigin {
		t.Fatal("fake did not change api_origin")
	}
	second, err := c.Dial(ctx)
	if err != nil {
		t.Fatalf("Dial after restart: %v", err)
	}
	second.Close()
}

func TestDoMintsFreshTicketPerRequest(t *testing.T) {
	f := hermestest.New(t)
	c := connector(t, f)
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
	f.Mu.Lock()
	defer f.Mu.Unlock()
	if len(f.RestHeaders) != 2 || f.RestHeaders[0] == f.RestHeaders[1] {
		t.Fatalf("tickets per request = %v, want two distinct", f.RestHeaders)
	}
	for _, m := range f.Minted {
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
	f := hermestest.New(t)
	c := connector(t, f)
	resp := doGet(t, c, "pm", "/api/echo", nil)
	if resp.StatusCode != 200 {
		t.Fatalf("status %d", resp.StatusCode)
	}
	doGet(t, c, "", "/api/echo", nil)
	f.Mu.Lock()
	defer f.Mu.Unlock()
	if len(f.Minted) != 2 || f.Minted[0].ProfileID != f.Home+"/profiles/pm" || f.Minted[1].ProfileID != f.Home {
		t.Fatalf("minted = %+v, want pm then launch profile", f.Minted)
	}
	if f.Minted[0].Purpose != "native-http" || f.Minted[0].Scope != "" {
		t.Fatalf("minted = %+v", f.Minted[0])
	}
}

func TestDoUnknownProfileMintsNothing(t *testing.T) {
	f := hermestest.New(t)
	closed := &closeSpy{Reader: strings.NewReader("x")}
	req, _ := http.NewRequest(http.MethodPost, "http://x/api/echo", closed)
	_, err := connector(t, f).Do(testCtx(t), "nope", req)
	if !errors.Is(err, ErrUnknownProfile) {
		t.Fatalf("err = %v", err)
	}
	if !closed.closed {
		t.Fatal("request body not closed on early failure")
	}
	f.Mu.Lock()
	defer f.Mu.Unlock()
	if len(f.Minted) != 0 {
		t.Fatal("ticket minted for unknown profile")
	}
}

type closeSpy struct {
	io.Reader
	closed bool
}

func (c *closeSpy) Close() error { c.closed = true; return nil }

func TestDoDoesNotFollowRedirects(t *testing.T) {
	f := hermestest.New(t)
	resp := doGet(t, connector(t, f), "", "/api/redirect", nil)
	if resp.StatusCode != http.StatusTemporaryRedirect || resp.Header.Get("Location") != "/api/echo" {
		t.Fatalf("status %d location %q, want the 307 itself", resp.StatusCode, resp.Header.Get("Location"))
	}
	f.Mu.Lock()
	defer f.Mu.Unlock()
	if f.RedeemRefused != 0 || len(f.RestSeen) != 0 {
		t.Fatalf("redirect was followed: refused=%d echoes=%d", f.RedeemRefused, len(f.RestSeen))
	}
}

func TestDoStripsCallerCredentialHeaders(t *testing.T) {
	f := hermestest.New(t)
	doGet(t, connector(t, f), "", "/api/echo", map[string]string{
		"Authorization": "Bearer phone", "Cookie": "s=1", "X-Hermes-Gateway-Ticket": "forged",
		"x-hermes-other": "1", "Proxy-Authorization": "p", "X-Keep": "yes",
	})
	f.Mu.Lock()
	defer f.Mu.Unlock()
	if len(f.RestSeen) != 1 {
		t.Fatalf("requests seen: %d", len(f.RestSeen))
	}
	h := f.RestSeen[0]
	for _, k := range []string{"Authorization", "Cookie", "X-Hermes-Other", "Proxy-Authorization"} {
		if h.Get(k) != "" {
			t.Errorf("header %s reached Hermes", k)
		}
	}
	if h.Get(ticketHTTPHeader) == "forged" || h.Get(ticketHTTPHeader) == "" {
		t.Errorf("ticket header = %q, want the minted ticket", h.Get(ticketHTTPHeader))
	}
	if h.Get("X-Keep") != "" {
		t.Error("header outside the allowlist was forwarded")
	}
}

// Keys set straight in the map, not through Header.Set, are not canonical; they must still go.
func TestDoStripsNonCanonicalCredentialKeys(t *testing.T) {
	f := hermestest.New(t)
	c := connector(t, f)
	ctx := testCtx(t)
	req, _ := http.NewRequestWithContext(ctx, http.MethodGet, "http://placeholder.invalid/api/echo", nil)
	req.Header["authorization"] = []string{"Bearer phone"}
	req.Header["x-hermes-gateway-ticket"] = []string{"forged"}
	req.Header["cookie"] = []string{"s=1"}
	resp, err := c.Do(ctx, "", req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	f.Mu.Lock()
	defer f.Mu.Unlock()
	if len(f.RestSeen) != 1 {
		t.Fatalf("requests seen: %d (status %d)", len(f.RestSeen), resp.StatusCode)
	}
	h := f.RestSeen[0]
	if h.Get("Authorization") != "" || h.Get("Cookie") != "" {
		t.Errorf("credential header reached Hermes: %v", h)
	}
	if got := h.Values(ticketHTTPHeader); len(got) != 1 || got[0] == "forged" {
		t.Errorf("ticket header = %v, want exactly the minted ticket", got)
	}
}

func TestDoForwardsOnlyAllowlistedHeaders(t *testing.T) {
	f := hermestest.New(t)
	doGet(t, connector(t, f), "", "/api/echo", map[string]string{
		"X-Forwarded-For": "1.2.3.4", "X-Forwarded-Proto": "https", "X-Forwarded-Prefix": "/x", "Forwarded": "for=1.2.3.4",
		"X-Real-Ip": "1.2.3.4", "X-Keep": "no",
		"Accept": "application/json", "Accept-Encoding": "gzip", "Content-Type": "application/json", "User-Agent": "aristotle/1",
	})
	f.Mu.Lock()
	defer f.Mu.Unlock()
	if len(f.RestSeen) != 1 {
		t.Fatalf("requests seen: %d", len(f.RestSeen))
	}
	h := f.RestSeen[0]
	for _, k := range []string{"X-Forwarded-For", "X-Forwarded-Proto", "X-Forwarded-Prefix", "Forwarded", "X-Real-Ip", "X-Keep"} {
		if h.Get(k) != "" {
			t.Errorf("header %s reached Hermes", k)
		}
	}
	for k, want := range map[string]string{"Accept": "application/json", "Content-Type": "application/json", "User-Agent": "aristotle/1"} {
		if h.Get(k) != want {
			t.Errorf("header %s = %q, want %q", k, h.Get(k), want)
		}
	}
}

func TestDialKeepsSeveralPreAckFrames(t *testing.T) {
	f := hermestest.New(t)
	f.PreAck = 5
	ctx := testCtx(t)
	conn, err := connector(t, f).Dial(ctx)
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
	f := hermestest.New(t)
	f.PreAck = maxPendingFrames + 5
	if _, err := connector(t, f).Dial(testCtx(t)); !errors.Is(err, ErrPendingOverflow) {
		t.Fatalf("err = %v, want ErrPendingOverflow", err)
	}
}

func TestDialAckTimeout(t *testing.T) {
	f := hermestest.New(t)
	f.NoAck = true
	c, _ := New(Options{Home: f.Home, HandshakeTimeout: 200 * time.Millisecond})
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
		f := hermestest.New(t)
		f.CloseCode = tc.code
		_, err := connector(t, f).Dial(testCtx(t))
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

func TestCloseCodesSurfaceOnReadAfterDial(t *testing.T) {
	for _, tc := range []struct {
		code websocket.StatusCode
		want error
	}{{1013, ErrDraining}, {4403, ErrForbidden}} {
		f := hermestest.New(t)
		f.CloseAfterAck = tc.code
		ctx := testCtx(t)
		conn, err := connector(t, f).Dial(ctx)
		if err != nil {
			t.Fatalf("close %d: Dial: %v", tc.code, err)
		}
		if _, err := conn.Read(ctx); err != nil { // the buffered gateway.ready
			t.Fatalf("close %d: first Read: %v", tc.code, err)
		}
		_, err = conn.Read(ctx)
		if !errors.Is(err, tc.want) {
			t.Fatalf("close %d: Read err = %v, want %v", tc.code, err, tc.want)
		}
		if code, ok := CloseCode(err); !ok || code != int(tc.code) {
			t.Fatalf("CloseCode = %d,%v want %d", code, ok, tc.code)
		}
		conn.Close()
	}
}

func TestSocketDiscoveryPrefersDirectOverStalePointer(t *testing.T) {
	f := hermestest.New(t)
	if err := os.WriteFile(filepath.Join(f.Home, "gateway.sock.path"), []byte("/nonexistent/stale.sock\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := connector(t, f).Identify(testCtx(t)); err != nil {
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
	f := hermestest.New(t)
	old := ownUID
	ownUID = func() int { return os.Getuid() + 1 }
	t.Cleanup(func() { ownUID = old })
	_, err := connector(t, f).Identify(testCtx(t))
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

// A POST body reaches Hermes intact with its Content-Type and the real length, whatever Content-Length
// header the caller set (the client sends req.ContentLength; the header is not forwarded).
func TestDoForwardsPostBody(t *testing.T) {
	f := hermestest.New(t)
	ctx := testCtx(t)
	body := `{"title":"x","pinned":true}`
	req, _ := http.NewRequestWithContext(ctx, http.MethodPost, "http://placeholder.invalid/api/echo", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Content-Length", "999")
	resp, err := connector(t, f).Do(ctx, "", req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	f.Mu.Lock()
	defer f.Mu.Unlock()
	if resp.StatusCode != 200 || len(f.RestBodies) != 1 {
		t.Fatalf("status %d, requests seen %d", resp.StatusCode, len(f.RestBodies))
	}
	if string(f.RestBodies[0]) != body || f.RestLengths[0] != int64(len(body)) {
		t.Fatalf("body %q length %d, want %q length %d", f.RestBodies[0], f.RestLengths[0], body, len(body))
	}
	if ct := f.RestSeen[0].Get("Content-Type"); ct != "application/json" {
		t.Fatalf("Content-Type = %q", ct)
	}
}

// profileHome makes <fake root>/profiles/<name> with no gateway of its own and marks the fake's home as a
// Hermes home (config.yaml), as the real ~/.hermes is.
func profileHome(t *testing.T, f *hermestest.Fake, name string, markRoot bool) string {
	t.Helper()
	home := filepath.Join(f.Home, "profiles", name)
	if err := os.MkdirAll(home, 0o700); err != nil {
		t.Fatal(err)
	}
	if markRoot {
		if err := os.WriteFile(filepath.Join(f.Home, "config.yaml"), []byte("{}\n"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	return home
}

// D18: HERMES_HOME=<root>/profiles/pm (any shell Hermes launched) has no gateway.sock; the root's gateway
// serves pm, so Dial and Do go through it and bind tickets to pm.
func TestProfileHomeWithoutSocketUsesTheRootGateway(t *testing.T) {
	f := hermestest.New(t)
	pm := profileHome(t, f, "pm", true)
	c, err := New(Options{Home: pm})
	if err != nil {
		t.Fatal(err)
	}
	conn, err := c.Dial(testCtx(t))
	if err != nil {
		t.Fatalf("Dial err = %v", err)
	}
	conn.Close()
	doGet(t, c, "", "/api/echo", nil)
	f.Mu.Lock()
	defer f.Mu.Unlock()
	want := []hermestest.MintRequest{
		{Purpose: "interactive", Scope: "host", ProfileID: f.Home + "/profiles/pm"},
		{Purpose: "native-http", Scope: "", ProfileID: f.Home + "/profiles/pm"},
	}
	if len(f.Minted) != 2 || f.Minted[0] != want[0] || f.Minted[1] != want[1] {
		t.Fatalf("minted = %+v, want %+v", f.Minted, want)
	}
}

// The served-profile check stays: a root gateway that does not list this profile home is not its gateway.
func TestRootGatewayNotServingTheProfileIsNotRunning(t *testing.T) {
	f := hermestest.New(t)
	other := profileHome(t, f, "other", true)
	c, _ := New(Options{Home: other})
	if _, err := c.Dial(testCtx(t)); !errors.Is(err, ErrHermesNotRunning) {
		t.Fatalf("Dial err = %v, want ErrHermesNotRunning", err)
	}
	f.Mu.Lock()
	defer f.Mu.Unlock()
	if len(f.Minted) != 0 {
		t.Fatalf("minted %+v for a profile the gateway does not serve", f.Minted)
	}
}

// Only a provable Hermes root is followed (hermes_constants._is_hermes_profiles_root): a directory
// that merely has a profiles/ segment, or a dot-named profile, is not.
func TestRootFallbackNeedsAHermesRootAndAProfileName(t *testing.T) {
	f := hermestest.New(t)
	for _, tc := range []struct {
		name     string
		markRoot bool
	}{{"pm", false}, {".pm", true}} {
		home := profileHome(t, f, tc.name, tc.markRoot)
		c, _ := New(Options{Home: home})
		if _, err := c.Identify(testCtx(t)); !errors.Is(err, ErrHermesNotRunning) {
			t.Fatalf("%s (marked %v): err = %v, want ErrHermesNotRunning", tc.name, tc.markRoot, err)
		}
	}
}

// Neither the profile nor its root has a gateway: still plain "not running".
func TestProfileHomeWithoutAnyGatewayIsNotRunning(t *testing.T) {
	root, _ := os.MkdirTemp("", "fh")
	t.Cleanup(func() { os.RemoveAll(root) })
	if err := os.MkdirAll(filepath.Join(root, ".hermes", "profiles", "pm"), 0o700); err != nil {
		t.Fatal(err)
	}
	c, _ := New(Options{Home: filepath.Join(root, ".hermes", "profiles", "pm")})
	if c.root != filepath.Join(root, ".hermes") {
		t.Fatalf("root = %q", c.root)
	}
	if _, err := c.Identify(testCtx(t)); !errors.Is(err, ErrHermesNotRunning) {
		t.Fatalf("err = %v", err)
	}
}
