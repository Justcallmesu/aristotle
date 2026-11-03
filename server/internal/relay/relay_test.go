package relay

import (
	"bytes"
	"context"
	"errors"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"runtime"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"

	"github.com/justcallmesu/aristotle/server/internal/devices"
	"github.com/justcallmesu/aristotle/server/internal/hermes"
	"github.com/justcallmesu/aristotle/server/internal/hermes/hermestest"
	"github.com/justcallmesu/aristotle/server/internal/httpapi"
	"github.com/justcallmesu/aristotle/server/internal/pairing"
)

type lockedBuffer struct {
	mu  sync.Mutex
	buf bytes.Buffer
}

func (b *lockedBuffer) Write(p []byte) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.Write(p)
}

func (b *lockedBuffer) String() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.String()
}

// harness is the real remote router (auth, logging, /v1/ws) on an httptest server, a relay hub and a
// fake Hermes. No real ~/.hermes is touched: the connector's home is the fake's temp dir.
type harness struct {
	fake  *hermestest.Fake
	store *devices.Store
	hub   *Hub
	srv   *httptest.Server
	logs  *lockedBuffer
	dev   devices.Public
	token string
}

func newHarness(t *testing.T, tune func(*Options)) *harness {
	t.Helper()
	fake := hermestest.New(t)
	conn, err := hermes.New(hermes.Options{Home: fake.Home})
	if err != nil {
		t.Fatal(err)
	}
	return newHarnessWith(t, fake, conn, tune)
}

func newHarnessWith(t *testing.T, fake *hermestest.Fake, d Dialer, tune func(*Options)) *harness {
	t.Helper()
	logs := &lockedBuffer{}
	log := slog.New(slog.NewTextHandler(logs, nil))
	store, err := devices.Open(t.TempDir(), nil, log)
	if err != nil {
		t.Fatal(err)
	}
	opts := Options{Dialer: d, Devices: store, Log: log}
	if tune != nil {
		tune(&opts)
	}
	hub := New(opts)
	router := httpapi.NewRemoteRouter("t", log, httpapi.Deps{Codes: &pairing.Codes{}, Devices: store, Relay: hub, Listener: "tailscale"})
	srv := httptest.NewServer(router)
	t.Cleanup(func() {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = hub.Shutdown(ctx)
		srv.Close()
	})
	dev, token, err := store.Add("phone")
	if err != nil {
		t.Fatal(err)
	}
	return &harness{fake: fake, store: store, hub: hub, srv: srv, logs: logs, dev: dev, token: token}
}

func testCtx(t *testing.T) context.Context {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	t.Cleanup(cancel)
	return ctx
}

func (h *harness) url() string { return "ws" + strings.TrimPrefix(h.srv.URL, "http") + "/v1/ws" }

// dial opens /v1/ws as a phone with token.
func (h *harness) dial(t *testing.T, token string) (*websocket.Conn, *http.Response, error) {
	t.Helper()
	hdr := http.Header{}
	if token != "" {
		hdr.Set("Authorization", "Bearer "+token)
	}
	pc, resp, err := websocket.Dial(testCtx(t), h.url(), &websocket.DialOptions{HTTPHeader: hdr})
	if pc != nil {
		pc.SetReadLimit(-1)
		t.Cleanup(func() { pc.CloseNow() })
	}
	return pc, resp, err
}

// phone opens a relay and reads the gateway.ready frame Hermes sent first.
func (h *harness) phone(t *testing.T, token string) *websocket.Conn {
	t.Helper()
	pc, _, err := h.dial(t, token)
	if err != nil {
		t.Fatal(err)
	}
	if got := read(t, pc); !strings.Contains(got, "gateway.ready") {
		t.Fatalf("first frame = %s, want gateway.ready", got)
	}
	return pc
}

func read(t *testing.T, pc *websocket.Conn) string {
	t.Helper()
	typ, b, err := pc.Read(testCtx(t))
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	if typ != websocket.MessageText {
		t.Fatalf("frame type %v, want text", typ)
	}
	return string(b)
}

func write(t *testing.T, pc *websocket.Conn, frame string) {
	t.Helper()
	if err := pc.Write(testCtx(t), websocket.MessageText, []byte(frame)); err != nil {
		t.Fatalf("write: %v", err)
	}
}

// closeOf reads until the socket closes and returns the close frame.
func closeOf(t *testing.T, pc *websocket.Conn, within time.Duration) websocket.CloseError {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), within)
	defer cancel()
	for {
		_, _, err := pc.Read(ctx)
		if err == nil {
			continue
		}
		var ce websocket.CloseError
		if !errors.As(err, &ce) {
			t.Fatalf("socket ended without a close frame within %v: %v", within, err)
		}
		return ce
	}
}

func wantClose(t *testing.T, pc *websocket.Conn, code websocket.StatusCode, within time.Duration) websocket.CloseError {
	t.Helper()
	ce := closeOf(t, pc, within)
	if ce.Code != code {
		t.Fatalf("close = %d %q, want %d", ce.Code, ce.Reason, code)
	}
	return ce
}

// waitFor polls cond for up to 5 s.
func waitFor(t *testing.T, what string, cond func() bool) {
	t.Helper()
	for deadline := time.Now().Add(5 * time.Second); time.Now().Before(deadline); time.Sleep(10 * time.Millisecond) {
		if cond() {
			return
		}
	}
	t.Fatalf("timed out waiting for %s", what)
}

func TestRelayHappyPath(t *testing.T) {
	h := newHarness(t, nil)
	pc := h.phone(t, h.token)

	// D4: the request line is written at upgrade time, while the socket is still open.
	waitFor(t, "request log at upgrade", func() bool {
		return strings.Contains(h.logs.String(), `route="GET /v1/ws" status=101`)
	})
	waitFor(t, "open line", func() bool {
		return strings.Contains(h.logs.String(), `msg="relay open" device_id=`+h.dev.ID+" listener=tailscale")
	})

	list := `{"jsonrpc":"2.0","id":7,"method":"session.list","params":{}}`
	write(t, pc, list)
	if got := read(t, pc); got != list {
		t.Fatalf("echo = %s", got)
	}
	shapeA := string(capturedFrame(t, "c-client1.jsonl", 15))
	write(t, pc, shapeA)
	if got := read(t, pc); got != shapeA {
		t.Fatal("shape A did not come back byte for byte")
	}
	shapeB := capturedFrame(t, "c-client1.jsonl", 26)
	write(t, pc, string(shapeB))
	got := read(t, pc)
	want, _ := Redact(shapeB)
	if got != string(want) || strings.Contains(got, `"system_prompt"`) || !strings.Contains(got, `"system_prompt_chars"`) {
		t.Fatal("shape B did not come back redacted")
	}

	h.fake.Mu.Lock()
	frames := append([]string(nil), h.fake.FirstFrames...)
	h.fake.Mu.Unlock()
	if len(frames) != 4 || !strings.Contains(frames[0], "client.capabilities") || frames[1] != list ||
		frames[2] != shapeA || frames[3] != string(shapeB) {
		t.Fatalf("Hermes saw %d frames; phone frames must arrive unchanged after the connector's capabilities", len(frames))
	}

	pc.Close(websocket.StatusNormalClosure, "")
	waitFor(t, "close line", func() bool { return strings.Contains(h.logs.String(), "relay closed") })
	logs := h.logs.String()
	for _, want := range []string{"device_id=" + h.dev.ID, "close_code=1000", "closed_by=phone", "frames_in=3", "frames_out=4",
		`methods="event=2,session.list=1"`, "duration="} {
		if !strings.Contains(logs, want) {
			t.Errorf("close line lacks %q:\n%s", want, logs)
		}
	}
	waitFor(t, "relay unregistered", func() bool { return h.hub.Count(h.dev.ID) == 0 })
}

// The LAN listener serves TLS with HTTP/2 enabled; a phone's HTTP/1.1 upgrade over TLS must still work.
func TestRelayOverTLSWithHTTP2Enabled(t *testing.T) {
	h := newHarness(t, nil)
	tlsSrv := httptest.NewUnstartedServer(h.srv.Config.Handler)
	tlsSrv.EnableHTTP2 = true
	tlsSrv.StartTLS()
	defer tlsSrv.Close()
	client := tlsSrv.Client() // trusts the test cert; offers only http/1.1 for the upgrade
	pc, _, err := websocket.Dial(testCtx(t), "wss"+strings.TrimPrefix(tlsSrv.URL, "https")+"/v1/ws", &websocket.DialOptions{
		HTTPClient: client, HTTPHeader: http.Header{"Authorization": {"Bearer " + h.token}}})
	if err != nil {
		t.Fatal(err)
	}
	defer pc.CloseNow()
	if got := read(t, pc); !strings.Contains(got, "gateway.ready") {
		t.Fatalf("first frame over TLS = %s", got)
	}
}

func TestRelayUnauthenticated(t *testing.T) {
	h := newHarness(t, nil)
	for _, token := range []string{"", "not-a-token"} {
		_, resp, err := h.dial(t, token)
		if err == nil || resp == nil || resp.StatusCode != http.StatusUnauthorized {
			t.Fatalf("token %q: err=%v resp=%v, want HTTP 401", token, err, resp)
		}
	}
	h.fake.Mu.Lock()
	defer h.fake.Mu.Unlock()
	if len(h.fake.Minted) != 0 {
		t.Fatal("an unauthenticated upgrade reached Hermes")
	}
}

func TestRelayDialErrorsBecomeCloseCodes(t *testing.T) {
	notRunningHome, err := os.MkdirTemp("", "fh")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { os.RemoveAll(notRunningHome) })
	for _, tc := range []struct {
		name   string
		setup  func(f *hermestest.Fake)
		home   string
		code   websocket.StatusCode
		reason string
	}{
		{"not running", nil, notRunningHome, CodeHermesNotRunning, "hermes_not_running"},
		{"unsupported protocol", func(f *hermestest.Fake) { f.SetRuntime(2) }, "", CodeHermesUnsupported, "hermes_protocol_unsupported (unsupported hermes runtime protocol: 2)"},
		{"draining", func(f *hermestest.Fake) { f.CloseCode = 1013 }, "", CodeTryAgain, "try_again"},
		{"refused", func(f *hermestest.Fake) { f.CloseCode = 4403 }, "", CodeHermesUnavailable, "hermes_unavailable"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			fake := hermestest.New(t)
			if tc.setup != nil {
				tc.setup(fake)
			}
			home := tc.home
			if home == "" {
				home = fake.Home
			}
			conn, _ := hermes.New(hermes.Options{Home: home})
			h := newHarnessWith(t, fake, conn, nil)
			pc, _, err := h.dial(t, h.token)
			if err != nil {
				t.Fatalf("upgrade must be accepted before the dial error is reported: %v", err)
			}
			ce := wantClose(t, pc, tc.code, 5*time.Second)
			if ce.Reason != tc.reason || strings.Contains(ce.Reason, "tkt-") {
				t.Fatalf("reason = %q, want %q", ce.Reason, tc.reason)
			}
		})
	}
}

func TestRelayForwardsHermesClose(t *testing.T) {
	for _, tc := range []struct {
		hermes websocket.StatusCode
		phone  websocket.StatusCode
		reason string
	}{
		{1013, CodeTryAgain, "test close"},
		{1000, websocket.StatusNormalClosure, "test close"},
		{4403, CodeHermesUnavailable, reasonHermesRefused},
	} {
		h := newHarness(t, nil)
		h.fake.CloseAfterAck = tc.hermes
		pc := h.phone(t, h.token)
		ce := wantClose(t, pc, tc.phone, 5*time.Second)
		if ce.Reason != tc.reason {
			t.Fatalf("hermes %d: reason %q, want %q", tc.hermes, ce.Reason, tc.reason)
		}
	}
}

func TestRelayRateLimit(t *testing.T) {
	h := newHarness(t, func(o *Options) { o.MaxFramesPerMinute = 5 })
	pc := h.phone(t, h.token)
	for i := 0; i < 6; i++ {
		write(t, pc, `{"jsonrpc":"2.0","id":1,"method":"gateway.ping","params":{}}`)
	}
	wantClose(t, pc, CodeRateLimited, 5*time.Second)
	phoneFrames := func() int { h.fake.Mu.Lock(); defer h.fake.Mu.Unlock(); return len(h.fake.FirstFrames) - 1 }
	waitFor(t, "5 frames at Hermes", func() bool { return phoneFrames() >= 5 })
	time.Sleep(100 * time.Millisecond)
	if n := phoneFrames(); n != 5 {
		t.Fatalf("Hermes got %d phone frames, want 5 (the 6th is refused)", n)
	}
}

func TestWindowAllowsNPerPeriod(t *testing.T) {
	w := newWindow(3, time.Minute)
	t0 := time.Unix(0, 0)
	for i, tc := range []struct {
		at   time.Duration
		want bool
	}{{0, true}, {time.Second, true}, {2 * time.Second, true}, {59 * time.Second, false}, {60 * time.Second, true},
		{61 * time.Second, true}, {61 * time.Second, false}, {62 * time.Second, true}} {
		if got := w.allow(t0.Add(tc.at)); got != tc.want {
			t.Fatalf("event %d at %v: allow=%v, want %v", i, tc.at, got, tc.want)
		}
	}
}

func TestRelayFrameLimits(t *testing.T) {
	h := newHarness(t, nil)
	h.fake.ReadLimit = 2 * DefaultMaxFrameBytes
	pc := h.phone(t, h.token)
	big := strings.Repeat("x", DefaultMaxFrameBytes)
	write(t, pc, big) // exactly 1 MiB passes and comes back (the phone-side write has no relay limit)
	if got := read(t, pc); len(got) != len(big) {
		t.Fatalf("echo of 1 MiB frame: %d bytes", len(got))
	}
	write(t, pc, big+"x")
	wantClose(t, pc, CodeTooBig, 5*time.Second)

	pc2 := h.phone(t, h.token)
	if err := pc2.Write(testCtx(t), websocket.MessageBinary, []byte("{}")); err != nil {
		t.Fatal(err)
	}
	wantClose(t, pc2, CodeTextOnly, 5*time.Second)
}

func TestRelayPerDeviceCap(t *testing.T) {
	h := newHarness(t, nil)
	for i := 0; i < DefaultMaxPerDevice; i++ {
		h.phone(t, h.token)
	}
	pc, _, err := h.dial(t, h.token)
	if err != nil {
		t.Fatal(err)
	}
	wantClose(t, pc, CodeTooMany, 5*time.Second)
	_, other, _ := h.store.Add("tablet")
	h.phone(t, other) // the cap is per device
}

func TestRelayRevokeClosesWithinOneSecond(t *testing.T) {
	h := newHarness(t, nil)
	a, b := h.phone(t, h.token), h.phone(t, h.token)
	_, otherToken, _ := h.store.Add("tablet")
	other := h.phone(t, otherToken)

	start := time.Now()
	if _, err := h.store.Revoke(h.dev.ID); err != nil {
		t.Fatal(err)
	}
	for _, pc := range []*websocket.Conn{a, b} {
		ce := wantClose(t, pc, CodeRevoked, time.Second)
		if ce.Reason != "revoked" {
			t.Fatalf("reason %q", ce.Reason)
		}
	}
	if d := time.Since(start); d > time.Second {
		t.Fatalf("revoke took %v", d)
	}
	write(t, other, `{"id":1}`)
	if got := read(t, other); got != `{"id":1}` {
		t.Fatalf("other device's relay broke: %s", got)
	}
	if _, resp, err := h.dial(t, h.token); err == nil || resp.StatusCode != http.StatusUnauthorized {
		t.Fatal("revoked token could open a new relay")
	}
}

// A device revoked after authentication but before its relay registered is still closed.
func TestRelayDeviceGoneAtRegisterIsRevoked(t *testing.T) {
	h := newHarness(t, nil)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h.hub.ServeRelay(w, r, "dev_gone", "lan")
	}))
	defer srv.Close()
	pc, _, err := websocket.Dial(testCtx(t), "ws"+strings.TrimPrefix(srv.URL, "http"), nil)
	if err != nil {
		t.Fatal(err)
	}
	defer pc.CloseNow()
	wantClose(t, pc, CodeRevoked, 5*time.Second)
}

func TestRelayShutdownClosesGoingAway(t *testing.T) {
	h := newHarness(t, nil)
	a, b := h.phone(t, h.token), h.phone(t, h.token)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	done := make(chan error, 1)
	start := time.Now()
	go func() { done <- h.hub.Shutdown(ctx) }()
	for _, pc := range []*websocket.Conn{a, b} {
		wantClose(t, pc, CodeGoingAway, 5*time.Second)
	}
	if err := <-done; err != nil {
		t.Fatalf("Shutdown: %v", err)
	}
	if d := time.Since(start); d > 5*time.Second {
		t.Fatalf("shutdown took %v", d)
	}
	if h.hub.Count(h.dev.ID) != 0 {
		t.Fatal("relays still registered after Shutdown")
	}
	pc, _, err := h.dial(t, h.token)
	if err != nil {
		t.Fatal(err)
	}
	wantClose(t, pc, CodeGoingAway, 5*time.Second)
}

func TestRelayKeepaliveClosesWithoutPong(t *testing.T) {
	h := newHarness(t, func(o *Options) { o.PingInterval = 50 * time.Millisecond; o.PongTimeout = 100 * time.Millisecond })
	pc, _, err := h.dial(t, h.token)
	if err != nil {
		t.Fatal(err)
	}
	time.Sleep(500 * time.Millisecond) // not reading: pings go unanswered
	wantClose(t, pc, CodePongTimeout, 5*time.Second)
}

func TestRelayKeepaliveKeepsReadingPhoneOpen(t *testing.T) {
	h := newHarness(t, func(o *Options) { o.PingInterval = 20 * time.Millisecond; o.PongTimeout = 200 * time.Millisecond })
	pc := h.phone(t, h.token)
	ctx, cancel := context.WithTimeout(context.Background(), 300*time.Millisecond)
	defer cancel()
	if _, _, err := pc.Read(ctx); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("read while pinged: %v, want only the deadline", err)
	}
}

func TestRelayNoGoroutineLeak(t *testing.T) {
	h := newHarness(t, nil)
	cycle := func() {
		pc := h.phone(t, h.token)
		write(t, pc, `{"id":1}`)
		read(t, pc)
		pc.Close(websocket.StatusNormalClosure, "")
		waitFor(t, "relay unregistered", func() bool { return h.hub.Count(h.dev.ID) == 0 })
	}
	cycle() // warm up pools and the fake's listeners
	base := settledGoroutines()
	for i := 0; i < 20; i++ {
		cycle()
	}
	var after int
	waitFor(t, "goroutines to settle", func() bool { after = settledGoroutines(); return after <= base+2 })
	t.Logf("goroutines: %d before, %d after 20 cycles", base, after)
}

func settledGoroutines() int {
	runtime.GC()
	time.Sleep(50 * time.Millisecond)
	return runtime.NumGoroutine()
}

func TestRelayAuditLinesHaveNoFrameContent(t *testing.T) {
	h := newHarness(t, nil)
	pc := h.phone(t, h.token)
	secret := "SECRET-PROMPT-TEXT-1234"
	frames := []string{
		`{"jsonrpc":"2.0","id":1,"method":"prompt.submit","params":{"text":"` + secret + `"}}`,
		`{"jsonrpc":"2.0","id":2,"method":"` + secret + ` as a method","params":{}}`,
		`{"jsonrpc":"2.0","id":"srq-1","result":{"answer":"` + secret + `"}}`,
	}
	for _, f := range frames {
		write(t, pc, f)
		read(t, pc)
	}
	pc.Close(websocket.StatusNormalClosure, "")
	waitFor(t, "close line", func() bool { return strings.Contains(h.logs.String(), "relay closed") })
	logs := h.logs.String()
	if strings.Contains(logs, secret) || strings.Contains(logs, h.token) || strings.Contains(logs, "tkt-") {
		t.Fatalf("log holds frame content, token or ticket:\n%s", logs)
	}
	if !strings.Contains(logs, `methods="other=1,prompt.submit=1"`) {
		t.Fatalf("method summary:\n%s", logs)
	}
}

func TestCloseMappings(t *testing.T) {
	if got := clip(strings.Repeat("é", 100)); len(got) > maxCloseReasonBytes || !strings.HasPrefix(strings.Repeat("é", 100), got) {
		t.Fatalf("clip: %d bytes", len(got))
	}
	for code, want := range map[websocket.StatusCode]bool{1000: true, 1005: false, 1006: false, 1015: false, 1013: true, 4999: true, 5000: false, 2000: false} {
		if sendable(code) != want {
			t.Errorf("sendable(%d) = %v", code, !want)
		}
	}
	if got := hermesClose(errors.New("eof")); got.code != CodeHermesUnavailable {
		t.Errorf("plain error -> %d", got.code)
	}
	if got := dialClose(errors.New("other")); got.code != CodeHermesUnavailable || got.reason != "hermes_unavailable" {
		t.Errorf("unknown dial error -> %+v", got)
	}
}
