package httpapi

import (
	"bufio"
	"bytes"
	"encoding/json"
	"fmt"
	appversion "github.com/justcallmesu/aristotle/server/internal/version"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/http/httptest"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/justcallmesu/aristotle/server/internal/devices"
	"github.com/justcallmesu/aristotle/server/internal/pairing"
)

type remote struct {
	h     http.Handler
	codes *pairing.Codes
	store *devices.Store
	logs  *bytes.Buffer
}

func newRemote(t *testing.T) *remote {
	t.Helper()
	store, err := devices.Open(t.TempDir(), nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	logs := &bytes.Buffer{}
	codes := &pairing.Codes{}
	h := NewRemoteRouter("t", slog.New(slog.NewTextHandler(logs, nil)), Deps{Codes: codes, Devices: store})
	return &remote{h: h, codes: codes, store: store, logs: logs}
}

func (rm *remote) do(method, path, body, token string) *httptest.ResponseRecorder {
	return rm.doFrom("192.168.1.20:40000", method, path, body, token)
}

func (rm *remote) doFrom(remoteAddr, method, path, body, token string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(method, path, strings.NewReader(body))
	req.RemoteAddr = remoteAddr
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	rec := httptest.NewRecorder()
	rm.h.ServeHTTP(rec, req)
	return rec
}

func (rm *remote) pair(t *testing.T, laptopName, phoneName string) pairResponse {
	t.Helper()
	iss, err := rm.codes.Start(laptopName)
	if err != nil {
		t.Fatal(err)
	}
	body, _ := json.Marshal(pairRequest{Code: iss.Code, DeviceName: phoneName})
	rec := rm.do(http.MethodPost, "/v1/pair", string(body), "")
	if rec.Code != http.StatusCreated {
		t.Fatalf("pair = %d %s", rec.Code, rec.Body.String())
	}
	var resp pairResponse
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil || resp.Token == "" || resp.DeviceID == "" {
		t.Fatalf("pair body %s (%v)", rec.Body.String(), err)
	}
	return resp
}

func TestPairThenMe(t *testing.T) {
	rm := newRemote(t)
	p := rm.pair(t, "", "Poco X5")
	rec := rm.do(http.MethodGet, "/v1/me", "", p.Token)
	if rec.Code != http.StatusOK {
		t.Fatalf("me = %d", rec.Code)
	}
	var me meResponse
	_ = json.Unmarshal(rec.Body.Bytes(), &me)
	if me.DeviceID != p.DeviceID || me.Name != "Poco X5" {
		t.Fatalf("me = %+v", me)
	}
}

// /v1/me carries the server block with exactly these keys (docs/protocol.md §11.2).
func TestMeServerBlock(t *testing.T) {
	rm := newRemote(t)
	p := rm.pair(t, "", "Poco X5")
	var raw map[string]any
	if err := json.Unmarshal(rm.do(http.MethodGet, "/v1/me", "", p.Token).Body.Bytes(), &raw); err != nil {
		t.Fatal(err)
	}
	srv, _ := raw["server"].(map[string]any)
	if len(srv) != 3 || srv["version"] != "t" || srv["protocol"] != float64(appversion.ProtocolVersion) ||
		srv["min_phone_protocol"] != float64(appversion.MinPhoneProtocol) {
		t.Fatalf("server = %v", srv)
	}
}

// The pair response and GET /v1/addresses carry the listener list (an array, never null); the list needs
// a device token.
func TestPairAndAddressesListTheListeners(t *testing.T) {
	rm := newRemote(t)
	if body := rm.pair(t, "", "").Addresses; body == nil || len(body) != 0 {
		t.Fatalf("pair addresses without a provider = %#v, want []", body)
	}

	list := []Address{
		{URL: "https://192.168.1.61:8760", Kind: "lan", Iface: "wlan0"},
		{URL: "https://100.74.61.96:8760", Kind: "tailscale", Iface: "tailscale0"},
	}
	rm.h = NewRemoteRouter("t", slog.New(slog.NewTextHandler(rm.logs, nil)),
		Deps{Codes: rm.codes, Devices: rm.store, Addresses: func() []Address { return list }})
	p := rm.pair(t, "", "")
	if !slices.Equal(p.Addresses, list) {
		t.Fatalf("pair addresses %v, want %v", p.Addresses, list)
	}
	if rec := rm.do(http.MethodGet, "/v1/addresses", "", ""); rec.Code != http.StatusUnauthorized {
		t.Fatalf("addresses without a token = %d", rec.Code)
	}
	rec := rm.do(http.MethodGet, "/v1/addresses", "", p.Token)
	var got addressesResponse
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil || rec.Code != http.StatusOK {
		t.Fatalf("addresses = %d %s", rec.Code, rec.Body.String())
	}
	if !slices.Equal(got.Addresses, list) || rec.Header().Get("Cache-Control") != "no-store" {
		t.Fatalf("addresses = %v (%q)", got.Addresses, rec.Header().Get("Cache-Control"))
	}
	if rm.do(http.MethodPost, "/v1/addresses", "", p.Token).Code != http.StatusNotFound {
		t.Fatal("POST /v1/addresses accepted")
	}
}

func TestPairNamePrecedence(t *testing.T) {
	tests := []struct{ laptop, phone, want string }{
		{"laptop", "phone", "laptop"},
		{"", "phone", "phone"},
		{"", "", "device"},
	}
	for _, tt := range tests {
		rm := newRemote(t)
		p := rm.pair(t, tt.laptop, tt.phone)
		var me meResponse
		_ = json.Unmarshal(rm.do(http.MethodGet, "/v1/me", "", p.Token).Body.Bytes(), &me)
		if me.Name != tt.want {
			t.Errorf("laptop=%q phone=%q: name %q, want %q", tt.laptop, tt.phone, me.Name, tt.want)
		}
	}
}

func TestPairRejects(t *testing.T) {
	rm := newRemote(t)
	iss, _ := rm.codes.Start("")
	tests := []struct {
		name, path, body string
		want             int
	}{
		{"code in query only", "/v1/pair?code=" + iss.Code, ``, http.StatusBadRequest},
		{"missing code", "/v1/pair", `{"device_name":"x"}`, http.StatusBadRequest},
		{"unknown field", "/v1/pair", `{"code":"` + iss.Code + `","token":"x"}`, http.StatusBadRequest},
		{"name too long", "/v1/pair", `{"code":"` + iss.Code + `","device_name":"` + strings.Repeat("x", 65) + `"}`, http.StatusBadRequest},
		{"huge body", "/v1/pair", `{"code":"` + strings.Repeat("A", 5000) + `"}`, http.StatusBadRequest},
		{"wrong code", "/v1/pair", `{"code":"00000000"}`, http.StatusUnauthorized},
	}
	for _, tt := range tests {
		if rec := rm.do(http.MethodPost, tt.path, tt.body, ""); rec.Code != tt.want {
			t.Errorf("%s: %d, want %d (%s)", tt.name, rec.Code, tt.want, rec.Body.String())
		}
	}
	// Only "wrong code" counted as an attempt; the real code still works once.
	if rec := rm.do(http.MethodPost, "/v1/pair", `{"code":"`+iss.Code+`"}`, ""); rec.Code != http.StatusCreated {
		t.Fatalf("valid code after bad requests = %d", rec.Code)
	}
	if rec := rm.do(http.MethodPost, "/v1/pair", `{"code":"`+iss.Code+`"}`, ""); rec.Code != http.StatusUnauthorized {
		t.Fatalf("reused code = %d", rec.Code)
	}
}

func TestAuth(t *testing.T) {
	rm := newRemote(t)
	p := rm.pair(t, "", "p")
	tests := []struct {
		name, method, path string
		header             string
		want               int
	}{
		{"no header", "GET", "/v1/me", "", http.StatusUnauthorized},
		{"bad token", "GET", "/v1/me", "Bearer nope", http.StatusUnauthorized},
		{"wrong scheme", "GET", "/v1/me", "Basic " + p.Token, http.StatusUnauthorized},
		{"bare token", "GET", "/v1/me", p.Token, http.StatusUnauthorized},
		{"lower-case scheme", "GET", "/v1/me", "bearer " + p.Token, http.StatusOK},
		{"unknown v1 route, no token", "GET", "/v1/sessions", "", http.StatusUnauthorized},
		{"unknown v1 route, token", "GET", "/v1/sessions", "Bearer " + p.Token, http.StatusNotFound},
		{"GET pair needs token", "GET", "/v1/pair", "", http.StatusUnauthorized},
		{"health is open", "GET", "/healthz", "", http.StatusOK},
	}
	for _, tt := range tests {
		req := httptest.NewRequest(tt.method, tt.path, nil)
		if tt.header != "" {
			req.Header.Set("Authorization", tt.header)
		}
		rec := httptest.NewRecorder()
		rm.h.ServeHTTP(rec, req)
		if rec.Code != tt.want {
			t.Errorf("%s: %d, want %d", tt.name, rec.Code, tt.want)
		}
		if rec.Code == http.StatusUnauthorized && rec.Header().Get("WWW-Authenticate") != "Bearer" {
			t.Errorf("%s: missing WWW-Authenticate", tt.name)
		}
	}
}

func TestRevokeTakesEffectOnNextRequest(t *testing.T) {
	rm := newRemote(t)
	p := rm.pair(t, "", "p")
	other := rm.pair(t, "", "q")
	if rec := rm.do("GET", "/v1/me", "", p.Token); rec.Code != http.StatusOK {
		t.Fatalf("before revoke = %d", rec.Code)
	}
	if _, err := rm.store.Revoke(p.DeviceID); err != nil {
		t.Fatal(err)
	}
	if rec := rm.do("GET", "/v1/me", "", p.Token); rec.Code != http.StatusUnauthorized {
		t.Fatalf("after revoke = %d", rec.Code)
	}
	if rec := rm.do("GET", "/v1/me", "", other.Token); rec.Code != http.StatusOK {
		t.Fatalf("other device after revoke = %d", rec.Code)
	}
}

func TestLogsNeverHoldSecrets(t *testing.T) {
	rm := newRemote(t)
	iss, _ := rm.codes.Start("")
	rm.do("POST", "/v1/pair", `{"code":"WRONG000"}`, "")
	rec := rm.do("POST", "/v1/pair", `{"code":"`+iss.Code+`","device_name":"p"}`, "")
	var p pairResponse
	_ = json.Unmarshal(rec.Body.Bytes(), &p)
	rm.do("GET", "/v1/me", "", p.Token)
	rm.do("GET", "/v1/me", "", "bad-token-value")
	rm.do("GET", "/v1/pair/"+iss.Code+"?token="+p.Token, "", p.Token)
	logs := rm.logs.String()
	for _, secret := range []string{iss.Code, p.Token, "bad-token-value", "WRONG000", "Bearer", "Authorization"} {
		if strings.Contains(logs, secret) {
			t.Fatalf("log contains %q:\n%s", secret, logs)
		}
	}
	if !strings.Contains(logs, "device paired") || !strings.Contains(logs, `route="GET /v1/me"`) {
		t.Fatalf("expected log lines missing:\n%s", logs)
	}
}

func TestUnmatchedRouteLogsConstant(t *testing.T) {
	var buf bytes.Buffer
	h := NewRouter("x", slog.New(slog.NewTextHandler(&buf, nil)))
	h.ServeHTTP(httptest.NewRecorder(), httptest.NewRequest(http.MethodGet, "/pair/SECRET123?tok=abc", nil))
	if strings.Contains(buf.String(), "SECRET123") || !strings.Contains(buf.String(), "route=unmatched") {
		t.Fatalf("log = %s", buf.String())
	}
	if !strings.Contains(buf.String(), "status=404") {
		t.Fatalf("status not logged: %s", buf.String())
	}
}

func TestLoopbackRouterHasNoV1(t *testing.T) {
	h := NewRouter("x", slog.New(slog.NewTextHandler(io.Discard, nil)))
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(http.MethodPost, "/v1/pair", strings.NewReader(`{"code":"x"}`)))
	if rec.Code != http.StatusNotFound {
		t.Fatalf("loopback /v1/pair = %d, want 404", rec.Code)
	}
}

func TestPairNameStripped(t *testing.T) {
	tests := []struct{ in, want string }{
		{"Poco\u202eX5", "PocoX5"},                                          // right-to-left override
		{"\u2066evil\u2069 phone", "evil phone"},                            // bidi isolates
		{"a\u0007b\tc\nd", "abcd"},                                          // C0 controls
		{"zero\u200bwidth\u200d", "zerowidth"},                              // Cf zero-width
		{"line\u2028sep\u2029", "linesep"},                                  // Zl, Zp
		{"  \u202e  ", "device"},                                            // nothing left: default name
		{strings.Repeat("x", 64) + "\u202e\u202e", strings.Repeat("x", 64)}, // stripped before the cap
	}
	for _, tt := range tests {
		rm := newRemote(t)
		p := rm.pair(t, "", tt.in)
		var me meResponse
		_ = json.Unmarshal(rm.do(http.MethodGet, "/v1/me", "", p.Token).Body.Bytes(), &me)
		if me.Name != tt.want {
			t.Errorf("device_name %q stored as %q, want %q", tt.in, me.Name, tt.want)
		}
	}
	if _, err := CleanName(strings.Repeat("é", 65)); err == nil {
		t.Error("65 characters accepted")
	}
}

func TestPairLockoutIsPerPeer(t *testing.T) {
	rm := newRemote(t)
	iss, _ := rm.codes.Start("")
	for range pairing.MaxAttemptsPerPeer {
		if rec := rm.doFrom("192.168.1.66:5000", "POST", "/v1/pair", `{"code":"00000000"}`, ""); rec.Code != http.StatusUnauthorized {
			t.Fatalf("wrong code = %d", rec.Code)
		}
	}
	if rec := rm.doFrom("192.168.1.66:5001", "POST", "/v1/pair", `{"code":"`+iss.Code+`"}`, ""); rec.Code != http.StatusUnauthorized {
		t.Fatalf("locked-out peer with the right code = %d, want 401", rec.Code)
	}
	if rec := rm.doFrom("[::ffff:192.168.1.20]:6000", "POST", "/v1/pair", `{"code":"`+iss.Code+`"}`, ""); rec.Code != http.StatusCreated {
		t.Fatalf("phone after another peer's lockout = %d, want 201", rec.Code)
	}
}

func TestPairGlobalCapAndLogThrottle(t *testing.T) {
	rm := newRemote(t)
	iss, _ := rm.codes.Start("")
	for i := range pairing.MaxAttemptsTotal + 10 {
		remote := fmt.Sprintf("10.0.%d.%d:5000", i/200, i%200)
		rec := rm.doFrom(remote, "POST", "/v1/pair", `{"code":"00000000"}`, "")
		if rec.Code != http.StatusUnauthorized || !strings.Contains(rec.Body.String(), pairing.ErrInvalid.Error()) ||
			strings.Contains(rec.Body.String(), "invalidated") {
			t.Fatalf("attempt %d = %d %s", i, rec.Code, rec.Body.String())
		}
	}
	if rec := rm.do("POST", "/v1/pair", `{"code":"`+iss.Code+`"}`, ""); rec.Code != http.StatusUnauthorized {
		t.Fatalf("code alive after %d wrong attempts: %d", pairing.MaxAttemptsTotal, rec.Code)
	}
	logs := rm.logs.String()
	if n := strings.Count(logs, "pairing attempts rejected"); n != 1 {
		t.Fatalf("%d rejection lines for 31 rejections in under a minute:\n%s", n, logs)
	}
	if n := strings.Count(logs, "pairing code invalidated"); n != 1 {
		t.Fatalf("invalidation logged %d times", n)
	}
}

func TestRejectLogOneLinePerInterval(t *testing.T) {
	var buf bytes.Buffer
	now := time.Date(2026, 11, 3, 10, 0, 0, 0, time.UTC)
	l := NewRejectLog(slog.New(slog.NewTextHandler(&buf, nil)))
	l.now = func() time.Time { return now }
	for range 50 {
		l.note()
		now = now.Add(time.Second) // 50 s: still inside the first minute
	}
	now = now.Add(11 * time.Second)
	l.note()
	lines := strings.Split(strings.TrimSpace(buf.String()), "\n")
	if len(lines) != 2 || !strings.Contains(lines[0], "count=1") || !strings.Contains(lines[1], "count=50") {
		t.Fatalf("lines = %q", lines)
	}
}

// A peer that trickles the /v1/pair body is cut off by the per-request read
// deadline, while the server itself has no ReadTimeout (WebSockets, T06).
func TestPairSlowBodyCutOff(t *testing.T) {
	old := pairReadTimeout
	pairReadTimeout = 200 * time.Millisecond
	t.Cleanup(func() { pairReadTimeout = old })
	rm := newRemote(t)
	srv := httptest.NewServer(rm.h)
	t.Cleanup(srv.Close)

	conn, err := net.Dial("tcp", srv.Listener.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	fmt.Fprintf(conn, "POST /v1/pair HTTP/1.1\r\nHost: x\r\nContent-Type: application/json\r\nContent-Length: 100\r\n\r\n{\"code\":")
	start := time.Now()
	_ = conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	resp, err := http.ReadResponse(bufio.NewReader(conn), nil)
	if err != nil {
		t.Fatalf("no response to a stalled body: %v", err)
	}
	resp.Body.Close()
	if took := time.Since(start); took > 3*time.Second || resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("stalled body: status %d after %v", resp.StatusCode, took)
	}

	// A normal pairing over the same real server still works.
	iss, _ := rm.codes.Start("")
	res, err := http.Post(srv.URL+"/v1/pair", "application/json", strings.NewReader(`{"code":"`+iss.Code+`"}`))
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	if res.StatusCode != http.StatusCreated {
		t.Fatalf("normal pair over a real server = %d", res.StatusCode)
	}
}

// Every listener's router is built with the same RejectLog, so three listeners log one line, not three.
func TestSharedRejectLogLimitsAcrossRouters(t *testing.T) {
	var buf bytes.Buffer
	log := slog.New(slog.NewTextHandler(&buf, nil))
	store, err := devices.Open(t.TempDir(), nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	deps := Deps{Codes: &pairing.Codes{}, Devices: store, Rejects: NewRejectLog(log)}
	for range 3 {
		h := NewRemoteRouter("t", log, deps)
		req := httptest.NewRequest(http.MethodPost, "/v1/pair", strings.NewReader(`{"code":"AAAAAAAA"}`))
		req.RemoteAddr = "192.168.1.20:40000"
		h.ServeHTTP(httptest.NewRecorder(), req)
	}
	if n := strings.Count(buf.String(), "pairing attempts rejected"); n != 1 {
		t.Fatalf("%d reject lines from 3 routers, want 1:\n%s", n, buf.String())
	}
}
