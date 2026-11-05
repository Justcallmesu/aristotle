package hermes

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"sync"

	"github.com/coder/websocket"
)

// Conn is an attached /api/ws socket. Frames are raw JSON-RPC text messages, passed through untouched.
// Read and Write may each be used from one goroutine at a time.
//
// Cancelling the context passed to Read or Write closes the whole connection (coder/websocket
// behaviour), so do not use per-call timeouts: a timed-out Read makes the next Write fail.
// Use one long-lived context and cancel it only to tear the connection down.
type Conn struct {
	ws           *websocket.Conn
	mu           sync.Mutex
	pending      [][]byte // frames that arrived before the capabilities ack (e.g. gateway.ready)
	pendingBytes int
}

// Dial re-identifies Hermes, mints a fresh host-scoped interactive ticket, opens /api/ws with no
// Origin header, sends client.capabilities and waits for its ack. Frames Hermes sent before the
// ack are returned by the first Reads, so the relay still sees gateway.ready.
func (c *Connector) Dial(ctx context.Context) (*Conn, error) {
	return c.dial(ctx, Identity.launchProfileID, scopeHost)
}

// DialProfile is Dial on a socket bound to one served profile (scope "profile", docs/protocol.md §3):
// calls naming another profile are refused profile_mismatch, so the socket cannot reach other chats.
// ErrUnknownProfile when Hermes does not serve the profile.
func (c *Connector) DialProfile(ctx context.Context, profile string) (*Conn, error) {
	return c.dial(ctx, func(id Identity) (string, error) { return id.resolveProfile(profile) }, scopeProfile)
}

func (c *Connector) dial(ctx context.Context, pick func(Identity) (string, error), scope string) (*Conn, error) {
	id, err := c.Identify(ctx)
	if err != nil {
		return nil, err
	}
	profileID, err := pick(id)
	if err != nil {
		return nil, err
	}
	ticket, err := c.mintTicket(ctx, id, profileID, purposeInteractive, scope)
	if err != nil {
		return nil, err
	}
	ws, err := c.upgrade(ctx, id.APIOrigin, ticket)
	if err != nil {
		return nil, err
	}
	conn := &Conn{ws: ws}
	hctx, cancel := context.WithTimeout(ctx, c.ackTimeout)
	defer cancel()
	if err := conn.handshake(hctx); err != nil {
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
			if resp.StatusCode == http.StatusSwitchingProtocols {
				return nil, fmt.Errorf("hermes upgrade response failed verification: %s", scrub(err, ticket))
			}
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
		c.pendingBytes += len(frame)
		if len(c.pending) >= maxPendingFrames || c.pendingBytes > maxPendingBytes {
			return ErrPendingOverflow
		}
		c.pending = append(c.pending, frame)
	}
}

// Read returns the next frame. Cancelling ctx closes the connection. Close codes from Hermes
// surface as ErrDraining / ErrForbidden (errors.Is) or via CloseCode.
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
	return data, mapClose(err)
}

// Write sends one text frame. Cancelling ctx closes the connection.
func (c *Conn) Write(ctx context.Context, frame []byte) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	return mapClose(c.ws.Write(ctx, websocket.MessageText, frame))
}

// CloseError is a close frame received from Hermes. It matches ErrDraining (1013) and ErrForbidden (4403)
// with errors.Is.
type CloseError struct {
	Code int
	err  error
}

func (e *CloseError) Error() string { return e.err.Error() }
func (e *CloseError) Unwrap() error { return e.err }

// Is maps the codes Hermes uses to the package sentinels.
func (e *CloseError) Is(target error) bool {
	return (target == ErrDraining && e.Code == 1013) || (target == ErrForbidden && e.Code == 4403)
}

func mapClose(err error) error {
	if err == nil {
		return nil
	}
	if code := websocket.CloseStatus(err); code != -1 {
		return &CloseError{Code: int(code), err: err}
	}
	return err
}

// CloseCode returns the WebSocket close code carried by err, so callers need not import the
// websocket library.
func CloseCode(err error) (int, bool) {
	var ce *CloseError
	if errors.As(err, &ce) {
		return ce.Code, true
	}
	if code := websocket.CloseStatus(err); code != -1 {
		return int(code), true
	}
	return 0, false
}

// Close ends the socket; Hermes detaches the connection's sessions.
func (c *Conn) Close() error {
	return c.ws.Close(websocket.StatusNormalClosure, "")
}
