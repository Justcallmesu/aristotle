// Package discovery announces the server on the LAN with mDNS/DNS-SD
// (docs/protocol.md §15, spec Q65) so a phone can find it after a network
// change. The announcement is a hint only: the phone connects over TLS and
// trusts the pinned certificate fingerprint, never the announcement.
//
// It runs its own IPv4 responder per interface that carries a LAN listener:
// a socket bound to the mDNS group on that interface only, answering only
// on-link queries that arrived there. Never loopback, never Tailscale.
package discovery

import (
	"context"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/netip"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/justcallmesu/aristotle/server/internal/listen"
)

const (
	// ServiceType is the DNS-SD service type.
	ServiceType = "_aristotle._tcp"
	// Format is the TXT "v" value: the version of this discovery format.
	Format = "1"
	// FingerprintHintLen is how many hex digits of the fingerprint go into TXT.
	FingerprintHintLen = 16
)

// Spec is what one interface announces.
type Spec struct {
	Iface    string
	Instance string         // "Aristotle on <short hostname> (<8 hex>)"
	Target   string         // SRV target host, TargetHost: "aristotle-<short hostname>-<8 hex>.local."
	Port     int            // the LAN listener port
	Addrs    []netip.Prefix // the LAN listener addresses on this interface, with their on-link prefix length
	TXT      []string
	Log      *slog.Logger // nil: discard
}

// Responder is one running announcement.
type Responder interface{ Stop() error }

// failer is a Responder that can die on its own (socket error); Sync restarts it.
type failer interface{ Err() error }

// StartFunc starts an announcement bound to one interface.
type StartFunc func(Spec) (Responder, error)

// Announcer keeps one announcement per LAN-listener interface.
type Announcer struct {
	Enabled     bool
	Port        int
	Fingerprint string // the certificate fingerprint, any case, with or without separators
	Proto       int    // version.ProtocolVersion
	Hostname    string // host name (cut at the first dot); empty: "PC"
	Start       StartFunc
	Log         *slog.Logger
	Now         func() time.Time // nil: time.Now

	mu     sync.Mutex
	active map[string]running
	retry  map[string]*backoff // interfaces whose start failed
}

type running struct {
	addrs []netip.Prefix
	r     Responder
}

// InstanceName is the service instance name: "Aristotle on <short hostname>
// (<8 hex of the fingerprint>)". The whole label stays within 63 bytes: the
// host part is cut on a rune boundary, never the hint.
func InstanceName(host, fp string) string {
	const prefix = "Aristotle on "
	suffix := ""
	if h := nameHint(fp); h != "" {
		suffix = " (" + h + ")"
	}
	h := strings.ReplaceAll(ShortHost(host), ".", "-")
	return prefix + cutRunes(h, maxLabel-len(prefix)-len(suffix)) + suffix
}

// ShortHost cuts a host name at its first dot.
func ShortHost(host string) string {
	host, _, _ = strings.Cut(host, ".")
	if host == "" {
		return "PC"
	}
	return host
}

// FingerprintHint returns the first 16 hex digits of fp, lowercase.
func FingerprintHint(fp string) string {
	var b strings.Builder
	for _, r := range strings.ToLower(fp) {
		if (r >= '0' && r <= '9') || (r >= 'a' && r <= 'f') {
			b.WriteRune(r)
		}
	}
	s := b.String()
	if len(s) > FingerprintHintLen {
		s = s[:FingerprintHintLen]
	}
	return s
}

// TXT returns the TXT records: discovery format, fingerprint hint, protocol
// version. Nothing else: no device ids, tokens or addresses.
func (a *Announcer) TXT() []string {
	return []string{"v=" + Format, "fp=" + FingerprintHint(a.Fingerprint), "proto=" + strconv.Itoa(a.Proto)}
}

// Sync makes the announcements match the active listeners: one per interface
// that has a LAN listener, restarted when that interface's address set
// changes or its responder died, stopped when the listener goes. Tailscale
// endpoints are ignored. A failed start is retried with backoff.
func (a *Announcer) Sync(eps []listen.Endpoint) {
	if !a.Enabled {
		return
	}
	want := wanted(eps)
	a.mu.Lock()
	defer a.mu.Unlock()
	now := a.now()
	for name, r := range a.active {
		if f, ok := r.r.(failer); ok && f.Err() != nil {
			a.stop(name, r)
			a.failed(name, f.Err(), now)
			continue
		}
		if addrs, ok := want[name]; ok && slices.Equal(addrs, r.addrs) {
			continue
		}
		a.stop(name, r)
	}
	for name := range a.retry {
		if _, ok := want[name]; !ok {
			delete(a.retry, name)
		}
	}
	for name, addrs := range want {
		if _, ok := a.active[name]; ok {
			continue
		}
		if b := a.retry[name]; b != nil && !b.due(now) {
			continue
		}
		a.begin(name, addrs, now)
	}
}

// wanted groups the IPv4 LAN endpoints by interface, as address/prefix-length, sorted.
func wanted(eps []listen.Endpoint) map[string][]netip.Prefix {
	want := map[string][]netip.Prefix{}
	for _, e := range eps {
		if e.Kind != listen.LAN || e.Iface == "" || !e.Addr.Is4() || e.Addr.IsLoopback() {
			continue
		}
		bits := 32
		if e.Subnet.IsValid() {
			bits = e.Subnet.Bits()
		}
		want[e.Iface] = append(want[e.Iface], netip.PrefixFrom(e.Addr, bits))
	}
	for _, v := range want {
		slices.SortFunc(v, func(x, y netip.Prefix) int { return x.Addr().Compare(y.Addr()) })
	}
	return want
}

func (a *Announcer) now() time.Time {
	if a.Now != nil {
		return a.Now()
	}
	return time.Now()
}

// Close stops every announcement.
func (a *Announcer) Close() {
	a.mu.Lock()
	defer a.mu.Unlock()
	for name, r := range a.active {
		a.stop(name, r)
	}
}

// Active lists the interfaces being announced, sorted.
func (a *Announcer) Active() []string {
	a.mu.Lock()
	defer a.mu.Unlock()
	out := make([]string, 0, len(a.active))
	for n := range a.active {
		out = append(out, n)
	}
	slices.Sort(out)
	return out
}

func (a *Announcer) stop(name string, r running) {
	_ = r.r.Stop()
	delete(a.active, name)
	a.Log.Info("discovery stopped", "iface", name, "service", ServiceType)
}

func (a *Announcer) begin(name string, addrs []netip.Prefix, now time.Time) {
	host := a.Hostname
	spec := Spec{Iface: name, Instance: InstanceName(host, a.Fingerprint), Target: TargetHost(host, a.Fingerprint), Port: a.Port, TXT: a.TXT(),
		Addrs: addrs, Log: a.Log.With("iface", name)}
	r, err := a.Start(spec)
	if err != nil {
		a.failed(name, err, now)
		return
	}
	delete(a.retry, name)
	if a.active == nil {
		a.active = map[string]running{}
	}
	a.active[name] = running{addrs: addrs, r: r}
	a.Log.Info("discovery started", "iface", name, "service", ServiceType, "instance", spec.Instance, "port", spec.Port)
}

// failed schedules the next start with backoff; it warns once per distinct error.
func (a *Announcer) failed(name string, err error, now time.Time) {
	if a.retry == nil {
		a.retry = map[string]*backoff{}
	}
	b := a.retry[name]
	if b == nil {
		b = &backoff{}
		a.retry[name] = b
	}
	delay, fresh := b.failed(now, err)
	level := slog.LevelDebug
	if fresh {
		level = slog.LevelWarn
	}
	a.Log.Log(context.Background(), level, "discovery start failed", "iface", name, "err", err, "retry_in", delay)
}

// MDNSStart starts this package's IPv4 mDNS responder on one interface
// (docs/protocol.md §15). A socket setup failure is returned, never swallowed.
func MDNSStart(s Spec) (Responder, error) {
	if s.Iface == "" {
		return nil, fmt.Errorf("no interface name")
	}
	z, err := newZone(s)
	if err != nil {
		return nil, err
	}
	ifc, err := net.InterfaceByName(s.Iface)
	if err != nil {
		return nil, err
	}
	l := link{index: ifc.Index}
	for _, p := range s.Addrs {
		l.subnets = append(l.subnets, p.Masked())
	}
	c, err := openConn(ifc)
	if err != nil {
		return nil, err
	}
	log := s.Log
	if log == nil {
		log = slog.New(slog.NewTextHandler(io.Discard, nil))
	}
	r := newResponder(z, l, c, log)
	r.run()
	return r, nil
}
