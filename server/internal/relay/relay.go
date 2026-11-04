// Package relay bridges a paired phone's WebSocket (/v1/ws) to its own Hermes /api/ws socket. Frames
// pass through unchanged in both directions, except the Q34 redaction on the way to the phone
// (redact.go). Wire facts: docs/protocol.md §10, §12.
package relay

import (
	"context"
	"log/slog"
	"net/http"
	"sync"
	"time"

	"github.com/coder/websocket"

	"github.com/justcallmesu/aristotle/server/internal/devices"
	"github.com/justcallmesu/aristotle/server/internal/hermes"
)

// Defaults for Options (docs/protocol.md §12).
const (
	DefaultMaxFrameBytes      = 1 << 20
	DefaultMaxFramesPerMinute = 600
	DefaultMaxPerDevice       = 4
	DefaultPingInterval       = 25 * time.Second
	DefaultPongTimeout        = 15 * time.Second
	DefaultDialTimeout        = 20 * time.Second
	// closeGrace bounds the close handshakes; after it both sockets are cut.
	closeGrace = 2 * time.Second
	// revokeGrace is closeGrace for a revoke: a phone that has not taken its 4401 by then is cut, so
	// every socket of a revoked device is gone within 1 s even when the phone is not reading.
	revokeGrace = 500 * time.Millisecond
	// fragmentBytes is the largest WebSocket frame written to the phone. Larger messages go out as
	// fragments, so pings and close frames are written between them instead of after the whole message.
	fragmentBytes = 32 << 10
)

// Dialer opens one fresh Hermes socket; *hermes.Connector is the real one.
type Dialer interface {
	Dial(ctx context.Context) (*hermes.Conn, error)
}

// Options configures a Hub. Zero limits take the defaults.
type Options struct {
	Dialer  Dialer
	Devices *devices.Store // revocations close the device's relays
	Log     *slog.Logger

	MaxFrameBytes      int64
	MaxFramesPerMinute int
	MaxPerDevice       int
	PingInterval       time.Duration
	PongTimeout        time.Duration
	DialTimeout        time.Duration
	Now                func() time.Time

	wrapHermes func(*hermes.Conn) upstream // tests: changes what the Hermes side does (e.g. streams events)
}

func (o *Options) setDefaults() {
	if o.MaxFrameBytes <= 0 {
		o.MaxFrameBytes = DefaultMaxFrameBytes
	}
	if o.MaxFramesPerMinute <= 0 {
		o.MaxFramesPerMinute = DefaultMaxFramesPerMinute
	}
	if o.MaxPerDevice <= 0 {
		o.MaxPerDevice = DefaultMaxPerDevice
	}
	if o.PingInterval <= 0 {
		o.PingInterval = DefaultPingInterval
	}
	if o.PongTimeout <= 0 {
		o.PongTimeout = DefaultPongTimeout
	}
	if o.DialTimeout <= 0 {
		o.DialTimeout = DefaultDialTimeout
	}
	if o.Now == nil {
		o.Now = time.Now
	}
}

// Hub owns every live relay: the per-device cap, revocation and shutdown.
type Hub struct {
	opts Options

	mu     sync.Mutex
	byDev  map[string]map[*session]struct{}
	closed bool
	wg     sync.WaitGroup // one per registered relay
}

// New returns a Hub and subscribes it to the store's revocations.
func New(opts Options) *Hub {
	opts.setDefaults()
	h := &Hub{opts: opts, byDev: map[string]map[*session]struct{}{}}
	opts.Devices.OnRevoke(h.revoked)
	return h
}

// ServeRelay upgrades an authenticated request and relays until either side ends. listener is the
// listener kind (for the audit line). It returns when the relay is over.
func (h *Hub) ServeRelay(w http.ResponseWriter, r *http.Request, deviceID, listener string) {
	s := newSession(h, deviceID, listener)
	pc, err := websocket.Accept(s.Hijacker(w), r, &websocket.AcceptOptions{CompressionMode: websocket.CompressionDisabled})
	if err != nil {
		return // Accept has answered with an HTTP error
	}
	pc.SetReadLimit(h.opts.MaxFrameBytes)
	s.pc = pc
	if refusal, ok := h.register(s); !ok {
		h.opts.Log.Info("relay refused", "device_id", deviceID, "listener", listener, "close_code", int(refusal.code))
		_ = pc.Close(refusal.code, refusal.reason)
		return
	}
	defer h.unregister(s)
	// A revoke between authentication and register would have found no relay to close.
	if !h.opts.Devices.Has(deviceID) {
		s.requestClose(closeReason{CodeRevoked, "revoked"})
	}
	s.run()
}

func (h *Hub) register(s *session) (closeReason, bool) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.closed {
		return closeReason{CodeGoingAway, "going_away"}, false
	}
	set := h.byDev[s.dev]
	// A newer socket on the other listener retires the device's older ones there (the phone changed
	// network; the old socket cannot say so). They leave the set now so the cap never counts them.
	for old := range set {
		if old.listener == s.listener {
			continue
		}
		if _, by := old.closeState(); by != "" {
			continue // already closing (revoke, shutdown, its own end)
		}
		old.requestClose(closeReason{CodeSuperseded, "superseded by a newer connection"})
		delete(set, old)
		h.opts.Log.Info("relay superseded", "device_id", s.dev, "old_listener", old.listener, "new_listener", s.listener)
	}
	if len(set) >= h.opts.MaxPerDevice {
		return closeReason{CodeTooMany, "too_many_connections"}, false
	}
	if set == nil {
		set = map[*session]struct{}{}
		h.byDev[s.dev] = set
	}
	set[s] = struct{}{}
	h.wg.Add(1)
	return closeReason{}, true
}

func (h *Hub) unregister(s *session) {
	h.mu.Lock()
	delete(h.byDev[s.dev], s)
	if len(h.byDev[s.dev]) == 0 {
		delete(h.byDev, s.dev)
	}
	h.mu.Unlock()
	h.wg.Done()
}

// revoked closes every relay of a revoked device with 4401.
func (h *Hub) revoked(id string) {
	h.mu.Lock()
	defer h.mu.Unlock()
	for s := range h.byDev[id] {
		s.requestClose(closeReason{CodeRevoked, "revoked"})
	}
}

// Shutdown closes every relay with 1001 and refuses new ones, then waits for them to end. When ctx
// expires first the remaining sockets are cut and ctx's error is returned.
func (h *Hub) Shutdown(ctx context.Context) error {
	h.mu.Lock()
	h.closed = true
	var all []*session
	for _, set := range h.byDev {
		for s := range set {
			all = append(all, s)
		}
	}
	h.mu.Unlock()
	for _, s := range all {
		s.requestClose(closeReason{CodeGoingAway, "going_away"})
	}
	done := make(chan struct{})
	go func() { h.wg.Wait(); close(done) }()
	select {
	case <-done:
		return nil
	case <-ctx.Done():
		for _, s := range all {
			s.kill()
		}
		return ctx.Err()
	}
}

// Count returns the number of live relays of a device.
func (h *Hub) Count(deviceID string) int {
	h.mu.Lock()
	defer h.mu.Unlock()
	return len(h.byDev[deviceID])
}
