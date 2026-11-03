// Package listen finds the laptop's LAN and Tailscale addresses, binds one
// HTTPS listener per address (the same pinned certificate on both networks)
// and keeps that set in sync with the interfaces. It never binds a wildcard
// address.
package listen

import (
	"cmp"
	"net"
	"net/netip"
	"slices"
	"strconv"
	"strings"
)

// Kind is the network a listener serves.
type Kind string

const (
	LAN       Kind = "lan"
	Tailscale Kind = "tailscale"
)

// tailscaleIfacePrefix starts the name of every Tailscale TUN interface.
const tailscaleIfacePrefix = "tailscale"

var (
	lanPrefixes = []netip.Prefix{
		netip.MustParsePrefix("10.0.0.0/8"),
		netip.MustParsePrefix("172.16.0.0/12"),
		netip.MustParsePrefix("192.168.0.0/16"),
	}
	tailnetPrefixes = []netip.Prefix{
		netip.MustParsePrefix("100.64.0.0/10"),
		netip.MustParsePrefix("fd7a:115c:a1e0::/48"),
	}
)

// Classify reports which address range an address is in: RFC1918 IPv4 is
// LAN; Tailscale's CGNAT range and ULA prefix are Tailscale. The range alone
// does not make an endpoint: Select also checks the interface.
func Classify(a netip.Addr) (Kind, bool) {
	a = a.Unmap()
	switch {
	case inAny(a, lanPrefixes):
		return LAN, true
	case inAny(a, tailnetPrefixes):
		return Tailscale, true
	}
	return "", false
}

func inAny(a netip.Addr, prefixes []netip.Prefix) bool {
	for _, p := range prefixes {
		if p.Contains(a) {
			return true
		}
	}
	return false
}

// Iface is the part of a network interface the selection needs.
type Iface struct {
	Name         string
	Up           bool // administratively up and running (has carrier)
	Loopback     bool
	Physical     bool // backed by a device: /sys/class/net/<if>/device exists
	Tun          bool // a TUN device: /sys/class/net/<if>/tun_flags exists
	DefaultRoute bool // carries the IPv4 default route
	Addrs        []netip.Prefix
}

// tailnet reports whether the interface is Tailscale's own TUN device.
func (i Iface) tailnet() bool {
	return i.Tun && strings.HasPrefix(i.Name, tailscaleIfacePrefix)
}

// Policy is what the config allows.
type Policy struct {
	LAN       bool
	Tailscale bool
	// LANInterfaces, when not empty, names the only interfaces that may get
	// a LAN listener; it replaces the physical-interface rule.
	LANInterfaces []string
}

func (p Policy) lanIface(i Iface) bool {
	if len(p.LANInterfaces) > 0 {
		return slices.Contains(p.LANInterfaces, i.Name)
	}
	return i.Physical
}

// Endpoint is one address to bind.
type Endpoint struct {
	Kind   Kind
	Addr   netip.Addr
	Iface  string
	Subnet netip.Prefix // the interface's on-link prefix (LAN peer gate)
}

// URL is the base URL a client uses for this endpoint: HTTPS on every network.
func (e Endpoint) URL(port int) string {
	return "https://" + net.JoinHostPort(e.Addr.String(), strconv.Itoa(port))
}

// Select returns the endpoints to bind, one per address, in SortEndpoints
// order. Rules:
//   - loopback and down interfaces are skipped;
//   - a LAN (RFC1918) address counts only on a physical interface, or, when
//     Policy.LANInterfaces is set, only on an interface it names; docker,
//     bridge, veth and VPN interfaces therefore get nothing by default;
//   - a Tailscale-range address counts only on a TUN interface whose name
//     starts with "tailscale"; the same range handed out by a Wi-Fi or
//     carrier CGNAT never gets a listener;
//   - anything else (link-local, public, wildcard) is ignored.
func Select(ifaces []Iface, p Policy) []Endpoint {
	var out []Endpoint
	for _, iface := range ifaces {
		if !iface.Up || iface.Loopback {
			continue
		}
		for _, pfx := range iface.Addrs {
			if e, ok := endpointFor(iface, pfx, p); ok {
				out = append(out, e)
			}
		}
	}
	SortEndpoints(out, DefaultIface(ifaces))
	return uniqueAddrs(out)
}

func endpointFor(iface Iface, pfx netip.Prefix, p Policy) (Endpoint, bool) {
	a := pfx.Addr().Unmap()
	if !a.IsValid() || a.IsUnspecified() || a.IsLoopback() {
		return Endpoint{}, false
	}
	kind, ok := Classify(a)
	switch {
	case !ok:
		return Endpoint{}, false
	case kind == LAN && !(p.LAN && p.lanIface(iface)):
		return Endpoint{}, false
	case kind == Tailscale && !(p.Tailscale && iface.tailnet()):
		return Endpoint{}, false
	}
	return Endpoint{Kind: kind, Addr: a, Iface: iface.Name, Subnet: netip.PrefixFrom(a, pfx.Bits()).Masked()}, true
}

// uniqueAddrs keeps the first endpoint for each address (input is sorted).
func uniqueAddrs(eps []Endpoint) []Endpoint {
	seen := map[netip.Addr]bool{}
	return slices.DeleteFunc(eps, func(e Endpoint) bool {
		dup := seen[e.Addr]
		seen[e.Addr] = true
		return dup
	})
}

// DefaultIface returns the name of the interface carrying the default route, or "".
func DefaultIface(ifaces []Iface) string {
	for _, i := range ifaces {
		if i.DefaultRoute {
			return i.Name
		}
	}
	return ""
}

// SortEndpoints orders LAN before Tailscale; within LAN the interface named
// defaultIface comes first; then by interface name and address.
func SortEndpoints(eps []Endpoint, defaultIface string) {
	rank := func(e Endpoint) int {
		switch {
		case e.Kind == LAN && e.Iface == defaultIface:
			return 0
		case e.Kind == LAN:
			return 1
		}
		return 2
	}
	slices.SortFunc(eps, func(a, b Endpoint) int {
		return cmp.Or(
			cmp.Compare(rank(a), rank(b)),
			strings.Compare(a.Iface, b.Iface),
			a.Addr.Compare(b.Addr),
		)
	})
}

// Addrs returns the address of each endpoint, in order.
func Addrs(eps []Endpoint) []netip.Addr {
	out := make([]netip.Addr, 0, len(eps))
	for _, e := range eps {
		out = append(out, e.Addr)
	}
	return out
}
