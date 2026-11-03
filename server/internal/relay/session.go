package relay

import (
	"context"
	"log/slog"
	"sync"
	"time"

	"github.com/coder/websocket"

	"github.com/justcallmesu/aristotle/server/internal/hermes"
)

// session is one phone socket and its Hermes socket.
//
// Two contexts: ioCtx is passed to every Read/Write on both sockets and cancelling it (kill) cuts
// them (coder/websocket closes a conn whose call context ends); stopCtx only signals that a close was
// requested, so the close handshake can still run on intact sockets.
type session struct {
	hub      *Hub
	pc       *websocket.Conn
	dev      string
	listener string
	log      *slog.Logger

	ioCtx   context.Context
	kill    context.CancelFunc
	stopCtx context.Context
	stop    context.CancelFunc

	closeMu sync.Mutex
	why     closeReason // first requested close wins
	by      string      // who ended it: phone, hermes, server
	set     bool

	in, out traffic // in: phone->Hermes (owned by pumpPhone), out: Hermes->phone (owned by pumpHermes)
	dropped int64   // Hermes frames that could not be redacted (owned by pumpHermes)
}

func newSession(h *Hub, pc *websocket.Conn, dev, listener string) *session {
	s := &session{hub: h, pc: pc, dev: dev, listener: listener, log: h.opts.Log}
	s.ioCtx, s.kill = context.WithCancel(context.Background())
	s.stopCtx, s.stop = context.WithCancel(context.Background())
	return s
}

// requestClose records the close to send to the phone (the first request wins) and starts teardown.
func (s *session) requestClose(why closeReason) { s.requestCloseBy("server", why) }

func (s *session) requestCloseBy(by string, why closeReason) {
	s.closeMu.Lock()
	if !s.set {
		s.why, s.by, s.set = why, by, true
	}
	s.closeMu.Unlock()
	s.stop()
}

func (s *session) closeState() (closeReason, string) {
	s.closeMu.Lock()
	defer s.closeMu.Unlock()
	return s.why, s.by
}

// run dials Hermes, pumps both directions until one side ends or a close is requested, then closes
// both sockets and waits for every goroutine it started.
func (s *session) run() {
	start := s.hub.opts.Now()
	s.log.Info("relay open", "device_id", s.dev, "listener", s.listener)
	defer func() { s.logClose(start) }()
	defer s.kill()

	hc, err := s.dial()
	if err != nil {
		if s.stopCtx.Err() == nil { // not already closing for revoke/shutdown
			s.log.Warn("relay hermes dial failed", "device_id", s.dev, "err", err)
			s.requestCloseBy("hermes", dialClose(err))
		}
		s.teardown(nil)
		return
	}
	var wg sync.WaitGroup
	wg.Go(func() { s.pumpPhone(hc) })
	wg.Go(func() { s.pumpHermes(hc) })
	wg.Go(s.keepalive)
	<-s.stopCtx.Done()
	s.teardown(hc)
	wg.Wait()
}

func (s *session) dial() (*hermes.Conn, error) {
	ctx, cancel := context.WithTimeout(s.stopCtx, s.hub.opts.DialTimeout)
	defer cancel()
	return s.hub.opts.Dialer.Dial(ctx)
}

// pumpPhone forwards phone frames to Hermes untouched, enforcing the text-only and rate limits. It
// returns only when the phone read fails: once a close is requested it keeps reading and discarding,
// so pongs and the phone's close frame are still processed and kill can always unblock it.
func (s *session) pumpPhone(hc *hermes.Conn) {
	rate := newWindow(s.hub.opts.MaxFramesPerMinute, time.Minute)
	for {
		typ, frame, err := s.pc.Read(s.ioCtx)
		if err != nil {
			s.requestCloseBy("phone", phoneClose(err))
			return
		}
		if s.stopCtx.Err() != nil {
			continue // closing: nothing more reaches Hermes
		}
		switch {
		case typ != websocket.MessageText:
			s.requestClose(closeReason{CodeTextOnly, "text_frames_only"})
		case !rate.allow(s.hub.opts.Now()):
			s.requestClose(closeReason{CodeRateLimited, "rate_limited"})
		default:
			s.in.add(frame)
			s.in.addMethod(frame)
			if err := hc.Write(s.ioCtx, frame); err != nil {
				s.requestCloseBy("hermes", hermesClose(err))
			}
		}
	}
}

// pumpHermes forwards Hermes frames to the phone, redacted (docs/protocol.md §10). Like pumpPhone it
// returns only when its read fails and discards frames once a close is requested.
func (s *session) pumpHermes(hc *hermes.Conn) {
	for {
		frame, err := hc.Read(s.ioCtx)
		if err != nil {
			s.requestCloseBy("hermes", hermesClose(err))
			return
		}
		if s.stopCtx.Err() != nil {
			continue
		}
		out, err := Redact(frame)
		if err != nil {
			if s.dropped == 0 {
				s.log.Warn("relay dropped a hermes frame it could not redact", "device_id", s.dev)
			}
			s.dropped++
			continue
		}
		if err := s.pc.Write(s.ioCtx, websocket.MessageText, out); err != nil {
			s.requestCloseBy("phone", phoneClose(err))
			continue
		}
		s.out.add(out)
	}
}

// keepalive pings the phone every PingInterval and closes 1011 when a pong is late. Pongs are read by
// pumpPhone's Read. The ping context is not stopCtx: ending a context mid-write would cut the socket.
func (s *session) keepalive() {
	t := time.NewTicker(s.hub.opts.PingInterval)
	defer t.Stop()
	for {
		select {
		case <-s.stopCtx.Done():
			return
		case <-t.C:
		}
		ctx, cancel := context.WithTimeout(context.Background(), s.hub.opts.PongTimeout)
		err := s.pc.Ping(ctx)
		cancel()
		if err != nil {
			s.requestClose(closeReason{CodePongTimeout, "pong_timeout"})
			return
		}
	}
}

// teardown closes the phone with the recorded code and Hermes normally, both at once. After
// closeGrace the sockets are cut so a stalled peer cannot hold the relay open.
func (s *session) teardown(hc *hermes.Conn) {
	why, _ := s.closeState()
	done := make(chan struct{})
	go func() {
		defer close(done)
		var wg sync.WaitGroup
		wg.Go(func() { s.closePhone(why) })
		if hc != nil {
			wg.Go(func() { _ = hc.Close() })
		}
		wg.Wait()
	}()
	t := time.NewTimer(closeGrace)
	defer t.Stop()
	select {
	case <-done:
	case <-t.C:
	}
	s.kill() // cuts both sockets if a handshake is still running; the pumps' reads then fail at once
	<-done
}

func (s *session) closePhone(why closeReason) {
	if !sendable(why.code) {
		_ = s.pc.CloseNow()
		return
	}
	_ = s.pc.Close(why.code, why.reason)
}

// logClose writes the audit line: counts and method names only, never frame contents.
func (s *session) logClose(start time.Time) {
	why, by := s.closeState()
	s.log.Info("relay closed",
		"device_id", s.dev,
		"listener", s.listener,
		"duration", s.hub.opts.Now().Sub(start).Round(time.Millisecond).String(),
		"close_code", int(why.code),
		"closed_by", by,
		"frames_in", s.in.frames,
		"frames_out", s.out.frames,
		"bytes_in", s.in.bytes,
		"bytes_out", s.out.bytes,
		"dropped", s.dropped,
		"methods", methodSummary(s.in.methods),
	)
}
