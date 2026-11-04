//go:build !linux

package discovery

import (
	"errors"
	"net"
)

// openConn needs IP_MULTICAST_ALL, SO_BINDTODEVICE and IP_PKTINFO, which this
// responder only implements on Linux; elsewhere discovery reports an error.
func openConn(*net.Interface) (conn, error) {
	return nil, errors.New("mDNS responder is only implemented on Linux")
}
