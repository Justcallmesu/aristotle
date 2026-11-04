package relay

import (
	"bufio"
	"net"
	"net/http"
	"time"

	"github.com/coder/websocket"
)

// Liveness of the phone socket (docs/protocol.md §12): fragmented writes, a fixed ping cadence and a
// progress rule that counts only what the phone itself did.

// drainWait is the fallback for a phone connection the relay cannot watch exactly (non-Unix, or a
// listener not passed through WrapListener): a write to it that waited at least this long counts as
// the phone draining when it completes. A write the kernel takes into free send-buffer space returns
// in microseconds and proves nothing; but a scheduling stall can make such a write look slow, which
// costs a dead phone one extra ping round. Watched connections (sock_unix.go) need no threshold: they
// count a write only when the kernel refused bytes (EAGAIN) and later took them.
const drainWait = 10 * time.Millisecond

// maxPings bounds the pings waiting for a pong. Pongs go unread while a phone frame waits for Hermes
// and queue behind data on a slow phone; neither should pile up goroutines.
const maxPings = 4

// writePhone writes one message to the phone: in one frame when it is small, else in fragments of
// fragmentBytes, so pings and close frames are written between them.
func (s *session) writePhone(msg []byte) error {
	if len(msg) <= fragmentBytes {
		return s.pc.Write(s.ioCtx, websocket.MessageText, msg)
	}
	w, err := s.pc.Writer(s.ioCtx, websocket.MessageText)
	if err != nil {
		return err
	}
	for len(msg) > 0 {
		n := min(len(msg), fragmentBytes)
		if _, err := w.Write(msg[:n]); err != nil {
			return err
		}
		msg = msg[n:]
	}
	return w.Close()
}

// keepalive sends a ping every PingInterval, whatever the traffic, and closes the phone 1011 when,
// within PongTimeout of the ping, the phone sent no byte (pong or frame) and no write to it that had
// to wait for it got bytes out (sock_unix.go; drainWait where that cannot be seen). A slow phone
// downloading a large message keeps its socket while those writes keep completing, even when its pong
// waits behind the data. Once the last bytes are in the kernel nothing waits any more: the phone must
// then read what is buffered for it and answer a ping within PongTimeout. While a phone frame waits
// for Hermes the phone is not read, so a missing pong is not held against it then.
func (s *session) keepalive() {
	t := time.NewTicker(s.hub.opts.PingInterval)
	defer t.Stop()
	for {
		select {
		case <-s.stopCtx.Done():
			return
		case <-t.C:
		}
		sent := s.now()
		s.ping()
		if !s.sleep(s.hub.opts.PongTimeout) {
			return
		}
		if s.deadSince(sent) {
			s.requestClose(closeReason{CodePongTimeout, "pong_timeout"})
			return
		}
	}
}

// ping sends a ping in the background unless maxPings are already waiting for their pongs. Each one
// waits on ioCtx, which ends only when the sockets are cut: coder/websocket closes a connection whose
// write context ends mid-write, so a ping deadline would cut a slow phone whose ping frame is still
// waiting for buffer space. A ping ends on its pong (pumpPhone reads it), on close or on kill.
func (s *session) ping() {
	if s.pingsOut.Add(1) > maxPings {
		s.pingsOut.Add(-1)
		return
	}
	s.pings.Go(func() {
		defer s.pingsOut.Add(-1)
		_ = s.pc.Ping(s.ioCtx)
	})
}

// deadSince: since sent, nothing arrived from the phone, no waiting write to it completed, and it was
// read throughout. forwarding is loaded before lastForwarded, which forward stores first.
func (s *session) deadSince(sent int64) bool {
	return s.lastRead.Load() < sent && s.lastDrain.Load() < sent &&
		!s.forwarding.Load() && s.lastForwarded.Load() < sent
}

// sleep waits d and reports false when a close was requested first.
func (s *session) sleep(d time.Duration) bool {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-s.stopCtx.Done():
		return false
	case <-t.C:
		return true
	}
}

// hijacker hands websocket.Accept the phone's connection wrapped so that its reads and waiting writes
// move the liveness clocks, and keeps the TCP connection for Cut.
type hijacker struct {
	http.ResponseWriter
	p *PhoneConn
}

func (w hijacker) Hijack() (net.Conn, *bufio.ReadWriter, error) {
	c, brw, err := http.NewResponseController(w.ResponseWriter).Hijack()
	if err != nil {
		return nil, nil, err
	}
	w.p.raw = transportOf(c) // closing it under TLS does not wait to send close_notify to a stalled phone
	setNotSentLowat(w.p.raw)
	pc := &progressConn{Conn: c, p: w.p, timed: true}
	// Under TLS the records reach the TCP connection through tls.Conn, below pc: only the listener's
	// own wrapper (WrapListener) sees whether those writes waited.
	if d, ok := w.p.raw.(drainNotifier); ok {
		d.notifyDrain(w.p.drained)
		pc.timed = false
	}
	// coder/websocket reads through pc (accept.go resets brw.Reader onto it) but writes through
	// brw.Writer, which net/http points at c itself. It is empty here (net/http flushed the 101).
	if err := brw.Writer.Flush(); err != nil {
		_ = c.Close()
		return nil, nil, err
	}
	brw.Writer.Reset(pc)
	return pc, brw, nil
}

// transportOf returns the connection under every layer that exposes one (tls.Conn.NetConn on the LAN and
// Tailscale listeners, and any listener wrapper above WrapListener): the accepted TCP connection, which is
// the WrapListener wrapper when there is one. It stops after maxConnLayers, so a layer that returns itself
// (or a cycle) cannot hang the hijack.
func transportOf(c net.Conn) net.Conn {
	for range maxConnLayers {
		layer, ok := c.(interface{ NetConn() net.Conn })
		if !ok {
			return c
		}
		next := layer.NetConn()
		if next == nil {
			return c
		}
		c = next
	}
	return c
}

// maxConnLayers bounds transportOf; production has two (tls.Conn over the WrapListener wrapper).
const maxConnLayers = 8

// progressConn is the phone's connection. Bytes read move lastRead: a phone slowly uploading a large
// frame (its pong waiting behind it) still shows progress. When timed (the connection underneath
// cannot report waits), a write that took at least drainWait moves lastDrain when it completes.
type progressConn struct {
	net.Conn
	p     *PhoneConn
	timed bool
}

func (c *progressConn) Read(b []byte) (int, error) {
	n, err := c.Conn.Read(b)
	if n > 0 {
		c.p.lastRead.Store(c.p.now())
	}
	return n, err
}

func (c *progressConn) Write(b []byte) (int, error) {
	if !c.timed {
		return c.Conn.Write(b)
	}
	start := time.Now()
	n, err := c.Conn.Write(b)
	if n > 0 && time.Since(start) >= drainWait {
		c.p.drained()
	}
	return n, err
}
