package listen

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"net/netip"
	"slices"
	"sync"
	"time"
)

const (
	DefaultInterval = 10 * time.Second
	shutdownTimeout = 5 * time.Second
)

// Manager keeps one HTTP server per selected endpoint and re-scans the
// interfaces every Interval, adding and removing servers as the set changes.
type Manager struct {
	Port     int
	Policy   Policy
	TLS      *tls.Config                               // used by LAN listeners
	Handler  func(Kind) http.Handler                   // wrapped in PeerGate
	Scan     func() ([]Iface, error)                   // default SystemInterfaces
	Bind     func(Endpoint, int) (net.Listener, error) // default BindTCP
	Interval time.Duration
	Log      *slog.Logger

	mu           sync.Mutex
	active       map[Endpoint]*http.Server
	bindFailed   map[Endpoint]bool
	scanFailed   bool
	defaultIface string // from the last successful scan
}

// Run reconciles at once, then on every tick until ctx is done, then shuts
// every server down.
func (m *Manager) Run(ctx context.Context) {
	interval := m.Interval
	if interval <= 0 {
		interval = DefaultInterval
	}
	m.Reconcile()
	t := time.NewTicker(interval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			m.Close()
			return
		case <-t.C:
			m.Reconcile()
		}
	}
}

// Reconcile scans once and makes the bound set match the selected endpoints.
func (m *Manager) Reconcile() {
	ifaces, err := m.scan()
	m.mu.Lock()
	defer m.mu.Unlock()
	if err != nil {
		if !m.scanFailed {
			m.Log.Warn("interface scan failed", "err", err)
		}
		m.scanFailed = true
		return
	}
	m.scanFailed = false
	m.defaultIface = DefaultIface(ifaces)
	want := Select(ifaces, m.Policy)
	m.removeStale(want)
	for _, e := range want {
		if _, ok := m.active[e]; !ok {
			m.add(e)
		}
	}
}

func (m *Manager) scan() ([]Iface, error) {
	if m.Scan != nil {
		return m.Scan()
	}
	return SystemInterfaces()
}

func (m *Manager) removeStale(want []Endpoint) {
	for e, srv := range m.active {
		if !slices.Contains(want, e) {
			_ = srv.Close() // the address is gone; its connections are dead anyway
			delete(m.active, e)
			m.Log.Info("listener removed", "kind", e.Kind, "iface", e.Iface, "addr", e.Addr)
		}
	}
	for e := range m.bindFailed {
		if !slices.Contains(want, e) {
			delete(m.bindFailed, e)
		}
	}
}

// add binds e and starts serving. Caller holds m.mu.
func (m *Manager) add(e Endpoint) {
	bind := m.Bind
	if bind == nil {
		bind = BindTCP
	}
	ln, err := bind(e, m.Port)
	if err != nil {
		if !m.bindFailed[e] {
			m.Log.Warn("listener bind failed; retrying on each scan", "kind", e.Kind, "iface", e.Iface, "addr", e.Addr, "err", err)
		}
		m.bindFailed = setTrue(m.bindFailed, e)
		return
	}
	delete(m.bindFailed, e)
	srv := m.newServer(e)
	if m.active == nil {
		m.active = map[Endpoint]*http.Server{}
	}
	m.active[e] = srv
	m.Log.Info("listener added", "kind", e.Kind, "iface", e.Iface, "addr", e.Addr, "subnet", e.Subnet, "url", e.URL(m.Port))
	go m.serve(e, srv, ln)
}

func setTrue(m map[Endpoint]bool, e Endpoint) map[Endpoint]bool {
	if m == nil {
		m = map[Endpoint]bool{}
	}
	m[e] = true
	return m
}

func (m *Manager) newServer(e Endpoint) *http.Server {
	srv := &http.Server{
		Handler:           PeerGate(e, m.Handler(e.Kind)),
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       2 * time.Minute,
		MaxHeaderBytes:    64 << 10,
		ErrorLog:          slog.NewLogLogger(m.Log.Handler(), slog.LevelDebug),
	}
	if e.Kind == LAN {
		// One copy per server: ServeTLS sets up HTTP/2 by writing to
		// srv.TLSConfig, which races when servers share a config.
		srv.TLSConfig = m.TLS.Clone()
	}
	return srv
}

func (m *Manager) serve(e Endpoint, srv *http.Server, ln net.Listener) {
	var err error
	if e.Kind == LAN {
		err = srv.ServeTLS(ln, "", "")
	} else {
		err = srv.Serve(ln)
	}
	if errors.Is(err, http.ErrServerClosed) {
		return
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.active[e] == srv {
		delete(m.active, e) // the next scan rebinds it
	}
	m.Log.Warn("listener stopped", "kind", e.Kind, "iface", e.Iface, "addr", e.Addr, "err", err)
}

// Close gracefully stops every server (bounded by a timeout).
func (m *Manager) Close() {
	m.mu.Lock()
	servers := m.active
	m.active = nil
	m.mu.Unlock()
	ctx, cancel := context.WithTimeout(context.Background(), shutdownTimeout)
	defer cancel()
	for e, srv := range servers {
		if err := srv.Shutdown(ctx); err != nil {
			_ = srv.Close()
		}
		m.Log.Info("listener removed", "kind", e.Kind, "iface", e.Iface, "addr", e.Addr)
	}
}

// DefaultRouteIface is the default-route interface seen by the last scan.
func (m *Manager) DefaultRouteIface() string {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.defaultIface
}

// Active returns the endpoints currently bound: LAN on the default-route
// interface first, other LAN next, Tailscale last.
func (m *Manager) Active() []Endpoint {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := make([]Endpoint, 0, len(m.active))
	for e := range m.active {
		out = append(out, e)
	}
	SortEndpoints(out, m.defaultIface)
	return out
}

// BindTCP listens on addr:port. Wildcard, loopback and addresses outside the
// endpoint's network are refused, whatever the caller passes.
func BindTCP(e Endpoint, port int) (net.Listener, error) {
	if err := checkBindable(e); err != nil {
		return nil, err
	}
	return net.Listen("tcp", netip.AddrPortFrom(e.Addr, uint16(port)).String())
}

func checkBindable(e Endpoint) error {
	if !e.Addr.IsValid() || e.Addr.IsUnspecified() {
		return fmt.Errorf("bind %v refused: wildcard address", e.Addr)
	}
	if kind, ok := Classify(e.Addr); !ok || kind != e.Kind {
		return fmt.Errorf("bind %v refused: not a %s address", e.Addr, e.Kind)
	}
	return nil
}
