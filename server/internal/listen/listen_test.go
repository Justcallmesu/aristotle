package listen

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"maps"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/justcallmesu/aristotle/server/internal/tlscert"
)

// syncBuffer is a goroutine-safe log sink.
type syncBuffer struct {
	mu  sync.Mutex
	buf bytes.Buffer
}

func (b *syncBuffer) Write(p []byte) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.Write(p)
}

func (b *syncBuffer) String() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.String()
}

func pfxs(ss ...string) []netip.Prefix {
	out := make([]netip.Prefix, len(ss))
	for i, s := range ss {
		out[i] = netip.MustParsePrefix(s)
	}
	return out
}

// ep builds an endpoint from "addr/bits" on iface.
func ep(kind Kind, iface, prefix string) Endpoint {
	p := netip.MustParsePrefix(prefix)
	return Endpoint{Kind: kind, Addr: p.Addr(), Iface: iface, Subnet: p.Masked()}
}

var (
	wifiIface = Iface{Name: "wlan0", Up: true, Physical: true, DefaultRoute: true,
		Addrs: pfxs("192.168.1.61/24", "fe80::97eb:a53b:be74:1de1/64")}
	tsIface = Iface{Name: "tailscale0", Up: true, Tun: true,
		Addrs: pfxs("100.74.61.96/32", "fd7a:115c:a1e0::d628:3d61/128", "fe80::741f:bfb1:1b74:789c/64")}
	wifiEP   = ep(LAN, "wlan0", "192.168.1.61/24")
	tsEP4    = ep(Tailscale, "tailscale0", "100.74.61.96/32")
	tsEP6    = ep(Tailscale, "tailscale0", "fd7a:115c:a1e0::d628:3d61/128")
	allLocal = Policy{LAN: true, Tailscale: true}
)

func TestClassify(t *testing.T) {
	tests := []struct {
		addr string
		want Kind
		ok   bool
	}{
		{"10.1.2.3", LAN, true},
		{"172.16.0.1", LAN, true},
		{"172.31.255.255", LAN, true},
		{"172.32.0.1", "", false},
		{"192.168.1.61", LAN, true},
		{"::ffff:192.168.1.61", LAN, true},
		{"100.64.0.1", Tailscale, true},
		{"100.127.255.255", Tailscale, true},
		{"100.128.0.1", "", false},
		{"fd7a:115c:a1e0::d628:3d61", Tailscale, true},
		{"fd7a:115c:a1e1::1", "", false},
		{"8.8.8.8", "", false},
		{"127.0.0.1", "", false},
		{"0.0.0.0", "", false},
		{"::", "", false},
		{"fe80::1", "", false},
	}
	for _, tt := range tests {
		got, ok := Classify(netip.MustParseAddr(tt.addr))
		if got != tt.want || ok != tt.ok {
			t.Errorf("Classify(%s) = %q,%v want %q,%v", tt.addr, got, ok, tt.want, tt.ok)
		}
	}
}

func TestSelect(t *testing.T) {
	ifaces := []Iface{
		{Name: "lo", Up: true, Loopback: true, Addrs: pfxs("127.0.0.1/8", "::1/128", "10.9.9.9/8")},
		wifiIface,
		tsIface,
		{Name: "docker0", Up: false, Addrs: pfxs("172.17.0.1/16")},
		{Name: "br-2c441c1f2fd0", Up: true, Addrs: pfxs("172.28.0.1/16")},
		{Name: "veth7b2bcba", Up: true, Addrs: pfxs("172.28.0.5/16")},
		{Name: "wg-office", Up: true, Tun: true, Addrs: pfxs("10.8.0.3/24")},
		{Name: "eth0", Up: true, Physical: true, Addrs: pfxs("203.0.113.5/24", "0.0.0.0/0", "::/0", "10.0.0.2/24")},
		{Name: "eth1", Up: false, Physical: true, Addrs: pfxs("10.1.0.2/24")},
	}
	tests := []struct {
		name string
		p    Policy
		want []Endpoint
	}{
		{"physical LAN and tailscale tun", allLocal, []Endpoint{
			wifiEP, ep(LAN, "eth0", "10.0.0.2/24"), tsEP4, tsEP6,
		}},
		{"lan only", Policy{LAN: true}, []Endpoint{wifiEP, ep(LAN, "eth0", "10.0.0.2/24")}},
		{"tailscale only", Policy{Tailscale: true}, []Endpoint{tsEP4, tsEP6}},
		{"none", Policy{}, nil},
		{"allowlist replaces the physical rule", Policy{LAN: true, LANInterfaces: []string{"br-2c441c1f2fd0"}}, []Endpoint{
			ep(LAN, "br-2c441c1f2fd0", "172.28.0.1/16"),
		}},
		{"allowlist with lan disabled", Policy{Tailscale: true, LANInterfaces: []string{"wlan0"}}, []Endpoint{tsEP4, tsEP6}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := Select(ifaces, tt.p)
			if !slices.Equal(got, tt.want) {
				t.Fatalf("got %v\nwant %v", got, tt.want)
			}
		})
	}
}

// A Tailscale-range address counts only on a TUN interface named tailscale*.
// Wi-Fi, hotel and carrier networks hand out 100.64/10 too.
func TestSelectTailnetRangeNeedsTailscaleTun(t *testing.T) {
	tests := []Iface{
		{Name: "wlan0", Up: true, Physical: true, Addrs: pfxs("100.80.1.2/10")},
		{Name: "wlan0", Up: true, Physical: true, Addrs: pfxs("fd7a:115c:a1e0::5/64")},
		{Name: "tun0", Up: true, Tun: true, Addrs: pfxs("100.80.1.2/32")},
		{Name: "tailscale0", Up: true, Tun: false, Addrs: pfxs("100.80.1.2/32")},
		{Name: "usb0", Up: true, Physical: true, Addrs: pfxs("100.100.1.9/24")},
	}
	for _, iface := range tests {
		if got := Select([]Iface{iface}, Policy{LAN: true, Tailscale: true}); len(got) != 0 {
			t.Errorf("%s (tun=%v) %v -> %v, want no endpoint", iface.Name, iface.Tun, iface.Addrs, got)
		}
	}
}

func TestSelectOrderDefaultRouteFirst(t *testing.T) {
	ifaces := []Iface{
		{Name: "eth0", Up: true, Physical: true, Addrs: pfxs("10.0.0.2/24")},
		{Name: "wlan0", Up: true, Physical: true, Addrs: pfxs("192.168.1.61/24")},
		tsIface,
	}
	got := Select(ifaces, allLocal)
	want := []Endpoint{ep(LAN, "eth0", "10.0.0.2/24"), wifiEP, tsEP4, tsEP6}
	if !slices.Equal(got, want) {
		t.Fatalf("no default route: got %v", got)
	}
	ifaces[1].DefaultRoute = true
	got = Select(ifaces, allLocal)
	want = []Endpoint{wifiEP, ep(LAN, "eth0", "10.0.0.2/24"), tsEP4, tsEP6}
	if !slices.Equal(got, want) {
		t.Fatalf("wlan0 default route: got %v", got)
	}
}

func TestSelectOneEndpointPerAddress(t *testing.T) {
	ifaces := []Iface{
		{Name: "eth0", Up: true, Physical: true, Addrs: pfxs("10.0.0.2/24")},
		{Name: "eth1", Up: true, Physical: true, Addrs: pfxs("10.0.0.2/24")},
	}
	if got := Select(ifaces, allLocal); len(got) != 1 || got[0].Iface != "eth0" {
		t.Fatalf("got %v", got)
	}
}

// fakeHost writes a /sys/class/net and /proc/net/route look-alike.
func fakeHost(t *testing.T, entries map[string]string, route string) Host {
	t.Helper()
	root := t.TempDir()
	for iface, entry := range entries {
		dir := filepath.Join(root, "net", iface)
		if err := os.MkdirAll(dir, 0o755); err != nil {
			t.Fatal(err)
		}
		if entry != "" {
			if err := os.WriteFile(filepath.Join(dir, entry), nil, 0o644); err != nil {
				t.Fatal(err)
			}
		}
	}
	routeFile := filepath.Join(root, "route")
	if err := os.WriteFile(routeFile, []byte(route), 0o644); err != nil {
		t.Fatal(err)
	}
	return Host{NetClassDir: filepath.Join(root, "net"), RouteFile: routeFile}
}

const procRoute = `Iface	Destination	Gateway 	Flags	RefCnt	Use	Metric	Mask		MTU	Window	IRTT
wlan0	00000000	0101A8C0	0003	0	0	600	00000000	0	0	0
docker0	000011AC	00000000	0001	0	0	0	0000FFFF	0	0	0
wlan0	0001A8C0	00000000	0001	0	0	600	00FFFFFF	0	0	0
`

func TestHostAnnotate(t *testing.T) {
	h := fakeHost(t, map[string]string{
		"wlan0": "device", "tailscale0": "tun_flags", "docker0": "bridge", "br-2c44": "bridge", "veth1": "", "lo": "",
	}, procRoute)
	ifaces := h.Annotate([]Iface{
		{Name: "lo"}, {Name: "wlan0"}, {Name: "tailscale0"}, {Name: "docker0"}, {Name: "br-2c44"}, {Name: "veth1"},
		{Name: "../wlan0"}, {Name: "gone0"},
	})
	got := map[string]string{}
	for _, i := range ifaces {
		got[i.Name] = fmt.Sprintf("phys=%v tun=%v def=%v", i.Physical, i.Tun, i.DefaultRoute)
	}
	want := map[string]string{
		"lo": "phys=false tun=false def=false", "wlan0": "phys=true tun=false def=true",
		"tailscale0": "phys=false tun=true def=false", "docker0": "phys=false tun=false def=false",
		"br-2c44": "phys=false tun=false def=false", "veth1": "phys=false tun=false def=false",
		"../wlan0": "phys=false tun=false def=false", "gone0": "phys=false tun=false def=false",
	}
	if !maps.Equal(got, want) {
		t.Fatalf("got  %v\nwant %v", got, want)
	}
	// End to end: on this fake host only wlan0 (LAN) and tailscale0 are selected.
	for i := range ifaces {
		ifaces[i].Up = true
	}
	ifaces[1].Addrs = pfxs("192.168.1.61/24")
	ifaces[2].Addrs = pfxs("100.74.61.96/32")
	ifaces[3].Addrs = pfxs("172.17.0.1/16")
	ifaces[4].Addrs = pfxs("172.28.0.1/16")
	if got := Select(ifaces, allLocal); !slices.Equal(got, []Endpoint{wifiEP, tsEP4}) {
		t.Fatalf("select on fake host: %v", got)
	}
}

func TestParseDefaultRoute(t *testing.T) {
	tests := []struct{ name, in, want string }{
		{"wlan0", procRoute, "wlan0"},
		{"lowest metric wins", procRoute + "eth0	00000000	0100000A	0003	0	0	100	00000000	0	0	0\n", "eth0"},
		{"down route ignored", "h\neth0	00000000	0100000A	0002	0	0	1	00000000	0	0	0\n", ""},
		{"no default", "h\ndocker0	000011AC	00000000	0001	0	0	0	0000FFFF	0	0	0\n", ""},
		{"empty", "", ""},
	}
	for _, tt := range tests {
		if got := parseDefaultRoute(strings.NewReader(tt.in)); got != tt.want {
			t.Errorf("%s: got %q want %q", tt.name, got, tt.want)
		}
	}
	if got := (Host{RouteFile: "/nonexistent/route"}).defaultRouteIface(); got != "" {
		t.Errorf("missing route file: %q", got)
	}
}

func TestSystemInterfacesHasAddresses(t *testing.T) {
	ifaces, err := SystemInterfaces()
	if err != nil {
		t.Fatal(err)
	}
	for _, i := range ifaces {
		for _, p := range i.Addrs {
			if p.Addr().Is4In6() || !p.IsValid() {
				t.Errorf("%s: bad prefix %v", i.Name, p)
			}
		}
	}
}

func TestEndpointURL(t *testing.T) {
	if got := wifiEP.URL(8760); got != "https://192.168.1.61:8760" {
		t.Error(got)
	}
	if got := ep(Tailscale, "tailscale0", "fd7a:115c:a1e0::1/128").URL(8760); got != "http://[fd7a:115c:a1e0::1]:8760" {
		t.Error(got)
	}
}

func TestBindTCPRefuses(t *testing.T) {
	tests := []Endpoint{
		{Kind: LAN, Addr: netip.MustParseAddr("0.0.0.0")},
		{Kind: Tailscale, Addr: netip.MustParseAddr("::")},
		{Kind: LAN, Addr: netip.Addr{}},
		{Kind: LAN, Addr: netip.MustParseAddr("127.0.0.1")},
		{Kind: LAN, Addr: netip.MustParseAddr("100.74.61.96")},
		{Kind: Tailscale, Addr: netip.MustParseAddr("8.8.8.8")},
	}
	for _, e := range tests {
		if ln, err := BindTCP(e, 0); err == nil {
			ln.Close()
			t.Errorf("BindTCP(%v) succeeded, want refusal", e)
		}
	}
}

func TestPeerGate(t *testing.T) {
	ok := http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusNoContent) })
	lan := wifiEP // 192.168.1.61 on 192.168.1.0/24
	tests := []struct {
		e      Endpoint
		remote string
		want   int
	}{
		{lan, "192.168.1.20:5000", http.StatusNoContent},
		{lan, "[::ffff:192.168.1.254]:5000", http.StatusNoContent},
		{lan, "172.28.0.2:5000", http.StatusForbidden}, // docker container: RFC1918 but off-link
		{lan, "10.0.0.5:5000", http.StatusForbidden},
		{lan, "192.168.2.20:5000", http.StatusForbidden},
		{lan, "100.74.1.1:5000", http.StatusForbidden},
		{lan, "203.0.113.9:5000", http.StatusForbidden},
		{lan, "127.0.0.1:5000", http.StatusForbidden},
		{lan, "garbage", http.StatusForbidden},
		{Endpoint{Kind: LAN, Addr: lan.Addr}, "192.168.1.20:5000", http.StatusForbidden}, // no subnet: fail closed
		{tsEP4, "100.101.102.103:5000", http.StatusNoContent},
		{tsEP4, "[fd7a:115c:a1e0::5]:5000", http.StatusNoContent},
		{tsEP4, "192.168.1.20:5000", http.StatusForbidden},
		{tsEP4, "[2001:db8::1]:5000", http.StatusForbidden},
	}
	for _, tt := range tests {
		req := httptest.NewRequest(http.MethodGet, "/healthz", nil)
		req.RemoteAddr = tt.remote
		rec := httptest.NewRecorder()
		PeerGate(tt.e, ok).ServeHTTP(rec, req)
		if rec.Code != tt.want {
			t.Errorf("%s %s from %s = %d, want %d", tt.e.Kind, tt.e.Subnet, tt.remote, rec.Code, tt.want)
		}
	}
}

// fakeNet serves interface lists and binds loopback ports in place of the
// real addresses, so the manager can be exercised without those IPs.
type fakeNet struct {
	mu     sync.Mutex
	ifaces []Iface
	err    error
	failOn map[netip.Addr]bool
}

func (f *fakeNet) set(ifaces []Iface, err error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.ifaces, f.err = ifaces, err
}

func (f *fakeNet) scan() ([]Iface, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.ifaces, f.err
}

func (f *fakeNet) bind(e Endpoint, _ int) (net.Listener, error) {
	f.mu.Lock()
	fail := f.failOn[e.Addr]
	f.mu.Unlock()
	if fail {
		return nil, errors.New("address in use")
	}
	return net.Listen("tcp", "127.0.0.1:0")
}

func newTestManager(t *testing.T, f *fakeNet, logs io.Writer) *Manager {
	cert, _, err := tlscert.LoadOrCreate(t.TempDir(), nil, time.Now())
	if err != nil {
		t.Fatal(err)
	}
	m := &Manager{
		Port: 8760, Policy: allLocal,
		TLS:     cert.ServerConfig(),
		Handler: func(Kind) http.Handler { return http.NotFoundHandler() },
		Scan:    f.scan, Bind: f.bind,
		Log: slog.New(slog.NewTextHandler(logs, nil)),
	}
	t.Cleanup(m.Close)
	return m
}

func TestManagerAddsAndRemovesOnChange(t *testing.T) {
	f := &fakeNet{}
	logs := &syncBuffer{}
	m := newTestManager(t, f, logs)
	f.set([]Iface{wifiIface, tsIface}, nil)
	m.Reconcile()
	m.Reconcile() // no change: no new log lines
	want := []Endpoint{wifiEP, tsEP4, tsEP6}
	if got := m.Active(); !slices.Equal(got, want) {
		t.Fatalf("active = %v", got)
	}
	f.set([]Iface{tsIface}, nil)
	m.Reconcile()
	if got := m.Active(); !slices.Equal(got, want[1:]) {
		t.Fatalf("after wifi down, active = %v", got)
	}
	if n := strings.Count(logs.String(), "listener added"); n != 3 {
		t.Fatalf("added logged %d times:\n%s", n, logs.String())
	}
	if n := strings.Count(logs.String(), "listener removed"); n != 1 {
		t.Fatalf("removed logged %d times:\n%s", n, logs.String())
	}
}

func TestManagerLogsBindFailureOnceAndRetries(t *testing.T) {
	f := &fakeNet{failOn: map[netip.Addr]bool{wifiEP.Addr: true}}
	logs := &syncBuffer{}
	m := newTestManager(t, f, logs)
	f.set([]Iface{wifiIface}, nil)
	m.Reconcile()
	m.Reconcile()
	if n := strings.Count(logs.String(), "bind failed"); n != 1 {
		t.Fatalf("bind failure logged %d times", n)
	}
	f.mu.Lock()
	f.failOn = nil
	f.mu.Unlock()
	m.Reconcile()
	if len(m.Active()) != 1 {
		t.Fatalf("retry did not bind: %v", m.Active())
	}
}

func TestManagerScanErrorKeepsListeners(t *testing.T) {
	f := &fakeNet{}
	logs := &syncBuffer{}
	m := newTestManager(t, f, logs)
	f.set([]Iface{wifiIface}, nil)
	m.Reconcile()
	f.set(nil, errors.New("netlink busy"))
	m.Reconcile()
	m.Reconcile()
	if len(m.Active()) != 1 || strings.Count(logs.String(), "scan failed") != 1 {
		t.Fatalf("active=%v logs=%s", m.Active(), logs.String())
	}
}

func TestManagerServesWithPeerGateAndStops(t *testing.T) {
	var mu sync.Mutex
	var bound net.Listener
	m := &Manager{
		Port: 8760, Policy: Policy{Tailscale: true},
		Handler: func(Kind) http.Handler {
			return http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusNoContent) })
		},
		Scan: func() ([]Iface, error) {
			return []Iface{{Name: "tailscale0", Up: true, Tun: true, Addrs: pfxs("100.74.61.96/32")}}, nil
		},
		Bind: func(Endpoint, int) (net.Listener, error) {
			ln, err := net.Listen("tcp", "127.0.0.1:0")
			mu.Lock()
			bound = ln
			mu.Unlock()
			return ln, err
		},
		Interval: time.Hour,
		Log:      slog.New(slog.NewTextHandler(io.Discard, nil)),
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { m.Run(ctx); close(done) }()
	deadline := time.Now().Add(5 * time.Second)
	for len(m.Active()) == 0 && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	mu.Lock()
	addr := bound.Addr().String()
	mu.Unlock()
	resp, err := http.Get("http://" + addr + "/healthz")
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	// The peer is 127.0.0.1, not a tailnet address: the gate must refuse it.
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("status %d, want 403 from the peer gate", resp.StatusCode)
	}
	cancel()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("Run did not stop")
	}
	if len(m.Active()) != 0 {
		t.Fatal("servers still active after stop")
	}
}

// Two LAN listeners start at once; each must get its own TLS config (the
// race detector flags a shared one: ServeTLS writes HTTP/2 settings to it).
func TestManagerTwoLANListenersStartCleanly(t *testing.T) {
	f := &fakeNet{}
	m := newTestManager(t, f, io.Discard)
	f.set([]Iface{
		wifiIface,
		{Name: "eth0", Up: true, Physical: true, Addrs: pfxs("10.0.0.2/24")},
		{Name: "eth1", Up: true, Physical: true, Addrs: pfxs("10.1.0.2/24")},
	}, nil)
	m.Reconcile()
	if n := len(m.Active()); n != 3 {
		t.Fatalf("active = %d", n)
	}
	time.Sleep(50 * time.Millisecond) // let the ServeTLS goroutines start
	m.Close()
}
