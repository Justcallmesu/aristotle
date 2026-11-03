//go:build unix

package relay

import (
	"io"
	"net"
	"sync/atomic"
	"testing"
	"time"
)

// A watched connection reports a drain only when a write that found the send buffer full got bytes
// out again: never for writes into free space, never once the peer's buffers are full.
func TestTrackedConnReportsOnlyWritesThatWaited(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	wl := WrapListener(ln)
	peer, err := net.Dial("tcp", ln.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer peer.Close()
	_ = peer.(*net.TCPConn).SetReadBuffer(socketBuffer)
	c, err := wl.Accept()
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	tc, ok := c.(*trackedConn)
	if !ok {
		t.Fatalf("accepted %T, want *trackedConn", c)
	}
	_ = tc.SetWriteBuffer(socketBuffer)
	var drains atomic.Int32
	tc.notifyDrain(func() { drains.Add(1) })

	for range 100 { // 9.6 KB into free space
		if _, err := tc.Write(make([]byte, 96)); err != nil {
			t.Fatal(err)
		}
	}
	if n := drains.Load(); n != 0 {
		t.Fatalf("%d drains for writes into free buffer space", n)
	}

	const big = 4 << 20 // far beyond both 128 KiB buffers
	wrote := make(chan error, 1)
	go func() {
		n, err := tc.Write(make([]byte, big))
		if err == nil && n != big {
			err = io.ErrShortWrite
		}
		wrote <- err
	}()
	// The peer's kernel takes data into its receive buffer until that is full (drains: its TCP is
	// alive); then the write waits and, the peer reading nothing, nothing wakes it.
	time.Sleep(300 * time.Millisecond)
	before := drains.Load()
	time.Sleep(300 * time.Millisecond)
	if n := drains.Load() - before; n != 0 {
		t.Fatalf("%d drains while both buffers were full and the peer read nothing", n)
	}
	if _, err := io.CopyN(io.Discard, peer, 9600+big); err != nil {
		t.Fatal(err)
	}
	if err := <-wrote; err != nil {
		t.Fatal(err)
	}
	if n := drains.Load(); n == 0 {
		t.Fatal("no drain reported for a write that waited for the peer")
	}
}

// A write that waits for a peer that never reads ends on the write deadline (without a drain: the
// kernel refused the bytes and never took them) and on Close.
func TestTrackedConnWriteEndsOnDeadlineAndClose(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	peer, err := net.Dial("tcp", ln.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer peer.Close()
	_ = peer.(*net.TCPConn).SetReadBuffer(socketBuffer)
	c, err := WrapListener(ln).Accept()
	if err != nil {
		t.Fatal(err)
	}
	var drains atomic.Int32
	c.(*trackedConn).notifyDrain(func() { drains.Add(1) })
	timedOut := func() {
		t.Helper()
		_ = c.SetWriteDeadline(time.Now().Add(100 * time.Millisecond))
		if _, err := c.Write(make([]byte, 64<<20)); err == nil {
			t.Fatal("write to a peer that reads nothing ended without error")
		} else if ne, ok := err.(net.Error); !ok || !ne.Timeout() {
			t.Fatalf("write past the deadline: %v, want a timeout", err)
		}
	}
	timedOut() // fills both buffers
	before := drains.Load()
	timedOut() // refused at once (EAGAIN), never taken
	if n := drains.Load() - before; n != 0 {
		t.Fatalf("%d drains for a write the kernel never took", n)
	}
	_ = c.SetWriteDeadline(time.Time{})
	done := make(chan error, 1)
	go func() { _, err := c.Write(make([]byte, 64<<20)); done <- err }()
	time.Sleep(50 * time.Millisecond)
	_ = c.Close()
	select {
	case err := <-done:
		if err == nil {
			t.Fatal("write ended without error after Close")
		}
	case <-time.After(5 * time.Second):
		t.Fatal("Close did not end a waiting write")
	}
}
