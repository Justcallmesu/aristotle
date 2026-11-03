//go:build !linux

package control

import (
	"errors"
	"net"
)

// PeerUID is only implemented on Linux; elsewhere every peer is refused.
func PeerUID(*net.UnixConn) (int, error) {
	return 0, errors.New("peer credentials are only supported on linux")
}
