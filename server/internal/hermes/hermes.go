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
	"time"
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
	// ErrUnknownProfile: the requested profile is not in identify.served_profiles.
	ErrUnknownProfile = errors.New("hermes does not serve that profile")
	// ErrForeignSocket: the control socket's peer runs as another uid; nothing is sent to it.
	ErrForeignSocket = errors.New("hermes control socket belongs to another user")
	// ErrDraining: Hermes closed the socket with 1013 (draining or not ready; try again later).
	// A 1013 sent before the upgrade is accepted reaches the client as HTTP 403, i.e. ErrUpgradeRefused.
	ErrDraining = errors.New("hermes is draining (close 1013)")
	// ErrForbidden: Hermes closed the socket with 4403 (refused). Before accept it looks like
	// ErrUpgradeRefused (HTTP 403), indistinguishable from a bad ticket.
	ErrForbidden = errors.New("hermes refused the connection (close 4403)")
	// ErrPendingOverflow: Hermes sent too many frames before acknowledging client.capabilities.
	ErrPendingOverflow = errors.New("hermes sent too much data before the capabilities ack")
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
	defaultAckTimeout   = 10 * time.Second
	maxPendingFrames    = 64
	maxPendingBytes     = 1 << 20
	capabilitiesRequest = `{"jsonrpc":"2.0","id":1,"method":"client.capabilities","params":{"server_requests":true}}`
)

// Options configures a Connector. The zero value is usable.
type Options struct {
	// Home is the Hermes home of the profile to serve. Default: $HERMES_HOME, else ~/.hermes. A named
	// profile home (<root>/profiles/<name>) without a gateway of its own is reached through its root's
	// gateway when that lists it in served_profiles, as Hermes' own clients do (discover.go).
	Home string
	// Logger receives connection-level events only; tickets are never logged.
	Logger *slog.Logger
	// HTTPClient is used for the upgrade and REST calls. Default: a plain client without timeout.
	HTTPClient *http.Client
	// HandshakeTimeout bounds the client.capabilities ack wait in Dial. Default: 10 s.
	HandshakeTimeout time.Duration
}

// Connector reaches one Hermes gateway. It holds no connection state, so it is safe for concurrent use.
type Connector struct {
	home       string
	root       string // multiplexer root when home is a named profile home, else ""
	log        *slog.Logger
	client     *http.Client
	ackTimeout time.Duration
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
	// Never follow redirects: a redirect would replay a spent ticket and forward headers to another host.
	noRedirect := *client
	noRedirect.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	ack := opts.HandshakeTimeout
	if ack <= 0 {
		ack = defaultAckTimeout
	}
	return &Connector{home: home, root: multiplexerRootFor(home), log: log, client: &noRedirect, ackTimeout: ack}, nil
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
