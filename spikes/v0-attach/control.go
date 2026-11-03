package main

// Control-socket client: one JSON line in, one JSON line out, then the server closes
// (gateway/control_socket.py:6, :415-433).

import (
	"bufio"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"time"
)

type servedProfile struct {
	Name      string `json:"-"`
	Home      string `json:"home"`
	ProfileID string `json:"profile_id"`
}

type identity struct {
	Protocol        int             `json:"protocol"`
	Kind            string          `json:"kind"`
	PID             int             `json:"pid"`
	HermesHome      string          `json:"hermes_home"`
	Profile         string          `json:"profile"`
	Supervisor      string          `json:"supervisor"`
	CodeSHA         string          `json:"code_sha"`
	InstanceID      string          `json:"instance_id"`
	RuntimeProtocol int             `json:"runtime_protocol"`
	AuthorityEpoch  int             `json:"authority_epoch"`
	State           string          `json:"state"`
	APIOrigin       string          `json:"api_origin"`
	ServedProfiles  []servedProfile `json:"served_profiles"`
	Capabilities    []string        `json:"capabilities"`
	raw             map[string]any
}

func controlSocketPath() string {
	if p := os.Getenv("HERMES_CONTROL_SOCKET"); p != "" {
		return p
	}
	home, _ := os.UserHomeDir()
	return filepath.Join(home, ".hermes", "gateway.sock")
}

// controlQuery sends one request line and returns the decoded envelope.
func controlQuery(req map[string]any) (map[string]any, error) {
	conn, err := net.DialTimeout("unix", controlSocketPath(), 5*time.Second)
	if err != nil {
		return nil, fmt.Errorf("dial control socket: %w", err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
	line, _ := json.Marshal(req)
	if _, err := conn.Write(append(line, '\n')); err != nil {
		return nil, err
	}
	reader := bufio.NewReaderSize(conn, 64*1024)
	raw, err := reader.ReadBytes('\n')
	if err != nil && len(raw) == 0 {
		return nil, fmt.Errorf("read control answer: %w", err)
	}
	var envelope map[string]any
	if err := json.Unmarshal(raw, &envelope); err != nil {
		return nil, fmt.Errorf("decode control answer: %w", err)
	}
	if ok, _ := envelope["ok"].(bool); !ok {
		return envelope, fmt.Errorf("control socket refused: %v", envelope["error"])
	}
	return envelope, nil
}

func identify() (*identity, error) {
	envelope, err := controlQuery(map[string]any{"protocol": 1, "id": 1, "verb": "identify"})
	if err != nil {
		return nil, err
	}
	result, _ := envelope["result"].(map[string]any)
	encoded, _ := json.Marshal(result)
	id := &identity{raw: result}
	if err := json.Unmarshal(encoded, id); err != nil {
		return nil, err
	}
	if id.RuntimeProtocol != 1 {
		return id, fmt.Errorf("unknown runtime_protocol %d (fail closed)", id.RuntimeProtocol)
	}
	return id, nil
}

// profileFor picks the served profile by name ("" or "default" = the gateway's own home,
// otherwise <hermes_home>/profiles/<name>). served_profiles rows carry only profile_id and home
// (gateway/session_authorities.py:135-136); profile_id IS the home path.
func (id *identity) profileFor(name string) (servedProfile, error) {
	want := filepath.Clean(id.HermesHome)
	if name != "" && name != "default" {
		want = filepath.Join(want, "profiles", name)
	}
	for _, p := range id.ServedProfiles {
		if filepath.Clean(p.Home) == want {
			p.Name = name
			return p, nil
		}
	}
	return servedProfile{}, fmt.Errorf("profile %q not served by this gateway", name)
}

// mintTicket asks for a single-use 30 s ticket. scope "host" = Desktop's shared socket.
// The ticket is returned to the caller only; it is never printed or saved.
func mintTicket(id *identity, profileID, scope string) (string, error) {
	return mintTicketFor(id, profileID, "interactive", scope)
}

// mintPurposeTicket mints a non-interactive grant (scope "host" is interactive-only).
func mintPurposeTicket(id *identity, profileID, purpose string) (string, error) {
	return mintTicketFor(id, profileID, purpose, "")
}

func mintTicketFor(id *identity, profileID, purpose, scope string) (string, error) {
	params := map[string]any{"profile_id": profileID, "instance_id": id.InstanceID, "purpose": purpose}
	if scope != "" {
		params["scope"] = scope
	}
	envelope, err := controlQuery(map[string]any{"protocol": 1, "id": 1, "verb": "session-ticket", "params": params})
	if err != nil {
		return "", err
	}
	result, _ := envelope["result"].(map[string]any)
	ticket, _ := result["ticket"].(string)
	if ticket == "" || result["instance_id"] != id.InstanceID || result["profile_id"] != profileID {
		return "", errors.New("ticket answer does not match identity")
	}
	if rp, _ := result["runtime_protocol"].(float64); rp != 1 {
		return "", fmt.Errorf("ticket runtime_protocol %v (fail closed)", result["runtime_protocol"])
	}
	return ticket, nil
}
