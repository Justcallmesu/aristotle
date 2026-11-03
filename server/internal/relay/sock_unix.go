//go:build unix

package relay

import (
	"errors"
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
	return &trackedConn{Conn: tc, rc: rc}
}

// trackedConn is an accepted TCP connection that writes through its file descriptor itself, so it
// sees when the kernel refused bytes because the send buffer was full (EAGAIN) and the write had to
// wait. Bytes accepted after such a wait mean the peer's TCP took data: a full buffer frees space only
// when the peer acknowledges, and (with TCP_NOTSENT_LOWAT, lowat_linux.go) unsent bytes leave only
// as acknowledgements open the window. A dead peer's last window is sent within moments of its death,
// so nothing then wakes a waiting write.
//
// The TCP connection is embedded as a net.Conn, never as *net.TCPConn: that would promote ReadFrom
// (sendfile/splice) and the unexported writev path (net.Buffers.WriteTo), both of which write to the
// descriptor without passing through Write, so a drain would go unseen. io.Copy and net.Buffers fall
// back to Write here.
type trackedConn struct {
	net.Conn // the *net.TCPConn; only net.Conn's methods are promoted
	rc       syscall.RawConn
	drained  atomic.Pointer[func()] // set by the relay at hijack; nil before
}

func (c *trackedConn) notifyDrain(f func()) { c.drained.Store(&f) }

// SyscallConn exposes the descriptor for socket options (TCP_NOTSENT_LOWAT, lowat_linux.go). Writing
// through it would bypass the tracking; the relay only sets options.
func (c *trackedConn) SyscallConn() (syscall.RawConn, error) { return c.rc, nil }

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
		err = werr
	}
	if err != nil {
		err = c.writeError(err)
	}
	return n, err
}

// writeError reports every write failure the way net.TCPConn.Write does ("write tcp a->b: …"): a deadline
// or Close arrives from RawConn.Write already wrapped as "raw-write", a syscall error unwrapped. The cause
// is kept, so errors.Is(err, net.ErrClosed) and a deadline's Timeout() still hold.
func (c *trackedConn) writeError(err error) error {
	var op *net.OpError
	if errors.As(err, &op) {
		err = op.Err
	}
	return &net.OpError{Op: "write", Net: "tcp", Source: c.LocalAddr(), Addr: c.RemoteAddr(), Err: err}
}
