//go:build linux

package listen

import (
	"errors"
	"net"
	"strings"
	"syscall"
	"testing"
	"unsafe"
)

func TestDeviceControlSetsOptionBeforeBind(t *testing.T) {
	var gotFD int
	var gotIface string
	calls := 0
	ctl := deviceControl("wlan0", func(fd int, iface string) error {
		calls++
		gotFD, gotIface = fd, iface
		return nil
	})
	lc := net.ListenConfig{Control: ctl}
	ln, err := lc.Listen(t.Context(), "tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	if calls != 1 || gotIface != "wlan0" || gotFD <= 0 {
		t.Fatalf("setter calls=%d fd=%d iface=%q, want one call with wlan0", calls, gotFD, gotIface)
	}
}

func TestDeviceControlFailureRefusesTheBind(t *testing.T) {
	boom := errors.New("boom")
	lc := net.ListenConfig{Control: deviceControl("tailscale0", func(int, string) error { return boom })}
	ln, err := lc.Listen(t.Context(), "tcp", "127.0.0.1:0")
	if err == nil {
		ln.Close()
		t.Fatal("listen succeeded although the interface bind failed")
	}
	if !errors.Is(err, boom) {
		t.Fatalf("err = %v, want it to wrap the setter error", err)
	}
}

// The real option: pinning a loopback socket to "lo" needs no privileges on Linux >= 5.7.
func TestSetDeviceOnRealSocket(t *testing.T) {
	lc := net.ListenConfig{Control: deviceControl("lo", setDevice)}
	ln, err := lc.Listen(t.Context(), "tcp", "127.0.0.1:0")
	if errors.Is(err, syscall.EPERM) {
		t.Skipf("kernel refuses SO_BINDTODEVICE without privileges: %v", err)
	}
	if err != nil {
		t.Fatal(err)
	}
	ln.Close()
}

// A listener pinned to an interface that does not exist cannot be created.
func TestSetDeviceUnknownInterfaceFails(t *testing.T) {
	lc := net.ListenConfig{Control: deviceControl("nosuchif0", setDevice)}
	ln, err := lc.Listen(t.Context(), "tcp", "127.0.0.1:0")
	if err == nil {
		ln.Close()
		t.Fatal("listen succeeded on a nonexistent interface")
	}
}

// getsockoptString is getsockopt(2) for a string option (the std syscall package has only the setter).
func getsockoptString(fd, level, opt int) (string, error) {
	buf := make([]byte, 256)
	n := uint32(len(buf))
	_, _, errno := syscall.Syscall6(syscall.SYS_GETSOCKOPT, uintptr(fd), uintptr(level), uintptr(opt),
		uintptr(unsafe.Pointer(&buf[0])), uintptr(unsafe.Pointer(&n)), 0)
	if errno != 0 {
		return "", errno
	}
	return strings.TrimRight(string(buf[:n]), "\x00"), nil
}

// boundDevice reads SO_BINDTODEVICE back from a listener's socket.
func boundDevice(t *testing.T, ln net.Listener) string {
	t.Helper()
	sc, err := ln.(*net.TCPListener).SyscallConn()
	if err != nil {
		t.Fatal(err)
	}
	var dev string
	var getErr error
	if err := sc.Control(func(fd uintptr) {
		dev, getErr = getsockoptString(int(fd), syscall.SOL_SOCKET, syscall.SO_BINDTODEVICE)
	}); err != nil {
		t.Fatal(err)
	}
	if getErr != nil {
		t.Fatalf("getsockopt SO_BINDTODEVICE: %v", getErr)
	}
	return dev
}

// The pin is really on the socket: the kernel reports the device back.
func TestListenConfigPinsSocket(t *testing.T) {
	lc := listenConfig("lo")
	ln, err := lc.Listen(t.Context(), "tcp", "127.0.0.1:0")
	if errors.Is(err, syscall.EPERM) {
		t.Skipf("kernel refuses SO_BINDTODEVICE without privileges: %v", err)
	}
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	if got := boundDevice(t, ln); got != "lo" {
		t.Fatalf("SO_BINDTODEVICE = %q, want lo", got)
	}
}

// BindTCP pins to the endpoint's own interface. Needs a selectable LAN or Tailscale address on this host;
// it binds an ephemeral port there for a moment and accepts nothing.
func TestBindTCPPinsToEndpointInterface(t *testing.T) {
	ifaces, err := SystemInterfaces()
	if err != nil {
		t.Fatal(err)
	}
	eps := Select(ifaces, Policy{LAN: true, Tailscale: true})
	if len(eps) == 0 {
		t.Skip("no LAN or Tailscale address on this host")
	}
	e := eps[0]
	ln, err := BindTCP(e, 0)
	if errors.Is(err, syscall.EPERM) {
		t.Skipf("kernel refuses SO_BINDTODEVICE without privileges: %v", err)
	}
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	if got := boundDevice(t, ln); got != e.Iface {
		t.Fatalf("SO_BINDTODEVICE = %q, want %q", got, e.Iface)
	}
}
