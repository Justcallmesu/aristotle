//go:build !linux

package relay

import "net"

// setNotSentLowat is Linux-only (TCP_NOTSENT_LOWAT); elsewhere the send buffer is left as it is.
func setNotSentLowat(net.Conn) {}
