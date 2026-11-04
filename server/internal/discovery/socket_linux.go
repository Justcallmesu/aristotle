//go:build linux

package discovery

import (
	"fmt"
	"net"
	"net/netip"
	"os"

	"golang.org/x/net/ipv4"
	"golang.org/x/sys/unix"
)

// udpConn is the IPv4 mDNS socket of one interface.
type udpConn struct {
	pc *ipv4.PacketConn
	c  net.PacketConn
	ix int
}

// openConn opens the interface's mDNS socket: bound to 224.0.0.251:5353 (the
// group address, never 0.0.0.0, so unicast datagrams to our addresses never
// reach it), pinned to the device, address/port reuse so avahi coexists,
// IP_MULTICAST_ALL off, the group joined on this interface only, multicast
// out of it with TTL 255, and IP_PKTINFO on for the per-packet filter.
func openConn(ifc *net.Interface) (conn, error) {
	c, err := bindGroup(ifc.Name)
	if err != nil {
		return nil, err
	}
	pc := ipv4.NewPacketConn(c)
	group := &net.UDPAddr{IP: net.IP(mdnsGroup.AsSlice())}
	steps := []struct {
		what string
		err  func() error
	}{
		{"join group", func() error { return pc.JoinGroup(ifc, group) }},
		{"multicast interface", func() error { return pc.SetMulticastInterface(ifc) }},
		{"multicast TTL", func() error { return pc.SetMulticastTTL(255) }},
		{"unicast TTL", func() error { return pc.SetTTL(255) }},
		{"packet info", func() error { return pc.SetControlMessage(ipv4.FlagInterface|ipv4.FlagDst, true) }},
	}
	for _, s := range steps {
		if err := s.err(); err != nil {
			_ = c.Close()
			return nil, fmt.Errorf("mDNS %s on %s: %w", s.what, ifc.Name, err)
		}
	}
	return &udpConn{pc: pc, c: c, ix: ifc.Index}, nil
}

// bindGroup creates the UDP socket by hand: net.ListenPacket rewrites a
// multicast local address to 0.0.0.0, which is exactly what must not happen.
func bindGroup(iface string) (net.PacketConn, error) {
	fd, err := unix.Socket(unix.AF_INET, unix.SOCK_DGRAM|unix.SOCK_CLOEXEC|unix.SOCK_NONBLOCK, unix.IPPROTO_UDP)
	if err != nil {
		return nil, fmt.Errorf("mDNS socket: %w", err)
	}
	if err := socketOptions(fd, iface); err != nil {
		_ = unix.Close(fd)
		return nil, err
	}
	if err := unix.Bind(fd, &unix.SockaddrInet4{Addr: mdnsGroup.As4(), Port: mdnsPort}); err != nil {
		_ = unix.Close(fd)
		return nil, fmt.Errorf("mDNS bind %s:%d on %s: %w", mdnsGroup, mdnsPort, iface, err)
	}
	f := os.NewFile(uintptr(fd), "mdns-"+iface)
	defer f.Close() // FilePacketConn dups the descriptor
	return net.FilePacketConn(f)
}

// socketOptions sets the options that must precede bind.
func socketOptions(fd int, iface string) error {
	opts := []struct {
		what            string
		level, opt, val int
	}{
		{"SO_REUSEADDR", unix.SOL_SOCKET, unix.SO_REUSEADDR, 1},
		{"SO_REUSEPORT", unix.SOL_SOCKET, unix.SO_REUSEPORT, 1},
		{"IP_MULTICAST_ALL", unix.IPPROTO_IP, unix.IP_MULTICAST_ALL, 0},
	}
	for _, o := range opts {
		if err := unix.SetsockoptInt(fd, o.level, o.opt, o.val); err != nil {
			return fmt.Errorf("mDNS %s: %w", o.what, err)
		}
	}
	if err := unix.SetsockoptString(fd, unix.SOL_SOCKET, unix.SO_BINDTODEVICE, iface); err != nil {
		return fmt.Errorf("mDNS bind to interface %s: %w", iface, err)
	}
	return nil
}

func (u *udpConn) read(b []byte) (inbound, error) {
	n, cm, src, err := u.pc.ReadFrom(b)
	if err != nil {
		return inbound{}, err
	}
	in := inbound{data: b[:n]}
	if ua, ok := src.(*net.UDPAddr); ok {
		in.src = ua.AddrPort()
		in.src = netip.AddrPortFrom(in.src.Addr().Unmap(), in.src.Port())
	}
	if cm != nil {
		in.ifIndex = cm.IfIndex
		in.dst, _ = netip.AddrFromSlice(cm.Dst.To4())
	}
	return in, nil
}

func (u *udpConn) send(b []byte, to netip.AddrPort) error {
	_, err := u.pc.WriteTo(b, &ipv4.ControlMessage{IfIndex: u.ix}, net.UDPAddrFromAddrPort(to))
	return err
}

func (u *udpConn) Close() error { return u.c.Close() }
