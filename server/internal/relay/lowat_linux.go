//go:build linux

package relay

import (
	"net"
	"syscall"
)

const (
	// tcpNotSentLowat is TCP_NOTSENT_LOWAT (linux/tcp.h); package syscall does not define it.
	tcpNotSentLowat = 0x19
	// notSentLowat caps the bytes queued on a phone socket but not yet sent. Without it the send
	// buffer may grow to tcp_wmem's maximum (4 MiB) on a long-lived link, and a slow phone must
	// then take all of that before it can see a ping. With it, writes keep waiting on the phone
	// (drain marks, liveness.go) until little is left unsent.
	notSentLowat = 128 << 10
)

// setNotSentLowat sets TCP_NOTSENT_LOWAT on the phone's TCP connection. Best effort: without it the
// relay still works, only the tail after the last waiting write can be longer.
func setNotSentLowat(c net.Conn) {
	sc, ok := c.(syscall.Conn)
	if !ok {
		return
	}
	rc, err := sc.SyscallConn()
	if err != nil {
		return
	}
	_ = rc.Control(func(fd uintptr) {
		_ = syscall.SetsockoptInt(int(fd), syscall.IPPROTO_TCP, tcpNotSentLowat, notSentLowat)
	})
}
