// Package hermes owns everything about reaching the local Hermes gateway: the control
// socket, runtime-protocol checks, single-use tickets, the /api/ws upgrade and REST calls.
// Callers get frames and responses; they never see sockets, tickets or protocol versions.
// Wire facts: docs/protocol.md §1, §3, §8.
package hermes

import (
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
)

// SupportedProtocols lists the Hermes runtime_protocol values this build speaks.
var SupportedProtocols = map[int]bool{1: true}

// Sentinel errors callers map to phone-visible states.
var (
	// ErrHermesNotRunning: the control socket is missing or refuses connections.
	ErrHermesNotRunning = errors.New("hermes is not running")
	// ErrUnsupportedProtocol: runtime_protocol is not in SupportedProtocols (wrapped with the value).
	ErrUnsupportedProtocol = errors.New("unsupported hermes runtime protocol")
	// ErrNotReady: Hermes answered identify but is not ready or lacks session-authority-v1.
	ErrNotReady = errors.New("hermes is not ready")
	// ErrUpgradeRefused: /api/ws did not switch protocols (wrapped with the HTTP status).
	ErrUpgradeRefused = errors.New("hermes refused the websocket upgrade")
)

const (
	controlSocketName = "gateway.sock"
	// Subprotocols of the /api/ws upgrade (docs/protocol.md §1.2).
	gatewaySubprotocol  = "hermes-gateway-v1"
	ticketSubprotocol   = "hermes-gateway-ticket."
	ticketHTTPHeader    = "X-Hermes-Gateway-Ticket"
	purposeInteractive  = "interactive"
	purposeNativeHTTP   = "native-http"
	scopeHost           = "host"
	requiredAuthority   = "session-authority-v1"
	wsPath              = "/api/ws"
	maxFrameBytes       = 64 << 20
	capabilitiesRequest = `{"jsonrpc":"2.0","id":1,"method":"client.capabilities","params":{"server_requests":true}}`
)

// Options configures a Connector. The zero value is usable.
type Options struct {
	// Home is the Hermes home. Default: $HERMES_HOME, else ~/.hermes.
	Home string
	// Logger receives connection-level events only; tickets are never logged.
	Logger *slog.Logger
	// HTTPClient is used for the upgrade and REST calls. Default: a plain client without timeout.
	HTTPClient *http.Client
}

// Connector reaches one Hermes gateway. It holds no connection state, so it is safe for concurrent use.
type Connector struct {
	home   string
	log    *slog.Logger
	client *http.Client
}

// New builds a Connector from opts.
func New(opts Options) (*Connector, error) {
	home, err := resolveHome(opts.Home)
	if err != nil {
		return nil, err
	}
	log := opts.Logger
	if log == nil {
		log = slog.New(slog.NewTextHandler(io.Discard, nil))
	}
	client := opts.HTTPClient
	if client == nil {
		client = &http.Client{}
	}
	return &Connector{home: home, log: log, client: client}, nil
}

func resolveHome(configured string) (string, error) {
	if configured != "" {
		return configured, nil
	}
	if env := os.Getenv("HERMES_HOME"); env != "" {
		return env, nil
	}
	userHome, err := os.UserHomeDir()
	if err != nil {
		return "", fmt.Errorf("resolve hermes home: %w", err)
	}
	return filepath.Join(userHome, ".hermes"), nil
}
