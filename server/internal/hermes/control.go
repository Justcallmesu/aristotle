package hermes

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"time"
)

const (
	controlTimeout = 5 * time.Second
	controlMaxLine = 1 << 20
)

// ServedProfile is one row of identify.served_profiles; ProfileID is the profile's home path.
type ServedProfile struct {
	ProfileID string `json:"profile_id"`
	Home      string `json:"home"`
}

// Identity is what Hermes reports about itself right now. APIOrigin changes when Hermes restarts.
type Identity struct {
	RuntimeProtocol int             `json:"runtime_protocol"`
	APIOrigin       string          `json:"api_origin"`
	InstanceID      string          `json:"instance_id"`
	State           string          `json:"state"`
	HermesHome      string          `json:"hermes_home"`
	ServedProfiles  []ServedProfile `json:"served_profiles"`
	Capabilities    []string        `json:"capabilities"`
}

// ControlError is a refusal from the control socket ({"ok":false,"error":...}).
type ControlError struct{ Message string }

func (e *ControlError) Error() string { return "hermes control socket refused: " + e.Message }

type controlEnvelope struct {
	OK     bool            `json:"ok"`
	ID     int             `json:"id"`
	Error  string          `json:"error"`
	Result json.RawMessage `json:"result"`
}

// Identify asks Hermes who it is and fails closed on an unknown protocol or a gateway that is not ready.
func (c *Connector) Identify(ctx context.Context) (Identity, error) {
	raw, err := c.control(ctx, map[string]any{"protocol": 1, "id": 1, "verb": "identify"})
	if err != nil {
		return Identity{}, err
	}
	var id Identity
	if err := json.Unmarshal(raw, &id); err != nil {
		return Identity{}, fmt.Errorf("decode identify result: %w", err)
	}
	if err := id.check(); err != nil {
		return Identity{}, err
	}
	return id, nil
}

func (id Identity) check() error {
	if !SupportedProtocols[id.RuntimeProtocol] {
		return fmt.Errorf("%w: %d", ErrUnsupportedProtocol, id.RuntimeProtocol)
	}
	if id.State != "ready" {
		return fmt.Errorf("%w: state %q", ErrNotReady, id.State)
	}
	if !contains(id.Capabilities, requiredAuthority) {
		return fmt.Errorf("%w: missing capability %s", ErrNotReady, requiredAuthority)
	}
	if id.InstanceID == "" {
		return errors.New("hermes identify: empty instance_id")
	}
	return id.checkOrigin()
}

// checkOrigin keeps tickets on loopback: they must never be sent to another host.
func (id Identity) checkOrigin() error {
	u, err := url.Parse(id.APIOrigin)
	if err != nil || u.Scheme != "http" {
		return fmt.Errorf("hermes identify: unusable api_origin %q", id.APIOrigin)
	}
	if ip := net.ParseIP(u.Hostname()); ip == nil || !ip.IsLoopback() {
		return fmt.Errorf("hermes identify: api_origin %q is not loopback", id.APIOrigin)
	}
	return nil
}

// launchProfileID is the served profile bound to the gateway's own home; a host-scoped
// ticket for it reaches every served profile (docs/protocol.md §3).
func (id Identity) launchProfileID() (string, error) {
	want := filepath.Clean(id.HermesHome)
	for _, p := range id.ServedProfiles {
		if filepath.Clean(p.Home) == want {
			return p.ProfileID, nil
		}
	}
	return "", fmt.Errorf("hermes identify: launch profile %q is not in served_profiles", id.HermesHome)
}

// mintTicket requests a single-use ticket. The value must never be logged.
func (c *Connector) mintTicket(ctx context.Context, id Identity, purpose, scope string) (string, error) {
	profileID, err := id.launchProfileID()
	if err != nil {
		return "", err
	}
	params := map[string]any{"profile_id": profileID, "instance_id": id.InstanceID, "purpose": purpose}
	if scope != "" {
		params["scope"] = scope
	}
	raw, err := c.control(ctx, map[string]any{"protocol": 1, "id": 1, "verb": "session-ticket", "params": params})
	if err != nil {
		return "", err
	}
	var result struct {
		Ticket          string `json:"ticket"`
		InstanceID      string `json:"instance_id"`
		ProfileID       string `json:"profile_id"`
		RuntimeProtocol int    `json:"runtime_protocol"`
	}
	if err := json.Unmarshal(raw, &result); err != nil {
		return "", fmt.Errorf("decode ticket result: %w", err)
	}
	if !SupportedProtocols[result.RuntimeProtocol] {
		return "", fmt.Errorf("%w: %d", ErrUnsupportedProtocol, result.RuntimeProtocol)
	}
	if result.Ticket == "" || result.InstanceID != id.InstanceID || result.ProfileID != profileID {
		return "", errors.New("hermes ticket answer does not match the identity")
	}
	return result.Ticket, nil
}

// control performs one request/answer exchange (one JSON line each way, then Hermes closes).
func (c *Connector) control(ctx context.Context, request map[string]any) (json.RawMessage, error) {
	path, err := c.socketPath()
	if err != nil {
		return nil, err
	}
	dialer := net.Dialer{Timeout: controlTimeout}
	conn, err := dialer.DialContext(ctx, "unix", path)
	if err != nil {
		if isNotRunning(err) {
			return nil, fmt.Errorf("%w: %v", ErrHermesNotRunning, err)
		}
		return nil, fmt.Errorf("dial hermes control socket: %w", err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(deadlineFor(ctx))
	line, err := json.Marshal(request)
	if err != nil {
		return nil, err
	}
	if _, err := conn.Write(append(line, '\n')); err != nil {
		return nil, fmt.Errorf("write control request: %w", err)
	}
	answer, err := bufio.NewReaderSize(conn, 64*1024).ReadBytes('\n')
	if len(answer) == 0 {
		return nil, fmt.Errorf("read control answer: %w", orEOF(err))
	}
	return decodeEnvelope(answer, len(answer) <= controlMaxLine)
}

func decodeEnvelope(answer []byte, sizeOK bool) (json.RawMessage, error) {
	if !sizeOK {
		return nil, errors.New("control answer too large")
	}
	var env controlEnvelope
	if err := json.Unmarshal(answer, &env); err != nil {
		return nil, fmt.Errorf("decode control answer: %w", err)
	}
	if !env.OK {
		return nil, &ControlError{Message: env.Error}
	}
	if env.ID != 1 {
		return nil, fmt.Errorf("control answer id %d, want 1", env.ID)
	}
	return env.Result, nil
}

// socketPath is $HERMES_HOME/gateway.sock, or the path named by gateway.sock.path when the
// default is too long for sun_path (docs/protocol.md §1.1).
func (c *Connector) socketPath() (string, error) {
	named, err := os.ReadFile(filepath.Join(c.home, controlSocketName+".path"))
	switch {
	case err == nil:
		if p := strings.TrimSpace(string(named)); p != "" {
			return p, nil
		}
	case !errors.Is(err, os.ErrNotExist):
		return "", fmt.Errorf("read gateway.sock.path: %w", err)
	}
	return filepath.Join(c.home, controlSocketName), nil
}

func isNotRunning(err error) bool {
	return errors.Is(err, os.ErrNotExist) || errors.Is(err, syscall.ENOENT) || errors.Is(err, syscall.ECONNREFUSED)
}

func deadlineFor(ctx context.Context) time.Time {
	if d, ok := ctx.Deadline(); ok {
		return d
	}
	return time.Now().Add(controlTimeout)
}

func orEOF(err error) error {
	if err == nil {
		return errors.New("empty answer")
	}
	return err
}

func contains(list []string, want string) bool {
	for _, s := range list {
		if s == want {
			return true
		}
	}
	return false
}
