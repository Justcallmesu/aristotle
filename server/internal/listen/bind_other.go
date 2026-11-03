//go:build !linux

package listen

import "net"

// listenConfig does not pin the socket to the interface: SO_BINDTODEVICE is
// Linux-only. The peer gate is then the only interface check.
func listenConfig(string) net.ListenConfig { return net.ListenConfig{} }
