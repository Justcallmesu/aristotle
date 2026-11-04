package discovery

import (
	"errors"
	"io"
	"log/slog"
	"net/netip"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/justcallmesu/aristotle/server/internal/listen"
)

type fakeResp struct {
	spec    Spec
	stopped bool
}

func (f *fakeResp) Stop() error { f.stopped = true; return nil }

type fake struct {
	mu      sync.Mutex
	started []*fakeResp
	fail    error
}

func (f *fake) start(s Spec) (Responder, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.fail != nil {
		return nil, f.fail
	}
	r := &fakeResp{spec: s}
	f.started = append(f.started, r)
	return r, nil
}

func (f *fake) live() []string {
	var out []string
	for _, r := range f.started {
		if !r.stopped {
			out = append(out, r.spec.Iface)
		}
	}
	slices.Sort(out)
	return out
}

func newAnn(f *fake, logs *strings.Builder) *Announcer {
	var w io.Writer = io.Discard
	if logs != nil {
		w = logs
	}
	return &Announcer{Enabled: true, Port: 8761, Fingerprint: "AB12 CD34 EF56 7890 1122 3344", Proto: 1,
		Hostname: "laptop.example.net", Start: f.start, Log: slog.New(slog.NewTextHandler(w, nil))}
}

func ep(kind listen.Kind, iface, addr string) listen.Endpoint {
	a := netip.MustParseAddr(addr)
	return listen.Endpoint{Kind: kind, Iface: iface, Addr: a, Subnet: netip.PrefixFrom(a, 24).Masked()}
}

func TestOnlyLANInterfacesAreAnnounced(t *testing.T) {
	f := &fake{}
	a := newAnn(f, nil)
	a.Sync([]listen.Endpoint{
		ep(listen.LAN, "wlan0", "192.168.1.5"),
		ep(listen.Tailscale, "tailscale0", "100.64.0.9"),
		ep(listen.LAN, "lo", "127.0.0.1"),
		ep(listen.LAN, "", "10.0.0.2"),
	})
	if got := f.live(); !slices.Equal(got, []string{"wlan0"}) {
		t.Fatalf("announced on %v, want only wlan0", got)
	}
	if s := f.started[0].spec; s.Port != 8761 || len(s.Addrs) != 1 || s.Addrs[0].String() != "192.168.1.5/24" {
		t.Fatalf("spec %+v", s)
	}
}

func TestTXTAndInstanceName(t *testing.T) {
	f := &fake{}
	a := newAnn(f, nil)
	a.Sync([]listen.Endpoint{ep(listen.LAN, "wlan0", "192.168.1.5")})
	s := f.started[0].spec
	want := []string{"v=1", "fp=ab12cd34ef567890", "proto=1"}
	if !slices.Equal(s.TXT, want) {
		t.Fatalf("TXT %v, want %v", s.TXT, want)
	}
	if s.Instance != "Aristotle on laptop" || s.Target != "aristotle-laptop.local." {
		t.Fatalf("instance %q target %q", s.Instance, s.Target)
	}
}

func TestFingerprintHint(t *testing.T) {
	if got := FingerprintHint("0A:1B 2c3D4e5F60718293 FFFF"); got != "0a1b2c3d4e5f6071" {
		t.Fatalf("got %q", got)
	}
	if got := FingerprintHint("AB"); got != "ab" {
		t.Fatalf("got %q", got)
	}
}

func TestStartStopFollowsListeners(t *testing.T) {
	f := &fake{}
	var logs strings.Builder
	a := newAnn(f, &logs)
	home := ep(listen.LAN, "wlan0", "192.168.1.5")
	office := ep(listen.LAN, "eth0", "10.1.1.5")
	a.Sync([]listen.Endpoint{home})
	a.Sync([]listen.Endpoint{home, office})
	if got := f.live(); !slices.Equal(got, []string{"eth0", "wlan0"}) || len(f.started) != 2 {
		t.Fatalf("live %v started %d (unchanged iface must not restart)", got, len(f.started))
	}
	a.Sync([]listen.Endpoint{office})
	if got := f.live(); !slices.Equal(got, []string{"eth0"}) {
		t.Fatalf("after wlan0 left: %v", got)
	}
	// Address change on the same interface restarts it with the new address.
	a.Sync([]listen.Endpoint{ep(listen.LAN, "eth0", "10.1.1.77")})
	last := f.started[len(f.started)-1]
	if got := f.live(); !slices.Equal(got, []string{"eth0"}) || last.spec.Addrs[0].Addr().String() != "10.1.1.77" {
		t.Fatalf("after address change: %v %+v", got, last.spec)
	}
	a.Sync(nil)
	if len(f.live()) != 0 || len(a.Active()) != 0 {
		t.Fatalf("still live: %v", f.live())
	}
	for _, w := range []string{"discovery started", "discovery stopped", "iface=wlan0", "iface=eth0"} {
		if !strings.Contains(logs.String(), w) {
			t.Errorf("log lacks %q:\n%s", w, logs.String())
		}
	}
}

func TestCloseStopsAll(t *testing.T) {
	f := &fake{}
	a := newAnn(f, nil)
	a.Sync([]listen.Endpoint{ep(listen.LAN, "wlan0", "192.168.1.5"), ep(listen.LAN, "eth0", "10.1.1.5")})
	a.Close()
	if len(f.live()) != 0 {
		t.Fatalf("live %v", f.live())
	}
}

func TestDisabledAnnouncesNothing(t *testing.T) {
	f := &fake{}
	a := newAnn(f, nil)
	a.Enabled = false
	a.Sync([]listen.Endpoint{ep(listen.LAN, "wlan0", "192.168.1.5")})
	if len(f.started) != 0 {
		t.Fatal("announced although disabled")
	}
}

// clock is a settable time source.
type clock struct{ t time.Time }

func (c *clock) now() time.Time          { return c.t }
func (c *clock) advance(d time.Duration) { c.t = c.t.Add(d) }

func TestStartFailureRetriesWithBackoffAndWarnsOnce(t *testing.T) {
	f := &fake{fail: errors.New("bind: address in use")}
	var logs strings.Builder
	a := newAnn(f, &logs)
	c := &clock{t: time.Unix(1_700_000_000, 0)}
	a.Now = c.now
	attempts := 0
	start := a.Start
	a.Start = func(s Spec) (Responder, error) { attempts++; return start(s) }
	e := []listen.Endpoint{ep(listen.LAN, "wlan0", "192.168.1.5")}
	// Scans every 10 s from 0 to 610 s: attempts at 0, 10, 30, 70, 150, 310, 610 s.
	for range 62 {
		a.Sync(e)
		c.advance(10 * time.Second)
	}
	if attempts != 7 {
		t.Fatalf("%d attempts in 610 s, want 7 (backoff 10 s doubling to 5 min)", attempts)
	}
	if n := strings.Count(logs.String(), "level=WARN"); n != 1 {
		t.Fatalf("%d warnings for one repeated error, want 1:\n%s", n, logs.String())
	}
	if len(a.Active()) != 0 {
		t.Fatal("failed start counted as active")
	}
	f.fail = nil
	c.advance(retryMax)
	a.Sync(e)
	if got := f.live(); !slices.Equal(got, []string{"wlan0"}) {
		t.Fatalf("live %v after recovery", got)
	}
}

func TestChangedErrorWarnsAgain(t *testing.T) {
	f := &fake{fail: errors.New("first")}
	var logs strings.Builder
	a := newAnn(f, &logs)
	c := &clock{t: time.Unix(1_700_000_000, 0)}
	a.Now = c.now
	e := []listen.Endpoint{ep(listen.LAN, "wlan0", "192.168.1.5")}
	a.Sync(e)
	f.fail = errors.New("second")
	c.advance(retryFirst)
	a.Sync(e)
	if n := strings.Count(logs.String(), "level=WARN"); n != 2 {
		t.Fatalf("%d warnings, want 2:\n%s", n, logs.String())
	}
}

func TestBackoffDelays(t *testing.T) {
	var b backoff
	now := time.Unix(0, 0)
	var got []time.Duration
	for range 8 {
		d, _ := b.failed(now, errors.New("x"))
		got = append(got, d)
	}
	s := time.Second
	want := []time.Duration{10 * s, 20 * s, 40 * s, 80 * s, 160 * s, 300 * s, 300 * s, 300 * s}
	if !slices.Equal(got, want) {
		t.Fatalf("delays %v, want %v", got, want)
	}
	if b.due(now.Add(299*s)) || !b.due(now.Add(300*s)) {
		t.Fatal("due() ignores next")
	}
}

// dyingResp is a responder whose socket failed after start.
type dyingResp struct {
	fakeResp
	err error
}

func (d *dyingResp) Err() error { return d.err }

func TestDeadResponderIsRestarted(t *testing.T) {
	var started []*dyingResp
	a := newAnn(&fake{}, nil)
	c := &clock{t: time.Unix(1_700_000_000, 0)}
	a.Now = c.now
	a.Start = func(s Spec) (Responder, error) {
		r := &dyingResp{fakeResp: fakeResp{spec: s}}
		started = append(started, r)
		return r, nil
	}
	e := []listen.Endpoint{ep(listen.LAN, "wlan0", "192.168.1.5")}
	a.Sync(e)
	started[0].err = errors.New("read: network is down")
	a.Sync(e)
	if !started[0].stopped || len(a.Active()) != 0 {
		t.Fatal("dead responder not stopped")
	}
	c.advance(retryFirst)
	a.Sync(e)
	if len(started) != 2 || len(a.Active()) != 1 {
		t.Fatalf("not restarted after backoff: %d starts", len(started))
	}
}
