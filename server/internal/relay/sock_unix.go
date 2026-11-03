//go:build unix

package relay

import (
	"io"
	"net"
	"os"
	"sync/atomic"
	"syscall"
)

// exactDrain: accepted connections report blocked writes exactly (tests).
const exactDrain = true

// maxWrite bounds one write(2), as package net does (some systems refuse 2 GiB or more).
const maxWrite = 1 << 30

func track(tc *net.TCPConn) net.Conn {
	rc, err := tc.SyscallConn()
	if err != nil {
		return tc
	}
	return &trackedConn{TCPConn: tc, rc: rc}
}

// trackedConn is an accepted TCP connection that writes through its file descriptor itself, so it
// sees when the kernel refused bytes because the send buffer was full (EAGAIN) and the write had to
// wait. Bytes accepted after such a wait mean the peer's TCP took data: a full buffer frees space only
// when the peer acknowledges, and (with TCP_NOTSENT_LOWAT, lowat_linux.go) unsent bytes leave only
// as acknowledgements open the window. A dead peer's last window is sent within moments of its death,
// so nothing then wakes a waiting write.
type trackedConn struct {
	*net.TCPConn
	rc      syscall.RawConn
	drained atomic.Pointer[func()] // set by the relay at hijack; nil before
}

func (c *trackedConn) notifyDrain(f func()) { c.drained.Store(&f) }

// Write writes all of p, like net.TCPConn.Write (the descriptor is non-blocking and the runtime
// poller waits for room, honouring write deadlines and Close), and calls the drain function each time
// the kernel takes bytes after a write found the buffer full.
func (c *trackedConn) Write(p []byte) (int, error) {
	var n int
	var werr error
	waited := false
	err := c.rc.Write(func(fd uintptr) bool {
		for n < len(p) {
			m, err := syscall.Write(int(fd), p[n:min(len(p), n+maxWrite)])
			switch {
			case err == syscall.EINTR:
				continue
			case err == syscall.EAGAIN:
				waited = true
				return false // wait until writable, then call again
			case err != nil:
				werr = os.NewSyscallError("write", err)
				return true
			case m == 0:
				werr = io.ErrUnexpectedEOF
				return true
			}
			n += m
			if waited {
				waited = false
				if f := c.drained.Load(); f != nil {
					(*f)()
				}
			}
		}
		return true
	})
	if werr != nil {
		err = &net.OpError{Op: "write", Net: "tcp", Source: c.LocalAddr(), Addr: c.RemoteAddr(), Err: werr}
	}
	return n, err
}
