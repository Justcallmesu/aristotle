package discovery

import (
	"errors"
	"log/slog"
	"net"
	"net/netip"
	"sync"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

// announceGap is the delay between the two start-up announcements (RFC 6762 §8.3).
const announceGap = time.Second

// rateWindow is the minimum time between two multicast answers of the same
// record set on one interface (RFC 6762 §6).
const rateWindow = time.Second

// conn is one interface's mDNS socket.
type conn interface {
	read(b []byte) (inbound, error)
	send(b []byte, to netip.AddrPort) error
	Close() error
}

// responder answers on one interface and announces/withdraws the zone.
type responder struct {
	zone *zone
	link link
	conn conn
	log  *slog.Logger
	now  func() time.Time
	gap  time.Duration

	stop      chan struct{}
	announced sync.WaitGroup // the start-up announcements
	served    sync.WaitGroup // the read loop

	mu   sync.Mutex
	last map[key]time.Time // last multicast send per record set
	err  error             // read loop failure
}

func newResponder(z *zone, l link, c conn, log *slog.Logger) *responder {
	return &responder{zone: z, link: l, conn: c, log: log, now: time.Now, gap: announceGap,
		stop: make(chan struct{}), last: map[key]time.Time{}}
}

func (r *responder) run() {
	r.announced.Add(1)
	r.served.Add(1)
	go r.announce()
	go r.serve()
}

// announce sends the record set twice, gap apart.
func (r *responder) announce() {
	defer r.announced.Done()
	for i := range 2 {
		if i > 0 {
			select {
			case <-r.stop:
				return
			case <-time.After(r.gap):
			}
		}
		r.sendAll(false)
	}
}

func (r *responder) sendAll(goodbye bool) {
	recs := r.zone.all(goodbye)
	b, err := announcement(recs)
	if err == nil {
		r.mu.Lock()
		r.mark(recs, r.now())
		r.mu.Unlock()
		err = r.conn.send(b, netip.AddrPortFrom(mdnsGroup, mdnsPort))
	}
	switch {
	case err == nil:
	case goodbye: // the interface is often already gone when we stop
		r.log.Debug("discovery goodbye failed", "err", err)
	default:
		r.log.Warn("discovery announce failed", "err", err)
	}
}

func (r *responder) serve() {
	defer r.served.Done()
	buf := make([]byte, maxPacket+1) // one spare byte so decide can see an oversized datagram
	for {
		in, err := r.conn.read(buf)
		if err != nil {
			select {
			case <-r.stop:
			default:
				r.mu.Lock()
				r.err = err
				r.mu.Unlock()
				r.log.Warn("discovery socket failed", "err", err)
			}
			return
		}
		r.handle(in)
	}
}

func (r *responder) handle(in inbound) {
	p := r.zone.decide(r.link, in)
	if p.mode == drop {
		return
	}
	if p.mode == multicast {
		r.mu.Lock()
		p.answers = r.limit(p.answers, r.now())
		r.mu.Unlock()
		if len(p.answers) == 0 {
			return
		}
	}
	b, err := p.pack()
	if err == nil {
		err = r.conn.send(b, p.to)
	}
	if err != nil {
		r.log.Debug("discovery reply failed", "err", err)
	}
}

// limit keeps the records whose set was not multicast within rateWindow and marks them sent.
func (r *responder) limit(rs []dnsmessage.Resource, now time.Time) []dnsmessage.Resource {
	var out []dnsmessage.Resource
	for _, rec := range rs {
		if t, ok := r.last[keyOf(rec)]; ok && now.Sub(t) < rateWindow {
			continue
		}
		out = append(out, rec)
	}
	r.mark(out, now)
	return out
}

func (r *responder) mark(rs []dnsmessage.Resource, now time.Time) {
	for _, rec := range rs {
		r.last[keyOf(rec)] = now
	}
}

// Stop withdraws the records (goodbye, TTL 0, after any pending announcement)
// and closes the socket.
func (r *responder) Stop() error {
	close(r.stop)
	r.announced.Wait()
	r.sendAll(true)
	err := r.conn.Close()
	r.served.Wait()
	if errors.Is(err, net.ErrClosed) {
		err = nil
	}
	return err
}

// Err is the socket failure that ended the responder, or nil while it runs.
func (r *responder) Err() error {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.err
}
