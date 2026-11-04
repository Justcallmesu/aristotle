package app

import (
	"net/netip"
	"slices"
	"sync"
	"testing"
	"time"

	"github.com/justcallmesu/aristotle/server/internal/discovery"
	"github.com/justcallmesu/aristotle/server/internal/listen"
)

type recResp struct {
	spec discovery.Spec
	done bool
}

type recorder struct {
	mu   sync.Mutex
	list []*recResp
}

func (r *recorder) start(s discovery.Spec) (discovery.Responder, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	x := &recResp{spec: s}
	r.list = append(r.list, x)
	return x, nil
}

func (x *recResp) Stop() error { x.done = true; return nil }

func (r *recorder) live() []string {
	r.mu.Lock()
	defer r.mu.Unlock()
	var out []string
	for _, x := range r.list {
		if !x.done {
			out = append(out, x.spec.Iface)
		}
	}
	return out
}

func (r *recorder) waitLive(t *testing.T, want []string) {
	t.Helper()
	for deadline := time.Now().Add(5 * time.Second); time.Now().Before(deadline); time.Sleep(10 * time.Millisecond) {
		if slices.Equal(r.live(), want) {
			return
		}
	}
	t.Fatalf("announced on %v, want %v", r.live(), want)
}

func lanTailscaleScan() ([]listen.Iface, error) {
	return []listen.Iface{
		{Name: "wlan0", Up: true, Physical: true, Addrs: []netip.Prefix{netip.MustParsePrefix("192.168.1.5/24")}},
		{Name: "tailscale0", Up: true, Tun: true, Addrs: []netip.Prefix{netip.MustParsePrefix("100.74.61.96/32")}},
		{Name: "lo", Up: true, Loopback: true, Addrs: []netip.Prefix{netip.MustParsePrefix("127.0.0.1/8")}},
	}, nil
}

func TestDiscoveryAnnouncesLANOnlyAndStopsWithServer(t *testing.T) {
	rec := &recorder{}
	ts := startServer(t, lanTailscaleScan, "", func(s *server) { s.discovery.Start = rec.start })
	rec.waitLive(t, []string{"wlan0"})
	rec.mu.Lock()
	spec := rec.list[0].spec
	rec.mu.Unlock()
	wantTXT := []string{"v=1", "fp=" + discovery.FingerprintHint(ts.srv.cert.Fingerprint), "proto=1"}
	if !slices.Equal(spec.TXT, wantTXT) || spec.Port != ts.srv.cfg.Port {
		t.Fatalf("spec %+v, want TXT %v port %d", spec, wantTXT, ts.srv.cfg.Port)
	}
	ts.stop()
	if got := rec.live(); len(got) != 0 {
		t.Fatalf("still announcing after stop: %v", got)
	}
}

func TestDiscoveryOffByConfig(t *testing.T) {
	rec := &recorder{}
	ts := startServer(t, lanTailscaleScan, "", func(s *server) {
		s.discovery.Start = rec.start
		s.discovery.Enabled = false
	})
	for deadline := time.Now().Add(5 * time.Second); len(ts.srv.listen.Active()) < 2 && time.Now().Before(deadline); {
		time.Sleep(10 * time.Millisecond)
	}
	if len(ts.srv.listen.Active()) < 2 {
		t.Fatal("listeners never came up")
	}
	if got := rec.live(); len(got) != 0 {
		t.Fatalf("announced although disabled: %v", got)
	}
}
