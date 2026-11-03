package httpapi

import (
	"context"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"

	"github.com/justcallmesu/aristotle/server/internal/devices"
	"github.com/justcallmesu/aristotle/server/internal/pairing"
)

// fakeRelay accepts the socket and holds it open until release is closed.
type fakeRelay struct {
	mu       sync.Mutex
	calls    []string // "deviceID listener"
	release  chan struct{}
	accepted chan struct{}
}

func (f *fakeRelay) ServeRelay(w http.ResponseWriter, r *http.Request, deviceID, listener string) {
	f.mu.Lock()
	f.calls = append(f.calls, deviceID+" "+listener)
	f.mu.Unlock()
	if f.release == nil {
		return
	}
	c, err := websocket.Accept(w, r, nil)
	if err != nil {
		return
	}
	defer c.CloseNow()
	close(f.accepted)
	<-f.release
}

func relayRemote(t *testing.T, relay RelayServer) (*remote, *lockedLog) {
	t.Helper()
	store, err := devices.Open(t.TempDir(), nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	logs := &lockedLog{}
	codes := &pairing.Codes{}
	h := NewRemoteRouter("t", slog.New(slog.NewTextHandler(logs, nil)), Deps{Codes: codes, Devices: store, Relay: relay, Listener: "lan"})
	return &remote{h: h, codes: codes, store: store}, logs
}

type lockedLog struct {
	mu sync.Mutex
	b  strings.Builder
}

func (l *lockedLog) Write(p []byte) (int, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.b.Write(p)
}
func (l *lockedLog) String() string { l.mu.Lock(); defer l.mu.Unlock(); return l.b.String() }

func TestRelayRouteNeedsDeviceToken(t *testing.T) {
	fr := &fakeRelay{}
	rm, _ := relayRemote(t, fr)
	dev, token, _ := rm.store.Add("phone")
	if rec := rm.do(http.MethodGet, "/v1/ws", "", ""); rec.Code != http.StatusUnauthorized {
		t.Fatalf("no token: %d", rec.Code)
	}
	if rec := rm.do(http.MethodGet, "/v1/ws", "", "wrong"); rec.Code != http.StatusUnauthorized {
		t.Fatalf("bad token: %d", rec.Code)
	}
	rm.do(http.MethodGet, "/v1/ws", "", token)
	fr.mu.Lock()
	defer fr.mu.Unlock()
	if len(fr.calls) != 1 || fr.calls[0] != dev.ID+" lan" {
		t.Fatalf("relay calls = %v, want one for %s on lan", fr.calls, dev.ID)
	}
}

func TestRelayRouteAbsentWithoutRelay(t *testing.T) {
	rm, _ := relayRemote(t, nil)
	_, token, _ := rm.store.Add("phone")
	if rec := rm.do(http.MethodGet, "/v1/ws", "", token); rec.Code != http.StatusNotFound {
		t.Fatalf("no relay configured: %d, want 404", rec.Code)
	}
}

// D4: a WebSocket's request line is written at upgrade time, not when the socket ends.
func TestUpgradeLoggedWhileSocketOpen(t *testing.T) {
	fr := &fakeRelay{release: make(chan struct{}), accepted: make(chan struct{})}
	rm, logs := relayRemote(t, fr)
	_, token, _ := rm.store.Add("phone")
	srv := httptest.NewServer(rm.h)
	defer srv.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	c, _, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(srv.URL, "http")+"/v1/ws",
		&websocket.DialOptions{HTTPHeader: http.Header{"Authorization": {"Bearer " + token}}})
	if err != nil {
		t.Fatal(err)
	}
	defer c.CloseNow()
	<-fr.accepted
	seen := false
	for deadline := time.Now().Add(2 * time.Second); !seen && time.Now().Before(deadline); time.Sleep(10 * time.Millisecond) {
		seen = strings.Contains(logs.String(), `route="GET /v1/ws" status=101`)
	}
	close(fr.release)
	if !seen {
		t.Fatalf("no request line while the socket is open:\n%s", logs.String())
	}
	c.CloseNow()
	time.Sleep(100 * time.Millisecond) // the handler returns; it must not log a second line
	if n := strings.Count(logs.String(), "msg=request"); n != 1 {
		t.Fatalf("%d request lines, want 1:\n%s", n, logs.String())
	}
}
