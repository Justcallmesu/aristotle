package relay

import (
	"net"
	"net/http"
	"sync/atomic"
	"time"
)

// PhoneConn is a phone's WebSocket connection as the liveness rule sees it (docs/protocol.md §12
// Keepalive): when bytes last arrived from the phone, and when a write that had to wait for the phone
// last got bytes out. Each relay session embeds one; the terminal socket (package terminal, §14)
// uses the same rule through the exported methods.
type PhoneConn struct {
	clock     func() time.Time
	raw       net.Conn // the phone's TCP connection (a trackedConn behind WrapListener), set at hijack
	lastRead  atomic.Int64
	lastDrain atomic.Int64
}

// NewPhoneConn returns a tracker whose clocks read now (time.Now when nil).
func NewPhoneConn(now func() time.Time) *PhoneConn {
	if now == nil {
		now = time.Now
	}
	return &PhoneConn{clock: now}
}

// Hijacker wraps w for websocket.Accept: the hijacked connection then moves this tracker's clocks,
// gets TCP_NOTSENT_LOWAT, and can be cut with Cut.
func (p *PhoneConn) Hijacker(w http.ResponseWriter) http.ResponseWriter { return hijacker{w, p} }

// DeadSince reports that since t no byte arrived from the phone and no write that had to wait for it
// got bytes out.
func (p *PhoneConn) DeadSince(t time.Time) bool {
	sent := t.UnixNano()
	return p.lastRead.Load() < sent && p.lastDrain.Load() < sent
}

// Cut closes the phone's TCP connection at once (under TLS too), ending any read, write or close
// handshake still waiting for the phone.
func (p *PhoneConn) Cut() {
	if p.raw != nil {
		_ = p.raw.Close()
	}
}

func (p *PhoneConn) now() int64 { return p.clock().UnixNano() }

// drained records that a write which had to wait for the phone got bytes out.
func (p *PhoneConn) drained() { p.lastDrain.Store(p.now()) }
