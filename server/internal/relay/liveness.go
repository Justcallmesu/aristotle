package relay

import (
	"bufio"
	"crypto/tls"
	"net"
	"net/http"
	"time"

	"github.com/coder/websocket"
)

// Liveness of the phone socket (docs/protocol.md §12): fragmented writes, a fixed ping cadence and a
// progress rule that counts only what the phone itself did.

// drainWait is how long one write to the phone's connection must have waited before its completion
// counts as the phone draining. Go's net.Conn does not say whether a write blocked, so the wait is
// measured. A write the kernel takes into free send-buffer space returns in microseconds and proves
// nothing: the phone may be gone. A write that found the buffer full returns only after the phone's
// TCP took a large part of that buffer (the kernel wakes a blocked writer only then), which on a phone
// slow enough for its pong to be late takes far longer than this (~0.14 s per wake at 2 MiB/s through
// a 128 KiB buffer in TestRelaySlowPhoneKeptByDrainWhilePongsAreLate). A scheduling stall that makes a
// free write look slow costs at most one extra ping round for a dead phone.
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
// to wait for it completed (drainWait). A slow phone downloading a large message keeps its socket
// while those writes keep completing, even when its pong waits behind the data. Once the last bytes
// are in the kernel nothing waits any more: the phone must then read what is buffered for it and
// answer a ping within PongTimeout. While a phone frame waits for Hermes the phone is not read, so a
// missing pong is not held against it then.
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

func (s *session) now() int64 { return s.hub.opts.Now().UnixNano() }

// hijacker hands websocket.Accept the phone's connection wrapped so that its reads and waiting writes
// move the liveness clocks, and keeps the TCP connection for kill.
type hijacker struct {
	http.ResponseWriter
	s *session
}

func (w hijacker) Hijack() (net.Conn, *bufio.ReadWriter, error) {
	c, brw, err := http.NewResponseController(w.ResponseWriter).Hijack()
	if err != nil {
		return nil, nil, err
	}
	w.s.raw = c
	if tc, ok := c.(*tls.Conn); ok {
		w.s.raw = tc.NetConn() // closing under TLS does not wait to send close_notify to a stalled phone
	}
	pc := &progressConn{Conn: c, s: w.s}
	// coder/websocket reads through pc (accept.go resets brw.Reader onto it) but writes through
	// brw.Writer, which net/http points at c itself. It is empty here (net/http flushed the 101).
	if err := brw.Writer.Flush(); err != nil {
		_ = c.Close()
		return nil, nil, err
	}
	brw.Writer.Reset(pc)
	return pc, brw, nil
}

// progressConn is the phone's connection. Bytes read move lastRead: a phone slowly uploading a large
// frame (its pong waiting behind it) still shows progress. A write that waited at least drainWait
// moves lastDrain when it completes.
type progressConn struct {
	net.Conn
	s *session
}

func (c *progressConn) Read(p []byte) (int, error) {
	n, err := c.Conn.Read(p)
	if n > 0 {
		c.s.lastRead.Store(c.s.now())
	}
	return n, err
}

func (c *progressConn) Write(p []byte) (int, error) {
	start := time.Now()
	n, err := c.Conn.Write(p)
	if n > 0 && time.Since(start) >= drainWait {
		c.s.lastDrain.Store(c.s.now())
	}
	return n, err
}
