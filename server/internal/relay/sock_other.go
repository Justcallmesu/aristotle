//go:build !unix

package relay

import "net"

// exactDrain: accepted connections pass unwrapped; the relay measures write durations (drainWait).
const exactDrain = false

func track(tc *net.TCPConn) net.Conn { return tc }
