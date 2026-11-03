package hermes

import (
	"context"
	"encoding/json"
	"fmt"
	"net/url"
	"strings"
	"sync"

	"github.com/coder/websocket"
)

// Conn is an attached /api/ws socket. Frames are raw JSON-RPC text messages, passed through untouched.
// Read and Write may each be used from one goroutine at a time.
type Conn struct {
	ws      *websocket.Conn
	mu      sync.Mutex
	pending [][]byte // frames that arrived before the capabilities ack (e.g. gateway.ready)
}

// Dial re-identifies Hermes, mints a fresh host-scoped interactive ticket, opens /api/ws with no
// Origin header, sends client.capabilities and waits for its ack. Frames Hermes sent before the
// ack are returned by the first Reads, so the relay still sees gateway.ready.
func (c *Connector) Dial(ctx context.Context) (*Conn, error) {
	id, err := c.Identify(ctx)
	if err != nil {
		return nil, err
	}
	ticket, err := c.mintTicket(ctx, id, purposeInteractive, scopeHost)
	if err != nil {
		return nil, err
	}
	ws, err := c.upgrade(ctx, id.APIOrigin, ticket)
	if err != nil {
		return nil, err
	}
	conn := &Conn{ws: ws}
	if err := conn.handshake(ctx); err != nil {
		_ = ws.CloseNow()
		return nil, err
	}
	c.log.Info("hermes socket attached", "api_origin", id.APIOrigin)
	return conn, nil
}

func (c *Connector) upgrade(ctx context.Context, apiOrigin, ticket string) (*websocket.Conn, error) {
	u, err := url.Parse(apiOrigin)
	if err != nil {
		return nil, err
	}
	u.Scheme = "ws"
	u.Path = wsPath
	ws, resp, err := websocket.Dial(ctx, u.String(), &websocket.DialOptions{
		HTTPClient:      c.client,
		Subprotocols:    []string{gatewaySubprotocol, ticketSubprotocol + ticket},
		CompressionMode: websocket.CompressionDisabled,
	})
	if err != nil {
		if resp != nil {
			return nil, fmt.Errorf("%w: HTTP %d", ErrUpgradeRefused, resp.StatusCode)
		}
		return nil, fmt.Errorf("dial hermes websocket: %s", scrub(err, ticket))
	}
	if ws.Subprotocol() != gatewaySubprotocol {
		_ = ws.CloseNow()
		return nil, fmt.Errorf("hermes chose subprotocol %q, want %q", ws.Subprotocol(), gatewaySubprotocol)
	}
	ws.SetReadLimit(maxFrameBytes)
	return ws, nil
}

// scrub keeps a ticket out of an error string.
func scrub(err error, ticket string) string {
	return strings.ReplaceAll(err.Error(), ticket, "<ticket>")
}

// handshake sends client.capabilities first and reads until its ack (JSON-RPC id 1).
func (c *Conn) handshake(ctx context.Context) error {
	if err := c.Write(ctx, []byte(capabilitiesRequest)); err != nil {
		return fmt.Errorf("send client.capabilities: %w", err)
	}
	for {
		frame, err := c.readWS(ctx)
		if err != nil {
			return fmt.Errorf("wait for client.capabilities ack: %w", err)
		}
		var head struct {
			ID    json.RawMessage `json:"id"`
			Error json.RawMessage `json:"error"`
		}
		if json.Unmarshal(frame, &head) == nil && string(head.ID) == "1" {
			if len(head.Error) > 0 {
				return fmt.Errorf("client.capabilities refused: %s", head.Error)
			}
			return nil
		}
		c.pending = append(c.pending, frame)
	}
}

// Read returns the next frame.
func (c *Conn) Read(ctx context.Context) ([]byte, error) {
	if len(c.pending) > 0 {
		frame := c.pending[0]
		c.pending = c.pending[1:]
		return frame, nil
	}
	return c.readWS(ctx)
}

func (c *Conn) readWS(ctx context.Context) ([]byte, error) {
	_, data, err := c.ws.Read(ctx)
	return data, err
}

// Write sends one text frame.
func (c *Conn) Write(ctx context.Context, frame []byte) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.ws.Write(ctx, websocket.MessageText, frame)
}

// Close ends the socket; Hermes detaches the connection's sessions.
func (c *Conn) Close() error {
	return c.ws.Close(websocket.StatusNormalClosure, "")
}
