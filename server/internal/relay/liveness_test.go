package relay

import (
	"context"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"runtime"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/coder/websocket"

	"github.com/justcallmesu/aristotle/server/internal/hermes"
)

const (
	// socketBuffer is the kernel buffer size of the slow-phone tests on both ends, so a slow reader backs
	// up into the relay's writes after ~0.5 MiB instead of megabytes of loopback buffers. Loopback's MSS
	// is 64 KiB; buffers much below 2 × MSS stall TCP itself (zero-window probes), not the relay.
	socketBuffer = 128 << 10
	// bigSize is a frame well above all the kernel buffers on both ends.
	bigSize = 2560 << 10
)

// newBigHarness is newHarness with bigSize frames allowed through the relay and the fake Hermes.
func newBigHarness(t *testing.T, ping, pong time.Duration) *harness {
	t.Helper()
	h := newHarness(t, func(o *Options) { o.PingInterval, o.PongTimeout, o.MaxFrameBytes = ping, pong, 2*bigSize })
	h.fake.ReadLimit = 2 * bigSize
	return h
}

// slowConn reads at most rate bytes per second, in pieces of 8 KiB: a phone on a slow link.
type slowConn struct {
	net.Conn
	rate int
}

func (c *slowConn) Read(p []byte) (int, error) {
	if len(p) > 8<<10 {
		p = p[:8<<10]
	}
	n, err := c.Conn.Read(p)
	time.Sleep(time.Duration(n) * time.Second / time.Duration(c.rate))
	return n, err
}

// aheadConn reads up to ahead bytes in advance into memory: a phone with a large receive buffer,
// whatever the host's socket buffer limits.
type aheadConn struct {
	net.Conn
	ahead  int
	mu     sync.Mutex
	cond   sync.Cond
	buf    []byte
	err    error
	closed bool
}

func newAheadConn(c net.Conn, ahead int) *aheadConn {
	a := &aheadConn{Conn: c, ahead: ahead}
	a.cond.L = &a.mu
	go a.fill()
	return a
}

func (a *aheadConn) fill() {
	p := make([]byte, 32<<10)
	for {
		a.mu.Lock()
		for len(a.buf) >= a.ahead && !a.closed {
			a.cond.Wait()
		}
		closed := a.closed
		a.mu.Unlock()
		if closed {
			return
		}
		n, err := a.Conn.Read(p)
		a.mu.Lock()
		a.buf, a.err = append(a.buf, p[:n]...), err
		a.cond.Broadcast()
		a.mu.Unlock()
		if err != nil {
			return
		}
	}
}

func (a *aheadConn) Read(p []byte) (int, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	for len(a.buf) == 0 && a.err == nil {
		a.cond.Wait()
	}
	if len(a.buf) == 0 {
		return 0, a.err
	}
	n := copy(p, a.buf)
	a.buf = a.buf[n:]
	a.cond.Broadcast()
	return n, nil
}

func (a *aheadConn) Close() error {
	a.mu.Lock()
	a.closed = true
	a.cond.Broadcast()
	a.mu.Unlock()
	return a.Conn.Close()
}

type smallSendListener struct{ net.Listener }

func (l smallSendListener) Accept() (net.Conn, error) {
	c, err := l.Listener.Accept()
	if tc, ok := c.(*net.TCPConn); ok {
		_ = tc.SetWriteBuffer(socketBuffer)
	}
	return c, err
}

// slowPhone opens a relay through a listener with a small send buffer, as a phone with a small receive
// buffer that reads at rate bytes per second, and reads the gateway.ready frame. ahead > 0 adds that
// much read-ahead memory in front of the rate limit.
func (h *harness) slowPhone(t *testing.T, rate, ahead int) *websocket.Conn {
	t.Helper()
	srv := httptest.NewUnstartedServer(h.srv.Config.Handler)
	srv.Listener = WrapListener(smallSendListener{srv.Listener})
	srv.Start()
	t.Cleanup(srv.Close)
	dial := func(ctx context.Context, network, addr string) (net.Conn, error) {
		c, err := (&net.Dialer{}).DialContext(ctx, network, addr)
		if err != nil {
			return nil, err
		}
		_ = c.(*net.TCPConn).SetReadBuffer(socketBuffer)
		if ahead > 0 {
			c = newAheadConn(c, ahead)
		}
		return &slowConn{Conn: c, rate: rate}, nil
	}
	pc, _, err := websocket.Dial(testCtx(t), "ws"+strings.TrimPrefix(srv.URL, "http")+"/v1/ws", &websocket.DialOptions{
		HTTPClient: &http.Client{Transport: &http.Transport{DialContext: dial}},
		HTTPHeader: http.Header{"Authorization": {"Bearer " + h.token}},
	})
	if err != nil {
		t.Fatal(err)
	}
	pc.SetReadLimit(-1)
	t.Cleanup(func() { pc.CloseNow() })
	if got := read(t, pc); !strings.Contains(got, "gateway.ready") {
		t.Fatalf("first frame = %s", got)
	}
	return pc
}

// bigFrame is a frame of exactly size bytes that the fake Hermes echoes back. It is not JSON, so the
// relay's own work per frame stays small under -race and the test measures the phone's link only.
func bigFrame(id, size int) string {
	return "#" + strings.Repeat(string(rune('a'+id)), size-1)
}

// Review T06 #1: a phone that keeps reading, but slower than one frame per PongTimeout, gets every frame.
func TestRelaySlowPhoneGetsEveryLargeFrame(t *testing.T) {
	// Each frame takes 1.25 s to read. rate × PongTimeout is 1 MiB, so even after both socket buffers
	// have filled, writing one frame in one piece takes twice PongTimeout: the T06 code cut it 1011.
	const rate, n = 2 << 20, 2
	h := newBigHarness(t, 50*time.Millisecond, 500*time.Millisecond)
	pc := h.slowPhone(t, rate, 0)
	sent := make(chan error, 1)
	go func() {
		for i := range n {
			if err := pc.Write(context.Background(), websocket.MessageText, []byte(bigFrame(i, bigSize))); err != nil {
				sent <- err
				return
			}
		}
		sent <- nil
	}()
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	for i := range n {
		_, got, err := pc.Read(ctx)
		if err != nil {
			t.Fatalf("frame %d: %v (the relay cut a phone that was still reading)", i, err)
		}
		if string(got) != bigFrame(i, bigSize) {
			t.Fatalf("frame %d came back damaged (%d bytes)", i, len(got))
		}
	}
	if err := <-sent; err != nil {
		t.Fatal(err)
	}
	if h.hub.Count(h.dev.ID) != 1 || strings.Contains(h.logs.String(), "relay closed") {
		t.Fatalf("relay closed:\n%s", h.logs.String())
	}
}

// floodingHermes is a Hermes that, after gateway.ready, sends 256 KiB frames as fast as the relay takes them.
type floodingHermes struct {
	*hermes.Conn
	ready bool // owned by pumpHermes
}

func (u *floodingHermes) Read(ctx context.Context) ([]byte, error) {
	if !u.ready {
		u.ready = true
		return u.Conn.Read(ctx)
	}
	return []byte(bigFrame(0, 256<<10)), ctx.Err()
}

// Review T06b A: while the relay is writing to a slow phone, the phone is kept though every pong comes
// back late. 2 MiB of read-ahead puts ~2.5 MiB ahead of each ping: ~1.2 s at 2 MiB/s, past PongTimeout
// (300 ms). Only the relay's waiting writes completing (~0.1 s apart: the kernel wakes a blocked
// writer once a large part of the 128 KiB send buffer drained) show that the phone is alive.
func TestRelaySlowPhoneKeptByDrainWhilePongsAreLate(t *testing.T) {
	const rate, ahead, run = 2 << 20, 2 << 20, 2500 * time.Millisecond
	h := newHarness(t, func(o *Options) {
		o.PingInterval, o.PongTimeout = 50*time.Millisecond, 300*time.Millisecond
		o.wrapHermes = func(c *hermes.Conn) upstream { return &floodingHermes{Conn: c} }
	})
	pc := h.slowPhone(t, rate, ahead)
	ctx := testCtx(t) // not run: a read cut by its context closes the phone's socket
	got := 0
	for end := time.Now().Add(run); time.Now().Before(end); {
		_, r, err := pc.Reader(ctx)
		if err == nil {
			var n int64
			n, err = io.Copy(io.Discard, r)
			got += int(n)
		}
		if err != nil {
			t.Fatalf("after %d bytes: %v (the relay cut a phone that was still reading)", got, err)
		}
	}
	if why, ok := requestedClose(h.hub, h.dev.ID); ok {
		t.Fatalf("relay closed %d %s after %d bytes", why.code, why.reason, got)
	}
	if got < rate { // well over a second of reading at rate
		t.Fatalf("the phone read only %d bytes in %v", got, run)
	}
	t.Logf("read %d bytes in %v", got, run)
}

// A phone that stops reading in the middle of a large frame is still closed 1011.
func TestRelayKeepaliveClosesPhoneThatStopsReadingMidFrame(t *testing.T) {
	h := newBigHarness(t, 50*time.Millisecond, 200*time.Millisecond)
	pc := h.slowPhone(t, 64<<20, 0)
	write(t, pc, bigFrame(1, bigSize)) // echoed back, never read
	waitFor(t, "relay closed", func() bool { return strings.Contains(h.logs.String(), "relay closed") })
	if !strings.Contains(h.logs.String(), "close_code=1011 closed_by=server") {
		t.Fatalf("close line:\n%s", h.logs.String())
	}
}

// Review T06 #2: revoking a device whose phone is not reading cuts its socket within 1 s.
func TestRelayRevokeCutsStalledPhoneWithinOneSecond(t *testing.T) {
	h := newBigHarness(t, DefaultPingInterval, DefaultPongTimeout)
	pc := h.slowPhone(t, 64<<20, 0)
	write(t, pc, bigFrame(1, bigSize)) // echoed back, never read
	waitFor(t, "Hermes to echo the frame", func() bool {
		h.fake.Mu.Lock()
		defer h.fake.Mu.Unlock()
		return len(h.fake.FirstFrames) >= 2 // the connector's capabilities, then the frame
	})
	time.Sleep(300 * time.Millisecond) // the echo fills ~0.5 MiB of socket buffers within milliseconds and stalls

	start := time.Now()
	if _, err := h.store.Revoke(h.dev.ID); err != nil {
		t.Fatal(err)
	}
	waitFor(t, "relay gone", func() bool { return h.hub.Count(h.dev.ID) == 0 })
	if d := time.Since(start); d > time.Second {
		t.Fatalf("revoke of a stalled phone took %v", d)
	}
	if !strings.Contains(h.logs.String(), "close_code=4401") {
		t.Fatalf("close line:\n%s", h.logs.String())
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	for {
		if _, _, err := pc.Read(ctx); err != nil {
			if errors.Is(err, context.DeadlineExceeded) {
				t.Fatal("the phone's socket stayed open after the revoke")
			}
			return
		}
	}
}

// eventFrame is a Hermes event of ~90 bytes, like the tool progress events an agent streams.
const eventFrame = `{"jsonrpc":"2.0","method":"event","params":{"type":"tool.progress","session_id":"s1","n":7}}`

// streamingHermes is a Hermes that, after its first frame (gateway.ready), sends eventFrame every tick
// and nothing else: an agent streaming tool events to a phone that may be gone.
type streamingHermes struct {
	*hermes.Conn
	tick  *time.Ticker
	ready bool // owned by pumpHermes
}

func streamEvents(every time.Duration) func(*hermes.Conn) upstream {
	return func(c *hermes.Conn) upstream { return &streamingHermes{Conn: c, tick: time.NewTicker(every)} }
}

func (u *streamingHermes) Read(ctx context.Context) ([]byte, error) {
	if !u.ready {
		u.ready = true
		return u.Conn.Read(ctx)
	}
	select {
	case <-ctx.Done():
		return nil, ctx.Err()
	case <-u.tick.C:
		return []byte(eventFrame), nil
	}
}

func (u *streamingHermes) Close() error {
	u.tick.Stop()
	return u.Conn.Close()
}

// requestedClose returns the close a relay of dev has decided on, if any.
func requestedClose(h *Hub, dev string) (closeReason, bool) {
	h.mu.Lock()
	defer h.mu.Unlock()
	for s := range h.byDev[dev] {
		if why, _ := s.closeState(); why.code != 0 {
			return why, true
		}
	}
	return closeReason{}, false
}

// wantDeadPhoneClosed: a phone that stopped reading at start is closed 1011 within bound, and the
// events Hermes streamed meanwhile were written to it (it reads them, then the close frame).
func wantDeadPhoneClosed(t *testing.T, h *harness, pc *websocket.Conn, start time.Time, bound time.Duration) {
	t.Helper()
	for {
		why, ok := requestedClose(h.hub, h.dev.ID)
		elapsed := time.Since(start)
		if ok {
			if why.code != CodePongTimeout {
				t.Fatalf("closed %d %s, want 1011", why.code, why.reason)
			}
			t.Logf("closed 1011 after %v", elapsed.Round(time.Millisecond))
			break
		}
		if elapsed > bound {
			t.Fatalf("a phone that reads nothing is still open after %v while Hermes streams", elapsed)
		}
		time.Sleep(2 * time.Millisecond)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	events := 0
	for {
		_, _, err := pc.Read(ctx)
		if err == nil {
			events++
			continue
		}
		if websocket.CloseStatus(err) != CodePongTimeout {
			t.Fatalf("after %d events: %v, want close 1011", events, err)
		}
		break
	}
	if events < 3 {
		t.Fatalf("only %d events reached the phone; Hermes was not streaming", events)
	}
	t.Logf("%d events written to the dead phone", events)
}

const (
	livePing = 100 * time.Millisecond
	livePong = 200 * time.Millisecond
	// liveBound: the first ping goes out at most livePing after the phone stops reading, the close
	// follows livePong later; 100 ms of slack for -race.
	liveBound = livePing + livePong + 100*time.Millisecond
)

// Review T06b A: Hermes streaming small events to a phone that reads nothing does not keep it open.
// The kernel takes each event into a free send buffer at once; that is not the phone making progress.
func TestRelayKeepaliveClosesDeadPhoneWhileHermesStreams(t *testing.T) {
	h := newHarness(t, func(o *Options) {
		o.PingInterval, o.PongTimeout, o.wrapHermes = livePing, livePong, streamEvents(40*time.Millisecond)
	})
	pc := h.phone(t, h.token) // reads gateway.ready, then nothing
	wantDeadPhoneClosed(t, h, pc, time.Now(), liveBound)
}

// The same with small socket buffers and an event every millisecond.
func TestRelayKeepaliveClosesDeadPhoneUnderFastStreamSmallBuffers(t *testing.T) {
	h := newHarness(t, func(o *Options) {
		o.PingInterval, o.PongTimeout, o.wrapHermes = livePing, livePong, streamEvents(time.Millisecond)
	})
	pc := h.slowPhone(t, 64<<20, 0) // 128 KiB buffers both ends; reads gateway.ready, then nothing
	wantDeadPhoneClosed(t, h, pc, time.Now(), liveBound)
}

// sessionOf returns the one live relay of h's device.
func sessionOf(t *testing.T, h *harness) *session {
	t.Helper()
	h.hub.mu.Lock()
	defer h.hub.mu.Unlock()
	for s := range h.hub.byDev[h.dev.ID] {
		return s
	}
	t.Fatal("no live relay")
	return nil
}

// loadCPUAndGC keeps every CPU busy with goroutines that allocate garbage, plus one that forces
// collections, until the test ends: a loaded host on which a write into free buffer space can take
// many milliseconds (descheduled, or stopped for GC).
func loadCPUAndGC(t *testing.T) {
	done := make(chan struct{})
	var wg sync.WaitGroup
	for range 2 * runtime.GOMAXPROCS(0) {
		wg.Go(func() {
			var sink [][]byte
			for {
				select {
				case <-done:
					return
				default:
				}
				sink = append(sink, make([]byte, 64<<10))
				if len(sink) > 64 {
					sink = sink[:0]
				}
			}
		})
	}
	wg.Go(func() {
		for {
			select {
			case <-done:
				return
			case <-time.After(time.Millisecond):
				runtime.GC()
			}
		}
	})
	t.Cleanup(func() { close(done); wg.Wait() })
}

// Review T06c #1: on a loaded host a dead phone is still closed at the first ping + pong. Under this
// load the old 10 ms write-duration rule counted some free writes as the phone draining (one more
// round each); a watched connection counts a write only after the kernel refused bytes (EAGAIN).
func TestRelayKeepaliveClosesDeadPhoneUnderCPUAndGCLoad(t *testing.T) {
	if !exactDrain {
		t.Skip("blocked writes are timed on this system")
	}
	h := newHarness(t, func(o *Options) {
		o.PingInterval, o.PongTimeout, o.wrapHermes = livePing, livePong, streamEvents(time.Millisecond)
	})
	pc := h.slowPhone(t, 64<<20, 0) // reads gateway.ready, then nothing
	s := sessionOf(t, h)
	loadCPUAndGC(t)
	start := time.Now()
	waitFor(t, "the close decision", func() bool { _, ok := requestedClose(h.hub, h.dev.ID); return ok })
	if s.lastDrain.Load() != 0 {
		t.Fatal("a write into free buffer space counted as the dead phone draining")
	}
	wantDeadPhoneClosed(t, h, pc, start, liveBound)
}

// stuckHermes is a Hermes that never takes a frame from the relay.
type stuckHermes struct {
	*hermes.Conn
	writing chan struct{}
	once    sync.Once
}

func (u *stuckHermes) Write(ctx context.Context, _ []byte) error {
	u.once.Do(func() { close(u.writing) })
	<-ctx.Done()
	return ctx.Err()
}

// Review T06b B: while a phone frame waits for Hermes, pings still reach the phone, and the pongs the
// relay cannot read meanwhile do not close it.
func TestRelayPingsPhoneWhileForwardingWaitsForHermes(t *testing.T) {
	stuck := &stuckHermes{writing: make(chan struct{})}
	h := newHarness(t, func(o *Options) {
		o.PingInterval, o.PongTimeout = 20*time.Millisecond, 50*time.Millisecond
		o.wrapHermes = func(c *hermes.Conn) upstream { stuck.Conn = c; return stuck }
	})
	var pings atomic.Int32
	pc, _, err := websocket.Dial(testCtx(t), h.url(), &websocket.DialOptions{
		HTTPHeader:     http.Header{"Authorization": {"Bearer " + h.token}},
		OnPingReceived: func(context.Context, []byte) bool { pings.Add(1); return true },
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { pc.CloseNow() })
	if got := read(t, pc); !strings.Contains(got, "gateway.ready") {
		t.Fatalf("first frame = %s", got)
	}
	write(t, pc, `{"jsonrpc":"2.0","id":5,"method":"prompt.submit","params":{}}`)
	<-stuck.writing
	before := pings.Load()
	ctx, cancel := context.WithTimeout(context.Background(), 500*time.Millisecond) // 10 pong timeouts
	defer cancel()
	if _, _, err := pc.Read(ctx); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("read while the frame waits for Hermes: %v, want only the deadline", err)
	}
	if got := pings.Load() - before; got < maxPings-1 { // one may have been out already
		t.Fatalf("%d pings reached the phone in 500 ms while forwarding, want >= %d", got, maxPings-1)
	}
	if why, ok := requestedClose(h.hub, h.dev.ID); ok {
		t.Fatalf("relay closed %d %s while waiting for Hermes", why.code, why.reason)
	}
}
