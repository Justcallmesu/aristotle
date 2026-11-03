package relay

import (
	"syscall"
	"testing"
)

// Review T06c #3: the phone's TCP connection caps its unsent bytes (TCP_NOTSENT_LOWAT).
func TestPhoneSocketHasNotSentLowat(t *testing.T) {
	h := newHarness(t, nil)
	h.phone(t, h.token)
	rc, err := sessionOf(t, h).raw.(syscall.Conn).SyscallConn()
	if err != nil {
		t.Fatal(err)
	}
	var got int
	var gerr error
	if err := rc.Control(func(fd uintptr) {
		got, gerr = syscall.GetsockoptInt(int(fd), syscall.IPPROTO_TCP, tcpNotSentLowat)
	}); err != nil {
		t.Fatal(err)
	}
	if gerr != nil || got != notSentLowat {
		t.Fatalf("TCP_NOTSENT_LOWAT = %d (%v), want %d", got, gerr, notSentLowat)
	}
}
