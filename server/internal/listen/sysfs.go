package listen

import (
	"bufio"
	"fmt"
	"io"
	"net"
	"net/netip"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

// Host says where the interface facts live. The zero value is not usable;
// use SystemHost (tests point the paths at a fake tree).
type Host struct {
	NetClassDir string // /sys/class/net
	RouteFile   string // /proc/net/route
}

// SystemHost is the real Linux layout.
var SystemHost = Host{NetClassDir: "/sys/class/net", RouteFile: "/proc/net/route"}

// SystemInterfaces reads the host's interfaces with their sysfs and routing facts.
func SystemInterfaces() ([]Iface, error) { return SystemHost.Interfaces() }

// Interfaces lists the interfaces from the kernel and annotates them from h.
func (h Host) Interfaces() ([]Iface, error) {
	ifs, err := net.Interfaces()
	if err != nil {
		return nil, fmt.Errorf("list interfaces: %w", err)
	}
	out := make([]Iface, 0, len(ifs))
	for _, ni := range ifs {
		addrs, err := ni.Addrs()
		if err != nil {
			return nil, fmt.Errorf("addresses of %s: %w", ni.Name, err)
		}
		out = append(out, Iface{
			Name:     ni.Name,
			Up:       ni.Flags&net.FlagUp != 0 && ni.Flags&net.FlagRunning != 0,
			Loopback: ni.Flags&net.FlagLoopback != 0,
			Addrs:    ifacePrefixes(addrs),
		})
	}
	return h.Annotate(out), nil
}

// Annotate fills Physical, Tun and DefaultRoute from sysfs and the routing
// table. A missing file means "no" (fail closed: no LAN or Tailscale listener).
func (h Host) Annotate(ifaces []Iface) []Iface {
	def := h.defaultRouteIface()
	for i := range ifaces {
		name := ifaces[i].Name
		ifaces[i].Physical = h.exists(name, "device")
		ifaces[i].Tun = h.exists(name, "tun_flags")
		ifaces[i].DefaultRoute = name != "" && name == def
	}
	return ifaces
}

func (h Host) exists(iface, entry string) bool {
	if iface == "" || strings.ContainsAny(iface, `/\`) || iface == "." || iface == ".." {
		return false
	}
	_, err := os.Stat(filepath.Join(h.NetClassDir, iface, entry))
	return err == nil
}

// defaultRouteIface returns the interface of the IPv4 default route with the
// lowest metric, or "" when there is none or the table cannot be read.
func (h Host) defaultRouteIface() string {
	f, err := os.Open(h.RouteFile)
	if err != nil {
		return ""
	}
	defer f.Close()
	return parseDefaultRoute(f)
}

const rtfUp = 0x1

// parseDefaultRoute reads /proc/net/route (header line first) and returns
// the interface of the up 0.0.0.0/0 route with the lowest metric.
func parseDefaultRoute(r io.Reader) string {
	best, bestMetric := "", -1
	sc := bufio.NewScanner(r)
	sc.Scan() // header
	for sc.Scan() {
		f := strings.Fields(sc.Text())
		if len(f) < 8 || f[1] != "00000000" || f[7] != "00000000" {
			continue
		}
		flags, err1 := strconv.ParseUint(f[3], 16, 32)
		metric, err2 := strconv.Atoi(f[6])
		if err1 != nil || err2 != nil || flags&rtfUp == 0 {
			continue
		}
		if bestMetric < 0 || metric < bestMetric {
			best, bestMetric = f[0], metric
		}
	}
	return best
}

// ifacePrefixes converts interface addresses to prefixes (IPv4 in 4-byte form).
func ifacePrefixes(addrs []net.Addr) []netip.Prefix {
	out := make([]netip.Prefix, 0, len(addrs))
	for _, a := range addrs {
		ipnet, ok := a.(*net.IPNet)
		if !ok {
			continue
		}
		ip := ipnet.IP
		if v4 := ip.To4(); v4 != nil {
			ip = v4
		}
		addr, ok := netip.AddrFromSlice(ip)
		ones, bits := ipnet.Mask.Size()
		if !ok || bits != addr.BitLen() {
			continue
		}
		out = append(out, netip.PrefixFrom(addr, ones))
	}
	return out
}
