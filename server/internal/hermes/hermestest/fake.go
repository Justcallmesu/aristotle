// Package hermestest is a fake Hermes gateway for tests: the control socket in a temp home plus /api/ws and
// REST routes on an httptest server. It is a test helper; product code never imports it.
package hermestest

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
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

// Wire names, duplicated from package hermes (whose in-package tests import this package, so it cannot
// import hermes back). Facts: docs/protocol.md §1.1, §1.2, §8.
const (
	controlSocketName  = "gateway.sock"
	gatewaySubprotocol = "hermes-gateway-v1"
	ticketSubprotocol  = "hermes-gateway-ticket."
	ticketHTTPHeader   = "X-Hermes-Gateway-Ticket"
)

// Fake speaks the control protocol on a unix socket in a temp home and serves /api/ws and
// /api/echo from an httptest server. It enforces what the real gateway enforces: single-use
// tickets, exact params keys, purpose, subprotocols and no Origin header.
//
// Set the behaviour fields before dialling; read the recorded fields holding Mu.
type Fake struct {
	Home string       // the fake Hermes home; point hermes.Options.Home here
	Ln   net.Listener // the control socket listener

	Mu            sync.Mutex
	srv           *httptest.Server
	instanceID    string
	runtime       int
	generation    int
	tickets       map[string]string // ticket -> purpose, removed on redeem
	Minted        []MintRequest
	RedeemRefused int
	OriginSeen    bool
	FirstFrames   []string             // every frame the client sent on /api/ws, in order
	RestHeaders   []string             // the ticket header of each accepted REST request
	RestSeen      []http.Header        // all headers of each accepted REST request
	RestBodies    [][]byte             // the body of each accepted REST request
	RestLengths   []int64              // the declared Content-Length of each accepted REST request (-1 = none)
	PreAck        int                  // frames sent before the ack (default 1: gateway.ready)
	CloseCode     websocket.StatusCode // close instead of acking (during Dial)
	CloseAfterAck websocket.StatusCode // close right after the ack (an established Conn)
	NoAck         bool
	ReadLimit     int64 // /api/ws read limit; 0 = the library default (32 KiB). Real Hermes allows 384 MiB.
}

// MintRequest is one ticket the fake minted.
type MintRequest struct{ Purpose, Scope, ProfileID string }

// New starts a fake Hermes; everything is torn down by t.Cleanup.
func New(t testing.TB) *Fake {
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
	f := &Fake{Home: home, Ln: ln, runtime: 1, tickets: map[string]string{}}
	f.startHTTP()
	go f.serveControl()
	t.Cleanup(func() { ln.Close(); f.srv.Close() })
	return f
}

// URL is the current api_origin.
func (f *Fake) URL() string {
	f.Mu.Lock()
	defer f.Mu.Unlock()
	return f.srv.URL
}

// Restart simulates a Hermes restart: new instance, new port, old tickets void.
func (f *Fake) Restart() {
	f.Mu.Lock()
	old := f.srv
	f.tickets = map[string]string{}
	f.Mu.Unlock()
	old.Close()
	f.startHTTP()
}

func (f *Fake) startHTTP() {
	mux := http.NewServeMux()
	mux.HandleFunc("/api/ws", f.handleWS)
	mux.HandleFunc("/api/echo", f.handleREST)
	mux.HandleFunc("/api/redirect", f.handleRedirect)
	srv := httptest.NewServer(mux)
	f.Mu.Lock()
	f.srv = srv
	f.generation++
	f.instanceID = fmt.Sprintf("instance-%d", f.generation)
	f.Mu.Unlock()
}

// SetRuntime changes the runtime_protocol identify and tickets report.
func (f *Fake) SetRuntime(v int) { f.Mu.Lock(); f.runtime = v; f.Mu.Unlock() }

func (f *Fake) serveControl() {
	for {
		conn, err := f.Ln.Accept()
		if err != nil {
			return
		}
		go f.handleControl(conn)
	}
}

func (f *Fake) handleControl(conn net.Conn) {
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
	f.Mu.Lock()
	defer f.Mu.Unlock()
	switch req.Verb {
	case "identify":
		f.reply(conn, map[string]any{"ok": true, "protocol": 1, "id": req.ID, "result": f.identityLocked()})
	case "session-ticket":
		f.replyTicketLocked(conn, req.ID, req.Params)
	default:
		f.reply(conn, map[string]any{"ok": false, "error": "unknown verb"})
	}
}

func (f *Fake) identityLocked() map[string]any {
	return map[string]any{
		"runtime_protocol": f.runtime, "state": "ready", "instance_id": f.instanceID,
		"api_origin": f.srv.URL, "hermes_home": f.Home,
		"capabilities": []string{"session-authority-v1"},
		"served_profiles": []map[string]string{
			{"profile_id": f.Home, "home": f.Home},
			{"profile_id": f.Home + "/profiles/pm", "home": f.Home + "/profiles/pm"},
		},
	}
}

func (f *Fake) replyTicketLocked(conn net.Conn, id int, params map[string]interface{}) {
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
	case (profileID != f.Home && profileID != f.Home+"/profiles/pm") || (purpose != "interactive" && purpose != "native-http") || (scope == "host" && purpose != "interactive"):
		deny("bootstrap binding rejected")
	default:
		ticket := fmt.Sprintf("tkt-%d-%d", f.generation, len(f.Minted)+1)
		f.tickets[ticket] = purpose
		f.Minted = append(f.Minted, MintRequest{purpose, scope, profileID})
		f.reply(conn, map[string]any{"ok": true, "id": id, "result": map[string]any{
			"ticket": ticket, "instance_id": f.instanceID, "profile_id": profileID, "runtime_protocol": f.runtime}})
	}
}

func (f *Fake) reply(conn net.Conn, v map[string]any) {
	b, _ := json.Marshal(v)
	_, _ = conn.Write(append(b, '\n'))
}

// redeem consumes a ticket once; a second redeem is refused like the real store.
func (f *Fake) redeem(ticket, purpose string) bool {
	f.Mu.Lock()
	defer f.Mu.Unlock()
	if f.tickets[ticket] != purpose || ticket == "" {
		f.RedeemRefused++
		return false
	}
	delete(f.tickets, ticket)
	return true
}

func (f *Fake) handleWS(w http.ResponseWriter, r *http.Request) {
	if r.Header.Get("Origin") != "" {
		f.Mu.Lock()
		f.OriginSeen = true
		f.Mu.Unlock()
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
	f.Mu.Lock()
	preAck, closeCode, noAck, closeAfterAck, readLimit := f.PreAck, f.CloseCode, f.NoAck, f.CloseAfterAck, f.ReadLimit
	f.Mu.Unlock()
	if readLimit != 0 {
		ws.SetReadLimit(readLimit)
	}
	if closeCode != 0 {
		_ = ws.Close(closeCode, "test close")
		return
	}
	if preAck == 0 {
		preAck = 1
	}
	for i := 0; i < preAck; i++ {
		frame := fmt.Sprintf(`{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","n":%d}}`, i)
		if ws.Write(ctx, websocket.MessageText, []byte(frame)) != nil {
			return
		}
	}
	for first := true; ; first = false {
		_, data, err := ws.Read(ctx)
		if err != nil {
			return
		}
		f.Mu.Lock()
		f.FirstFrames = append(f.FirstFrames, string(data))
		f.Mu.Unlock()
		reply := data
		if first && noAck {
			continue
		}
		if first {
			reply = []byte(`{"jsonrpc":"2.0","id":1,"result":{"declines_not_shown":true}}`)
		}
		if ws.Write(ctx, websocket.MessageText, reply) != nil {
			return
		}
		if first && closeAfterAck != 0 {
			_ = ws.Close(closeAfterAck, "test close")
			return
		}
	}
}

func (f *Fake) handleREST(w http.ResponseWriter, r *http.Request) {
	ticket := r.Header.Get(ticketHTTPHeader)
	if r.Header.Get("Origin") != "" || !f.redeem(ticket, "native-http") {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	body, _ := io.ReadAll(r.Body)
	f.Mu.Lock()
	f.RestHeaders = append(f.RestHeaders, ticket)
	f.RestSeen = append(f.RestSeen, r.Header.Clone())
	f.RestBodies = append(f.RestBodies, body)
	f.RestLengths = append(f.RestLengths, r.ContentLength)
	f.Mu.Unlock()
	_, _ = w.Write([]byte(r.URL.RequestURI()))
}

// handleRedirect redeems the ticket, then answers 307 so a following client would replay it.
func (f *Fake) handleRedirect(w http.ResponseWriter, r *http.Request) {
	if !f.redeem(r.Header.Get(ticketHTTPHeader), "native-http") {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	http.Redirect(w, r, "/api/echo", http.StatusTemporaryRedirect)
}
