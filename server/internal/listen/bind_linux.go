//go:build linux

package listen

import (
	"fmt"
	"net"
	"syscall"
)

// setDevice pins a socket to one network interface (SO_BINDTODEVICE).
func setDevice(fd int, iface string) error {
	return syscall.SetsockoptString(fd, syscall.SOL_SOCKET, syscall.SO_BINDTODEVICE, iface)
}

// deviceControl returns a net.ListenConfig.Control hook that pins the socket
// to iface before it is bound, so a packet that arrives on another interface
// never reaches this listener, whatever its source address. set is
// setDevice; tests inject their own.
func deviceControl(iface string, set func(fd int, iface string) error) func(network, address string, c syscall.RawConn) error {
	return func(_, _ string, c syscall.RawConn) error {
		var setErr error
		if err := c.Control(func(fd uintptr) { setErr = set(int(fd), iface) }); err != nil {
			return err
		}
		if setErr != nil {
			return fmt.Errorf("bind to interface %s: %w", iface, setErr)
		}
		return nil
	}
}

func listenConfig(iface string) net.ListenConfig {
	return net.ListenConfig{Control: deviceControl(iface, setDevice)}
}
