package discovery

import (
	"errors"
	"io"
	"log/slog"
	"net"
	"net/netip"
	"sync"
	"testing"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

type sentPkt struct {
	to  netip.AddrPort
	msg dnsmessage.Message
}

// fakeConn feeds queued packets to the responder and records what it sends.
type fakeConn struct {
	in     chan inbound
	closed chan struct{}
	once   sync.Once
	fail   error // returned by read instead of a packet when set before start

	mu   sync.Mutex
	sent []sentPkt
}

func newFakeConn() *fakeConn {
	return &fakeConn{in: make(chan inbound, 8), closed: make(chan struct{})}
}

func (f *fakeConn) read(b []byte) (inbound, error) {
	if f.fail != nil {
		return inbound{}, f.fail
	}
	select {
	case p := <-f.in:
		return p, nil
	case <-f.closed:
		return inbound{}, net.ErrClosed
	}
}

func (f *fakeConn) send(b []byte, to netip.AddrPort) error {
	var m dnsmessage.Message
	if err := m.Unpack(b); err != nil {
		return err
	}
	f.mu.Lock()
	f.sent = append(f.sent, sentPkt{to, m})
	f.mu.Unlock()
	return nil
}

func (f *fakeConn) Close() error { f.once.Do(func() { close(f.closed) }); return nil }

func (f *fakeConn) packets() []sentPkt {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]sentPkt(nil), f.sent...)
}

func (f *fakeConn) waitSent(t *testing.T, n int) []sentPkt {
	t.Helper()
	for deadline := time.Now().Add(3 * time.Second); time.Now().Before(deadline); time.Sleep(5 * time.Millisecond) {
		if p := f.packets(); len(p) >= n {
			return p
		}
	}
	t.Fatalf("sent %d packets, want %d", len(f.packets()), n)
	return nil
}

func quiet() *slog.Logger { return slog.New(slog.NewTextHandler(io.Discard, nil)) }

func newTestResponder(t *testing.T, c conn, now func() time.Time, gap time.Duration) *responder {
	t.Helper()
	r := newResponder(testZone(t), wlan, c, quiet())
	r.now, r.gap = now, gap
	r.run()
	return r
}

func TestAnnouncesTwiceThenGoodbyeOnStop(t *testing.T) {
	c := newFakeConn()
	r := newTestResponder(t, c, time.Now, 20*time.Millisecond)
	sent := c.waitSent(t, 2)
	if err := r.Stop(); err != nil {
		t.Fatal(err)
	}
	sent = c.packets()
	if len(sent) != 3 {
		t.Fatalf("sent %d packets, want 2 announcements + 1 goodbye", len(sent))
	}
	group := netip.MustParseAddrPort("224.0.0.251:5353")
	for i, p := range sent {
		if p.to != group || !p.msg.Header.Response || len(p.msg.Answers) != 4 {
			t.Fatalf("packet %d: to %s %+v", i, p.to, p.msg.Header)
		}
		goodbye := i == 2
		for _, rec := range p.msg.Answers {
			if (rec.Header.TTL == 0) != goodbye {
				t.Fatalf("packet %d record %s TTL %d (goodbye %v)", i, rec.Header.Type, rec.Header.TTL, goodbye)
			}
		}
	}
}

func TestStopDuringAnnounceStillSaysGoodbyeLast(t *testing.T) {
	c := newFakeConn()
	r := newTestResponder(t, c, time.Now, time.Hour) // second announcement never due
	c.waitSent(t, 1)
	if err := r.Stop(); err != nil {
		t.Fatal(err)
	}
	sent := c.packets()
	if last := sent[len(sent)-1]; last.msg.Answers[0].Header.TTL != 0 {
		t.Fatal("last packet is not the goodbye")
	}
}

func TestMulticastAnswersAreRateLimitedPerRecord(t *testing.T) {
	c := newFakeConn()
	var mu sync.Mutex
	now := time.Unix(1_700_000_000, 0)
	clock := func() time.Time { mu.Lock(); defer mu.Unlock(); return now }
	r := newTestResponder(t, c, clock, 20*time.Millisecond)
	defer r.Stop()
	c.waitSent(t, 2) // the announcements mark every record as just sent
	ask := func(data []byte) { c.in <- pkt(wlanIndex, "192.168.1.20:5353", "224.0.0.251", data) }

	ask(ptrQuery(t))
	ask(query(t, 0, true, q(svcName, dnsmessage.TypePTR))) // QU: unicast, not limited
	sent := c.waitSent(t, 3)
	if len(sent) != 3 || sent[2].to.String() != "192.168.1.20:5353" {
		t.Fatalf("within 1 s of the announcement a multicast answer went out: %d packets", len(sent))
	}

	mu.Lock()
	now = now.Add(rateWindow)
	mu.Unlock()
	ask(ptrQuery(t))
	ask(ptrQuery(t))
	ask(query(t, 0, false, q(hostName, dnsmessage.TypeA))) // A was last multicast with the announcements: allowed
	time.Sleep(100 * time.Millisecond)
	sent = c.packets()
	if len(sent) != 5 {
		t.Fatalf("sent %d packets, want 5 (one PTR answer per second, one A answer)", len(sent))
	}
	if a := sent[3].msg.Answers; len(a) != 1 || a[0].Header.Type != dnsmessage.TypePTR || sent[3].to.Port() != 5353 {
		t.Fatalf("PTR answer %+v", a)
	}
}

func TestReadFailureSurfaces(t *testing.T) {
	c := newFakeConn()
	c.fail = errors.New("network is down")
	r := newTestResponder(t, c, time.Now, 20*time.Millisecond)
	for deadline := time.Now().Add(3 * time.Second); r.Err() == nil && time.Now().Before(deadline); {
		time.Sleep(5 * time.Millisecond)
	}
	if r.Err() == nil {
		t.Fatal("read failure not reported")
	}
	_ = r.Stop()
}
