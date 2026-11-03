package relay

import "net"

// WrapListener returns ln with every accepted TCP connection wrapped so that the relay can tell
// exactly when a write to the phone had to wait for it (liveness.go, sock_unix.go). The remote
// listeners pass through it (listen.Manager.Wrap). Every remote listener serves TLS, and tls.Conn.NetConn
// returns the wrapper, so the relay finds it on the LAN and the Tailscale listener alike. On non-Unix systems
// connections pass unwrapped and the relay measures write durations instead (drainWait).
func WrapListener(ln net.Listener) net.Listener { return trackListener{ln} }

type trackListener struct{ net.Listener }

func (l trackListener) Accept() (net.Conn, error) {
	c, err := l.Listener.Accept()
	if tc, ok := c.(*net.TCPConn); ok {
		return track(tc), err
	}
	return c, err
}

// drainNotifier is a phone connection that reports each time a write that found the send buffer full
// got bytes into it again (trackedConn on Unix).
type drainNotifier interface {
	notifyDrain(f func())
}
