package app

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"os"
	"sync"
	"time"

	"github.com/justcallmesu/aristotle/server/internal/config"
	"github.com/justcallmesu/aristotle/server/internal/control"
	"github.com/justcallmesu/aristotle/server/internal/devices"
	"github.com/justcallmesu/aristotle/server/internal/discovery"
	"github.com/justcallmesu/aristotle/server/internal/hermes"
	"github.com/justcallmesu/aristotle/server/internal/httpapi"
	"github.com/justcallmesu/aristotle/server/internal/listen"
	"github.com/justcallmesu/aristotle/server/internal/pairing"
	"github.com/justcallmesu/aristotle/server/internal/pcstatus"
	"github.com/justcallmesu/aristotle/server/internal/relay"
	"github.com/justcallmesu/aristotle/server/internal/service"
	"github.com/justcallmesu/aristotle/server/internal/stocks"
	"github.com/justcallmesu/aristotle/server/internal/terminal"
	"github.com/justcallmesu/aristotle/server/internal/tlscert"
	protover "github.com/justcallmesu/aristotle/server/internal/version"
)

const shutdownTimeout = 5 * time.Second

// Serve loads config, prepares the state dir and serves until ctx is done.
func Serve(ctx context.Context, version string, log *slog.Logger) error {
	cfgPath, err := config.ConfigPath()
	if err != nil {
		return err
	}
	cfg, err := config.Load(cfgPath)
	if err != nil {
		return err
	}
	stateDir, err := config.EnsureStateDir(log)
	if err != nil {
		return err
	}
	srv, err := newServer(cfg, stateDir, version, log, nil)
	if err != nil {
		return err
	}
	// Only the real server announces on the network; tests build servers without a responder.
	srv.discovery.Start = discovery.MDNSStart
	// Only the real server runs the stocks jobs (they call Yahoo); tests build servers without them.
	srv.stocksJobs = true
	return srv.run(ctx)
}

// server is one running aristotle-server: loopback listener (plain HTTP, /healthz only), the
// LAN/Tailscale HTTPS listener manager, control socket and the /v1/ws relay hub, sharing the pairing
// codes and devices.
type server struct {
	cfg       config.Config
	stateDir  string
	version   string
	log       *slog.Logger
	cert      tlscert.Cert
	codes     *pairing.Codes
	devices   *devices.Store
	listen    *listen.Manager
	discovery *discovery.Announcer // mDNS on LAN-listener interfaces (Start nil: off)
	control   *control.Server
	rejects   *httpapi.RejectLog // one limiter for every listener
	relay     *relay.Hub
	grants    *terminal.Grants    // terminal switch and per-device grants (terminal.json)
	terms     *terminal.Hub       // /v1/terminal shells
	pcstatus  *pcstatus.Collector // GET /v1/pc-status
	stocks    *stocks.Service     // /v1/stocks/* (stocks.json)
	// stocksJobs starts the stocks scheduler in run (catch-up at boot, then every 30 s); off in tests.
	stocksJobs bool
}

// newServer builds the components. scan, when non-nil, replaces the system
// interface scan (tests).
func newServer(cfg config.Config, stateDir, version string, log *slog.Logger, scan func() ([]listen.Iface, error)) (*server, error) {
	if scan == nil {
		scan = listen.SystemInterfaces
	}
	// The control lock comes first: a second server must be refused before it
	// loads or creates the certificate or touches the device store.
	ctl := &control.Server{Path: control.SocketPath(stateDir), Log: log}
	if err := ctl.Lock(); err != nil {
		return nil, err
	}
	store, err := devices.Open(stateDir, nil, log)
	if err != nil {
		ctl.Unlock()
		return nil, err
	}
	// A bad terminal.json disables the terminal, not the server: the relay keeps working (fail closed).
	grants, err := terminal.OpenGrantsFailClosed(stateDir)
	if err != nil {
		log.Error("terminal state unusable: the remote terminal stays disabled until it is fixed or removed "+
			"(aristotle-server terminal status)", "err", err)
	}
	cert, err := loadCert(cfg, stateDir, scan, log)
	if err != nil {
		ctl.Unlock()
		return nil, err
	}
	connector, err := hermes.New(hermes.Options{Logger: log})
	if err != nil {
		ctl.Unlock()
		return nil, err
	}
	s := &server{cfg: cfg, stateDir: stateDir, version: version, log: log, cert: cert, codes: &pairing.Codes{}, devices: store,
		rejects: httpapi.NewRejectLog(log), control: ctl,
		relay:  relay.New(relay.Options{Dialer: connector, Devices: store, Log: log}),
		grants: grants, terms: terminal.New(terminal.Options{Grants: grants, Devices: store, Log: log,
			KeepEnv: cfg.Terminal.KeepEnv, DelegatedUnit: delegatedUnit()})}
	s.pcstatus = &pcstatus.Collector{Log: log, Hermes: hermesRunning(connector), Tailscale: s.tailscaleUp}
	s.stocks = stocks.New(stocks.Options{StateDir: stateDir, Log: log, Analyst: stocks.HermesAnalyst(connector)})
	s.listen = &listen.Manager{
		Port: cfg.Port, Policy: listenPolicy(cfg),
		TLS: cert.ServerConfig(), Handler: s.remoteHandler, Scan: scan, Log: log,
		Wrap: relay.WrapListener, // the relay sees exactly when a write to a phone waits for it
	}
	host, _ := os.Hostname()
	s.discovery = &discovery.Announcer{Enabled: cfg.Discovery.Enabled, Port: cfg.Port, Fingerprint: cert.Fingerprint,
		Proto: protover.ProtocolVersion, Hostname: host, Log: log}
	s.listen.OnChange = s.syncDiscovery
	ctl.Handlers = s.controlHandlers()
	ctl.Authorize = s.authorizeControl
	return s, nil
}

// delegatedUnit names the unit whose cgroup the terminal may split (docs/terminal.md): the installed
// unit when its file has Delegate=yes. The terminal also checks that it really runs in that cgroup.
func delegatedUnit() string {
	if service.InstalledUnitDelegates() {
		return service.UnitName
	}
	return ""
}

func listenPolicy(cfg config.Config) listen.Policy {
	return listen.Policy{LAN: cfg.LANEnabled, Tailscale: cfg.TailscaleEnabled, LANInterfaces: cfg.LANInterfaces}
}

func loadCert(cfg config.Config, stateDir string, scan func() ([]listen.Iface, error), log *slog.Logger) (tlscert.Cert, error) {
	ifaces, err := scan()
	if err != nil {
		log.Warn("interface scan failed; certificate gets no IP SANs", "err", err)
	}
	// SANs only matter for a new certificate and only as a courtesy: phones pin the key (SPKI), so an
	// existing certificate is never regenerated to add addresses.
	sansPolicy := listen.Policy{LAN: true, Tailscale: true, LANInterfaces: cfg.LANInterfaces}
	sans := listen.Addrs(listen.Select(ifaces, sansPolicy))
	cert, created, err := tlscert.LoadOrCreate(stateDir, sans, time.Now())
	if err != nil {
		return tlscert.Cert{}, err
	}
	log.Info("tls certificate", "created", created, "fingerprint", cert.Fingerprint)
	return cert, nil
}

// syncDiscovery follows the listener set; nil endpoints (manager closed) stop every announcement.
func (s *server) syncDiscovery(eps []listen.Endpoint) {
	if s.discovery.Start == nil {
		return
	}
	if eps == nil {
		s.discovery.Close()
		return
	}
	s.discovery.Sync(eps)
}

func (s *server) remoteHandler(kind listen.Kind) http.Handler {
	deps := httpapi.Deps{Codes: s.codes, Devices: s.devices, Rejects: s.rejects, Relay: s.relay, Terminal: s.terms,
		Listener: string(kind), Addresses: s.addresses, PCStatus: s.pcstatus.Status, Stocks: s.stocks}
	return httpapi.NewRemoteRouter(s.version, s.log.With("listener", string(kind)), deps)
}

// run serves until ctx is done or the loopback listener fails.
func (s *server) run(ctx context.Context) error {
	if err := s.control.Listen(); err != nil {
		return err
	}
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	var wg sync.WaitGroup
	wg.Go(func() { s.control.Serve(ctx) })
	ln, err := net.Listen("tcp", s.cfg.Addr())
	if err != nil {
		cancel()
		wg.Wait()
		return fmt.Errorf("listen %s: %w", s.cfg.Addr(), err)
	}
	s.log.Info("listening", "addr", ln.Addr().String(), "kind", "loopback", "state_dir", s.stateDir, "version", s.version)
	wg.Go(func() { s.listen.Run(ctx) })
	if s.stocksJobs {
		s.stocks.Start()
	}
	err = serveListener(ctx, ln, httpapi.NewRouter(s.version, s.log), s.log)
	cancel()
	s.closeRelays()
	s.closeTerminals()
	s.stocks.Close()
	wg.Wait()
	return err
}

// closeRelays ends every /v1/ws relay with 1001. http.Server.Shutdown does
// not track hijacked (WebSocket) connections, so the hub does it, bounded.
func (s *server) closeRelays() {
	ctx, cancel := context.WithTimeout(context.Background(), shutdownTimeout)
	defer cancel()
	if err := s.relay.Shutdown(ctx); err != nil {
		s.log.Warn("relays did not close in time; cut", "err", err)
	}
}

// terminalShutdownTimeout covers a shell's SIGHUP grace (terminal.DefaultKillGrace) plus the close.
const terminalShutdownTimeout = terminal.DefaultKillGrace + 3*time.Second

// closeTerminals ends every terminal with 1001 and kills its processes, bounded.
func (s *server) closeTerminals() {
	ctx, cancel := context.WithTimeout(context.Background(), terminalShutdownTimeout)
	defer cancel()
	if err := s.terms.Shutdown(ctx); err != nil {
		s.log.Warn("terminals did not close in time; cut", "err", err)
	}
}

func serveListener(ctx context.Context, ln net.Listener, h http.Handler, log *slog.Logger) error {
	srv := &http.Server{Handler: h, ReadHeaderTimeout: 10 * time.Second, ErrorLog: listen.ErrorLog(log)}
	errc := make(chan error, 1)
	go func() { errc <- srv.Serve(ln) }()
	select {
	case err := <-errc:
		return err
	case <-ctx.Done():
	}
	log.Info("shutting down")
	sctx, cancel := context.WithTimeout(context.Background(), shutdownTimeout)
	defer cancel()
	if err := srv.Shutdown(sctx); err != nil {
		return fmt.Errorf("shutdown: %w", err)
	}
	if err := <-errc; err != nil && !errors.Is(err, http.ErrServerClosed) {
		return err
	}
	return nil
}
