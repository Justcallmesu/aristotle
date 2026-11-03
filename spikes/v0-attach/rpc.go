package main

// JSON-RPC 2.0 over the gateway socket: one JSON object per WS text message
// (tui_gateway/ws.py:1-4). Client ids are ints; server→client request ids are "srq-…" strings.

import (
	"encoding/json"
	"fmt"
	"io"
	"os"
	"sync"
	"time"
)

type frame struct {
	JSONRPC string          `json:"jsonrpc"`
	ID      json.RawMessage `json:"id,omitempty"`
	Method  string          `json:"method,omitempty"`
	Params  json.RawMessage `json:"params,omitempty"`
	Result  json.RawMessage `json:"result,omitempty"`
	Error   *rpcError       `json:"error,omitempty"`
}

type rpcError struct {
	Code    int             `json:"code"`
	Message string          `json:"message"`
	Data    json.RawMessage `json:"data,omitempty"`
}

func (e *rpcError) Error() string {
	return fmt.Sprintf("rpc error %d %s %s", e.Code, e.Message, string(e.Data))
}

type eventParams struct {
	Type                string          `json:"type"`
	SessionID           string          `json:"session_id"`
	Seq                 int             `json:"seq"`
	ExecutionGeneration *int            `json:"execution_generation"`
	Payload             json.RawMessage `json:"payload"`
}

type client struct {
	label   string
	ws      *wsConn
	out     []io.Writer
	logMu   sync.Mutex
	mu      sync.Mutex
	nextID  int
	pending map[int]chan frame
	pingIDs map[int]bool
	events  chan frame
	done    chan error
}

// connect mints a fresh ticket (one per connection), dials, waits for gateway.ready and
// advertises server_requests so approval/clarify server requests are not withdrawn.
func connect(label, profileName, scope string, out ...io.Writer) (*client, *identity, error) {
	id, err := identify()
	if err != nil {
		return nil, nil, err
	}
	profile, err := id.profileFor(profileName)
	if err != nil {
		return nil, id, err
	}
	ticket, err := mintTicket(id, profile.ProfileID, scope)
	if err != nil {
		return nil, id, err
	}
	ws, _, err := dialGateway(id.APIOrigin, ticket)
	if err != nil {
		return nil, id, err
	}
	c := &client{label: label, ws: ws, out: out, pending: map[int]chan frame{}, pingIDs: map[int]bool{},
		events: make(chan frame, 4096), done: make(chan error, 1)}
	ready := make(chan struct{}, 1)
	go c.readLoop(ready)
	select {
	case <-ready:
	case err := <-c.done:
		return nil, id, fmt.Errorf("closed before gateway.ready: %w", err)
	case <-time.After(15 * time.Second):
		return nil, id, fmt.Errorf("no gateway.ready")
	}
	if _, err := c.call("client.capabilities", map[string]any{"server_requests": true}, 15*time.Second); err != nil {
		return nil, id, fmt.Errorf("client.capabilities: %w", err)
	}
	go c.heartbeat()
	return c, id, nil
}

func (c *client) log(direction string, raw []byte) {
	if len(c.out) == 0 {
		return
	}
	raw = redactFrame(raw)
	line, _ := json.Marshal(map[string]any{"ts": time.Now().UTC().Format(time.RFC3339Nano), "client": c.label,
		"dir": direction, "frame": json.RawMessage(raw)})
	c.logMu.Lock()
	defer c.logMu.Unlock()
	for _, w := range c.out {
		_, _ = w.Write(append(line, '\n'))
	}
}

func (c *client) readLoop(ready chan struct{}) {
	for {
		raw, err := c.ws.ReadMessage()
		if err != nil {
			c.done <- err
			close(c.events)
			return
		}
		var f frame
		if json.Unmarshal(raw, &f) != nil {
			c.log("in-unparsed", mustJSON(string(raw)))
			continue
		}
		var intID int
		isIntID := len(f.ID) > 0 && json.Unmarshal(f.ID, &intID) == nil
		c.mu.Lock()
		silent := isIntID && c.pingIDs[intID]
		delete(c.pingIDs, intID)
		c.mu.Unlock()
		if !silent {
			c.log("in", raw)
		}
		switch {
		case f.Method == "event":
			var ev eventParams
			_ = json.Unmarshal(f.Params, &ev)
			if ev.Type == "gateway.ready" {
				select {
				case ready <- struct{}{}:
				default:
				}
			}
			c.deliver(f)
		case f.Method != "": // server→client request
			c.deliver(f)
		case isIntID:
			c.mu.Lock()
			ch := c.pending[intID]
			delete(c.pending, intID)
			c.mu.Unlock()
			if ch != nil {
				ch <- f
			}
		}
	}
}

func (c *client) deliver(f frame) {
	select {
	case c.events <- f:
	default: // never block the reader on a slow consumer
	}
}

func (c *client) send(method string, params any, silent bool) (int, chan frame, error) {
	c.mu.Lock()
	c.nextID++
	id := c.nextID
	ch := make(chan frame, 1)
	c.pending[id] = ch
	if silent {
		c.pingIDs[id] = true
	}
	c.mu.Unlock()
	raw, _ := json.Marshal(map[string]any{"jsonrpc": "2.0", "id": id, "method": method, "params": params})
	if !silent {
		c.log("out", raw)
	}
	return id, ch, c.ws.WriteText(raw)
}

func (c *client) call(method string, params any, timeout time.Duration) (json.RawMessage, error) {
	_, ch, err := c.send(method, params, false)
	if err != nil {
		return nil, err
	}
	select {
	case f := <-ch:
		if f.Error != nil {
			return nil, f.Error
		}
		return f.Result, nil
	case <-time.After(timeout):
		return nil, fmt.Errorf("%s: timeout", method)
	}
}

// respond answers a server→client request (JSON-RPC response frame, same string id).
func (c *client) respond(id json.RawMessage, result any) error {
	raw, _ := json.Marshal(map[string]any{"jsonrpc": "2.0", "id": id, "result": result})
	c.log("out", raw)
	return c.ws.WriteText(raw)
}

// heartbeat mirrors the Desktop's 15 s gateway.ping (answered inline by ws.py:695-699).
func (c *client) heartbeat() {
	ticker := time.NewTicker(15 * time.Second)
	defer ticker.Stop()
	for range ticker.C {
		if _, _, err := c.send("gateway.ping", map[string]any{}, true); err != nil {
			return
		}
	}
}

func (c *client) close() { c.ws.Close() }

func mustJSON(v any) []byte {
	raw, _ := json.Marshal(v)
	return raw
}

// redactFrame drops values that carry the user's memory/profile text: session.info and resume
// snapshots include the full system_prompt (memories, SOUL, skills), which is not session content.
func redactFrame(raw []byte) []byte {
	var v any
	if json.Unmarshal(raw, &v) != nil {
		return raw
	}
	out, _ := json.Marshal(redactValue(v))
	return out
}

func redactValue(v any) any {
	switch t := v.(type) {
	case map[string]any:
		for k, x := range t {
			if s, ok := x.(string); ok && k == "system_prompt" {
				t[k] = fmt.Sprintf("<redacted system_prompt %d chars>", len(s))
				continue
			}
			t[k] = redactValue(x)
		}
	case []any:
		for i, x := range t {
			t[i] = redactValue(x)
		}
	}
	return v
}

func openCapture(path string) (io.Writer, func(), error) {
	if path == "" {
		return io.Discard, func() {}, nil
	}
	f, err := os.OpenFile(path, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o600)
	if err != nil {
		return nil, nil, err
	}
	return f, func() { f.Close() }, nil
}
