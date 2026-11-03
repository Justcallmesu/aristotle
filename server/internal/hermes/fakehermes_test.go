package hermes

import (
	"bufio"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"

	"github.com/coder/websocket"
)

// fakeHermes speaks the control protocol on a unix socket in a temp home and serves /api/ws and
// /api/echo from an httptest server. It enforces what the real gateway enforces: single-use
// tickets, exact params keys, purpose, subprotocols and no Origin header.
type fakeHermes struct {
	t    *testing.T
	home string
	ln   net.Listener

	mu            sync.Mutex
	srv           *httptest.Server
	instanceID    string
	runtime       int
	generation    int
	tickets       map[string]string // ticket -> purpose, removed on redeem
	minted        []mintRequest
	redeemRefused int
	originSeen    bool
	firstFrames   []string
	restHeaders   []string
}

type mintRequest struct{ Purpose, Scope, ProfileID string }

func newFakeHermes(t *testing.T) *fakeHermes {
	t.Helper()
	home, err := os.MkdirTemp("", "fh")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { os.RemoveAll(home) })
	ln, err := net.Listen("unix", filepath.Join(home, controlSocketName))
	if err != nil {
		t.Fatal(err)
	}
	f := &fakeHermes{t: t, home: home, ln: ln, runtime: 1, tickets: map[string]string{}}
	f.startHTTP()
	go f.serveControl()
	t.Cleanup(func() { ln.Close(); f.srv.Close() })
	return f
}

func (f *fakeHermes) connector() *Connector {
	c, err := New(Options{Home: f.home})
	if err != nil {
		f.t.Fatal(err)
	}
	return c
}

// restart simulates a Hermes restart: new instance, new port, old tickets void.
func (f *fakeHermes) restart() {
	f.mu.Lock()
	old := f.srv
	f.tickets = map[string]string{}
	f.mu.Unlock()
	old.Close()
	f.startHTTP()
}

func (f *fakeHermes) startHTTP() {
	mux := http.NewServeMux()
	mux.HandleFunc("/api/ws", f.handleWS)
	mux.HandleFunc("/api/echo", f.handleREST)
	srv := httptest.NewServer(mux)
	f.mu.Lock()
	f.srv = srv
	f.generation++
	f.instanceID = fmt.Sprintf("instance-%d", f.generation)
	f.mu.Unlock()
}

func (f *fakeHermes) setRuntime(v int) { f.mu.Lock(); f.runtime = v; f.mu.Unlock() }

func (f *fakeHermes) serveControl() {
	for {
		conn, err := f.ln.Accept()
		if err != nil {
			return
		}
		go f.handleControl(conn)
	}
}

func (f *fakeHermes) handleControl(conn net.Conn) {
	defer conn.Close()
	line, err := bufio.NewReader(conn).ReadBytes('\n')
	if err != nil {
		return
	}
	var req struct {
		Protocol int                    `json:"protocol"`
		ID       int                    `json:"id"`
		Verb     string                 `json:"verb"`
		Params   map[string]interface{} `json:"params"`
	}
	if json.Unmarshal(line, &req) != nil || req.Protocol != 1 {
		f.reply(conn, map[string]any{"ok": false, "error": "PermissionError: invalid bootstrap envelope"})
		return
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	switch req.Verb {
	case "identify":
		f.reply(conn, map[string]any{"ok": true, "protocol": 1, "id": req.ID, "result": f.identityLocked()})
	case "session-ticket":
		f.replyTicketLocked(conn, req.ID, req.Params)
	default:
		f.reply(conn, map[string]any{"ok": false, "error": "unknown verb"})
	}
}

func (f *fakeHermes) identityLocked() map[string]any {
	return map[string]any{
		"runtime_protocol": f.runtime, "state": "ready", "instance_id": f.instanceID,
		"api_origin": f.srv.URL, "hermes_home": f.home,
		"capabilities": []string{"session-authority-v1"},
		"served_profiles": []map[string]string{
			{"profile_id": f.home, "home": f.home},
			{"profile_id": f.home + "/profiles/pm", "home": f.home + "/profiles/pm"},
		},
	}
}

func (f *fakeHermes) replyTicketLocked(conn net.Conn, id int, params map[string]interface{}) {
	deny := func(msg string) {
		f.reply(conn, map[string]any{"ok": false, "id": id, "error": "PermissionError: " + msg})
	}
	for key := range params {
		if key != "profile_id" && key != "instance_id" && key != "purpose" && key != "scope" {
			deny("invalid bootstrap parameters")
			return
		}
	}
	purpose, _ := params["purpose"].(string)
	scope, _ := params["scope"].(string)
	profileID, _ := params["profile_id"].(string)
	switch {
	case params["instance_id"] != f.instanceID:
		deny("stale runtime instance")
	case profileID != f.home || (purpose != "interactive" && purpose != "native-http") || (scope == "host" && purpose != "interactive"):
		deny("bootstrap binding rejected")
	default:
		ticket := fmt.Sprintf("tkt-%d-%d", f.generation, len(f.minted)+1)
		f.tickets[ticket] = purpose
		f.minted = append(f.minted, mintRequest{purpose, scope, profileID})
		f.reply(conn, map[string]any{"ok": true, "id": id, "result": map[string]any{
			"ticket": ticket, "instance_id": f.instanceID, "profile_id": profileID, "runtime_protocol": f.runtime}})
	}
}

func (f *fakeHermes) reply(conn net.Conn, v map[string]any) {
	b, _ := json.Marshal(v)
	_, _ = conn.Write(append(b, '\n'))
}

// redeem consumes a ticket once; a second redeem is refused like the real store.
func (f *fakeHermes) redeem(ticket, purpose string) bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.tickets[ticket] != purpose || ticket == "" {
		f.redeemRefused++
		return false
	}
	delete(f.tickets, ticket)
	return true
}

func (f *fakeHermes) handleWS(w http.ResponseWriter, r *http.Request) {
	if r.Header.Get("Origin") != "" {
		f.mu.Lock()
		f.originSeen = true
		f.mu.Unlock()
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	ticket := ""
	for _, p := range strings.Split(r.Header.Get("Sec-WebSocket-Protocol"), ",") {
		if p = strings.TrimSpace(p); strings.HasPrefix(p, ticketSubprotocol) {
			ticket = strings.TrimPrefix(p, ticketSubprotocol)
		}
	}
	if !strings.Contains(r.Header.Get("Sec-WebSocket-Protocol"), gatewaySubprotocol) || !f.redeem(ticket, "interactive") {
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	ws, err := websocket.Accept(w, r, &websocket.AcceptOptions{Subprotocols: []string{gatewaySubprotocol}})
	if err != nil {
		return
	}
	defer ws.CloseNow()
	ctx := r.Context()
	_ = ws.Write(ctx, websocket.MessageText, []byte(`{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready"}}`))
	for first := true; ; first = false {
		_, data, err := ws.Read(ctx)
		if err != nil {
			return
		}
		f.mu.Lock()
		f.firstFrames = append(f.firstFrames, string(data))
		f.mu.Unlock()
		reply := data
		if first {
			reply = []byte(`{"jsonrpc":"2.0","id":1,"result":{"declines_not_shown":true}}`)
		}
		if ws.Write(ctx, websocket.MessageText, reply) != nil {
			return
		}
	}
}

func (f *fakeHermes) handleREST(w http.ResponseWriter, r *http.Request) {
	ticket := r.Header.Get(ticketHTTPHeader)
	if r.Header.Get("Origin") != "" || !f.redeem(ticket, "native-http") {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	f.mu.Lock()
	f.restHeaders = append(f.restHeaders, ticket)
	f.mu.Unlock()
	_, _ = w.Write([]byte(r.URL.RequestURI()))
}
