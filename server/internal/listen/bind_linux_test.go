//go:build linux

package listen

import (
	"errors"
	"net"
	"syscall"
	"testing"
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
