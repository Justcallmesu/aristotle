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

// Manager keeps one HTTPS server per selected endpoint and re-scans the
// interfaces every Interval, adding and removing servers as the set changes.
// Every server, LAN and Tailscale alike, serves TLS with the same
// certificate (spec Q49): there is no cleartext listener for phones.
type Manager struct {
	Port     int
	Policy   Policy
	TLS      *tls.Config                               // every listener; nil: nothing is bound
	Handler  func(Kind) http.Handler                   // wrapped in PeerGate
	Scan     func() ([]Iface, error)                   // default SystemInterfaces
	Bind     func(Endpoint, int) (net.Listener, error) // default BindTCP
	Wrap     func(net.Listener) net.Listener           // applied to each bound listener; nil: none
	Interval time.Duration
	Log      *slog.Logger
	// OnChange, when set, is called with the active endpoints after every
	// reconcile and with nil once the manager has closed (never under the lock).
	OnChange func([]Endpoint)

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
	m.reconcile()
	if m.OnChange != nil {
		m.OnChange(m.Active())
	}
}

func (m *Manager) reconcile() {
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
	ln, err := m.bind(e)
	if err != nil {
		if !m.bindFailed[e] {
			m.Log.Warn("listener bind failed; retrying on each scan", "kind", e.Kind, "iface", e.Iface, "addr", e.Addr, "err", err)
		}
		m.bindFailed = setTrue(m.bindFailed, e)
		return
	}
	delete(m.bindFailed, e)
	if m.Wrap != nil {
		ln = m.Wrap(ln) // below TLS: ServeTLS wraps what it is given
	}
	srv := m.newServer(e)
	if m.active == nil {
		m.active = map[Endpoint]*http.Server{}
	}
	m.active[e] = srv
	m.Log.Info("listener added", "kind", e.Kind, "iface", e.Iface, "addr", e.Addr, "subnet", e.Subnet, "url", e.URL(m.Port))
	go m.serve(e, srv, ln)
}

// bind refuses to bind without a TLS config: a remote listener never serves
// cleartext, so a missing config means no listener rather than plain HTTP.
func (m *Manager) bind(e Endpoint) (net.Listener, error) {
	if m.TLS == nil {
		return nil, errors.New("no TLS config: remote listeners serve HTTPS only")
	}
	if m.Bind != nil {
		return m.Bind(e, m.Port)
	}
	return BindTCP(e, m.Port)
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
		ErrorLog:          ErrorLog(m.Log),
	}
	// One copy per server: ServeTLS sets up HTTP/2 by writing to
	// srv.TLSConfig, which races when servers share a config.
	srv.TLSConfig = m.TLS.Clone()
	return srv
}

func (m *Manager) serve(e Endpoint, srv *http.Server, ln net.Listener) {
	err := srv.ServeTLS(ln, "", "")
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
	if m.OnChange != nil {
		m.OnChange(nil)
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

// BindTCP listens on addr:port, pinned to the endpoint's interface
// (SO_BINDTODEVICE on Linux). Wildcard, loopback and addresses outside the
// endpoint's network are refused, whatever the caller passes.
func BindTCP(e Endpoint, port int) (net.Listener, error) {
	if err := checkBindable(e); err != nil {
		return nil, err
	}
	lc := listenConfig(e.Iface)
	return lc.Listen(context.Background(), "tcp", netip.AddrPortFrom(e.Addr, uint16(port)).String())
}

func checkBindable(e Endpoint) error {
	if e.Iface == "" {
		// An empty name would mean "no device" to SO_BINDTODEVICE: an unpinned socket.
		return fmt.Errorf("bind %v refused: no interface name", e.Addr)
	}
	if !e.Addr.IsValid() || e.Addr.IsUnspecified() {
		return fmt.Errorf("bind %v refused: wildcard address", e.Addr)
	}
	if kind, ok := Classify(e.Addr); !ok || kind != e.Kind {
		return fmt.Errorf("bind %v refused: not a %s address", e.Addr, e.Kind)
	}
	return nil
}
