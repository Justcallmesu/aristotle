//go:build linux

package discovery

import (
	"bufio"
	"context"
	"encoding/binary"
	"fmt"
	"net"
	"net/netip"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"testing"
	"time"

	"golang.org/x/net/dns/dnsmessage"
	"golang.org/x/net/ipv4"
)

// The namespace test runs the real responder in a private user+net namespace
// (`unshare -rn`, no root, never a real interface):
//
//	server ns: lanA 192.168.50.10/24  <-veth->  peer ns: lanB 192.168.50.20/24 (+ 10.9.9.9/32 off-link)
//	server ns: tsA  100.64.0.5/24     <-veth->  peer ns: tsB  100.64.0.9/24 (+ 192.168.50.30/32, LAN-looking)
//
// The responder runs on lanA only. Queries are sent from the peer ns.
const (
	nsRoleEnv  = "ARISTOTLE_MDNS_NS_ROLE" // "server" or "query"
	nsQueryEnv = "ARISTOTLE_MDNS_NS_QUERY"
	nsTestName = "TestNamespaceResponderStaysOnLAN"
	nsSkip     = "MDNS-NS-SKIP "
	nsResult   = "MDNS-NS-RESULT "
)

func TestNamespaceResponderStaysOnLAN(t *testing.T) {
	switch os.Getenv(nsRoleEnv) {
	case "server":
		nsServer(t)
		return
	case "query":
		nsQuery(t)
		return
	}
	if testing.Short() {
		t.Skip("namespace test skipped in -short mode")
	}
	for _, tool := range []string{"unshare", "nsenter", "ip"} {
		if _, err := exec.LookPath(tool); err != nil {
			t.Skipf("%s not found: cannot build a private network namespace", tool)
		}
	}
	if out, err := exec.Command("unshare", "-rn", "true").CombinedOutput(); err != nil {
		t.Skipf("unshare -rn not available (unprivileged user+net namespaces disabled?): %v %s", err, out)
	}
	cmd := exec.Command("unshare", "-rn", os.Args[0], "-test.run=^"+nsTestName+"$", "-test.count=1")
	cmd.Env = append(os.Environ(), nsRoleEnv+"=server")
	out, err := cmd.CombinedOutput()
	if _, reason, ok := strings.Cut(string(out), nsSkip); ok {
		t.Skip(strings.TrimSpace(reason))
	}
	if err != nil {
		t.Fatalf("namespace run failed: %v\n%s", err, out)
	}
}

// nsServer runs inside `unshare -rn`: it builds the topology, starts the
// responder on lanA and checks what each query gets.
func nsServer(t *testing.T) {
	peer := nsTopology(t)

	// Announcements: twice at start.
	l1 := nsQueryAsync(t, peer, nsQ{Iface: "lanB", Wait: 3 * time.Second})
	time.Sleep(400 * time.Millisecond)
	r, err := MDNSStart(Spec{Iface: "lanA", Instance: "Aristotle on nstest", Target: TargetHost("nstest"), Port: 8761,
		Addrs: []netip.Prefix{netip.MustParsePrefix("192.168.50.10/24")}, TXT: []string{"v=1", "fp=0123456789abcdef", "proto=1"}})
	if err != nil {
		t.Fatalf("MDNSStart: %v", err)
	}
	stopped := false
	defer func() {
		if !stopped {
			_ = r.Stop()
		}
	}()
	if got := l1(); got.Announce < 2 {
		t.Errorf("start-up announcements seen on lanB: %d, want 2 (%+v)", got.Announce, got)
	}

	nsCheckSockets(t, 1)

	group := "224.0.0.251:5353"
	// Every query that must get nothing comes first: the 1 s multicast rate
	// limit must not be what hides an answer.
	silent := []struct {
		what string
		q    nsQ
	}{
		{"unicast query to the LAN address", nsQ{Iface: "lanB", Src: "192.168.50.20", SrcPort: 5353, Dst: "192.168.50.10:5353"}},
		{"legacy unicast query to the LAN address", nsQ{Iface: "lanB", Src: "192.168.50.20", Dst: "192.168.50.10:5353"}},
		{"unicast query to the Tailscale address", nsQ{Iface: "tsB", Src: "100.64.0.9", SrcPort: 5353, Dst: "100.64.0.5:5353"}},
		{"multicast query on the Tailscale link", nsQ{Iface: "tsB", Src: "100.64.0.9", SrcPort: 5353, Dst: group}},
		{"multicast query on the LAN from an off-link source", nsQ{Iface: "lanB", Src: "10.9.9.9", SrcPort: 5353, Dst: group}},
	}
	for _, c := range silent {
		if got := nsQuery1(t, peer, c.q); got.Answers != 0 {
			t.Errorf("%s: answered (%+v)", c.what, got)
		}
	}
	// An avahi-like wildcard socket joined on tsA must not make tsA traffic reach us.
	avahi, err := net.ListenMulticastUDP("udp4", mustIface(t, "tsA"), &net.UDPAddr{IP: net.IPv4(224, 0, 0, 251), Port: 5353})
	if err != nil {
		t.Fatalf("avahi-like socket (coexistence on 5353): %v", err)
	}
	for _, src := range []string{"100.64.0.9", "192.168.50.30"} {
		if got := nsQuery1(t, peer, nsQ{Iface: "tsB", Src: src, SrcPort: 5353, Dst: group}); got.Answers != 0 {
			t.Errorf("multicast query on tsB from %s with a group member on tsA: answered (%+v)", src, got)
		}
	}
	_ = avahi.Close()

	if got := nsQuery1(t, peer, nsQ{Iface: "lanB", Src: "192.168.50.20", SrcPort: 5353, Dst: group}); got.Answers == 0 || !got.LANAddr || !got.FP {
		t.Errorf("multicast query on the LAN: %+v, want an answer with A 192.168.50.10 and fp", got)
	}
	if got := nsQuery1(t, peer, nsQ{Iface: "lanB", Src: "192.168.50.20", Dst: group}); got.Answers == 0 || got.Unicast == 0 {
		t.Errorf("legacy multicast query on the LAN: %+v, want a unicast answer", got)
	}

	// Goodbye on stop.
	l2 := nsQueryAsync(t, peer, nsQ{Iface: "lanB", Wait: 1500 * time.Millisecond})
	time.Sleep(400 * time.Millisecond)
	stopped = true
	if err := r.Stop(); err != nil {
		t.Errorf("Stop: %v", err)
	}
	if got := l2(); got.Goodbye == 0 {
		t.Errorf("no goodbye seen on lanB: %+v", got)
	}
	nsCheckSockets(t, 0)
}

// nsTopology builds the two veth pairs; it returns the peer namespace's pid.
func nsTopology(t *testing.T) int {
	sh := func(args ...string) {
		t.Helper()
		if out, err := exec.Command(args[0], args[1:]...).CombinedOutput(); err != nil {
			t.Fatalf("%v: %v %s", args, err, out)
		}
	}
	sh("ip", "link", "set", "lo", "up")
	peer := exec.Command("unshare", "-n", "sleep", "120")
	if err := peer.Start(); err != nil {
		t.Fatalf("peer namespace: %v", err)
	}
	t.Cleanup(func() { _ = peer.Process.Kill(); _ = peer.Wait() })
	pid := peer.Process.Pid
	self, _ := os.Readlink("/proc/self/ns/net")
	for deadline := time.Now().Add(3 * time.Second); ; time.Sleep(10 * time.Millisecond) {
		if ns, err := os.Readlink(fmt.Sprintf("/proc/%d/ns/net", pid)); err == nil && ns != self {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("peer namespace never appeared")
		}
	}
	if out, err := exec.Command("ip", "link", "add", "lanA", "type", "veth", "peer", "name", "lanB").CombinedOutput(); err != nil {
		fmt.Println(nsSkip + "veth interfaces unavailable in a user namespace: " + strings.TrimSpace(string(out)))
		t.Skip("veth unavailable")
	}
	sh("ip", "link", "add", "tsA", "type", "veth", "peer", "name", "tsB")
	p := strconv.Itoa(pid)
	sh("ip", "link", "set", "lanB", "netns", p)
	sh("ip", "link", "set", "tsB", "netns", p)
	sh("ip", "addr", "add", "192.168.50.10/24", "dev", "lanA")
	sh("ip", "addr", "add", "100.64.0.5/24", "dev", "tsA")
	sh("ip", "link", "set", "lanA", "up")
	sh("ip", "link", "set", "tsA", "up")
	// Routes back to the odd sources, so rp_filter delivers their packets and only the responder can drop them.
	sh("ip", "route", "add", "10.9.9.9/32", "dev", "lanA")
	sh("ip", "route", "add", "192.168.50.30/32", "dev", "tsA")
	in := func(args ...string) { sh(append([]string{"nsenter", "-t", p, "-n"}, args...)...) }
	in("ip", "link", "set", "lo", "up")
	in("ip", "addr", "add", "192.168.50.20/24", "dev", "lanB")
	in("ip", "addr", "add", "10.9.9.9/32", "dev", "lanB")
	in("ip", "addr", "add", "100.64.0.9/24", "dev", "tsB")
	in("ip", "addr", "add", "192.168.50.30/32", "dev", "tsB")
	in("ip", "link", "set", "lanB", "up")
	in("ip", "link", "set", "tsB", "up")
	for _, name := range []string{"lanA", "tsA"} {
		for deadline := time.Now().Add(3 * time.Second); mustIface(t, name).Flags&net.FlagRunning == 0; time.Sleep(10 * time.Millisecond) {
			if time.Now().After(deadline) {
				t.Fatalf("%s never came up", name)
			}
		}
	}
	return pid
}

func mustIface(t *testing.T, name string) *net.Interface {
	t.Helper()
	ifc, err := net.InterfaceByName(name)
	if err != nil {
		t.Fatal(err)
	}
	return ifc
}

// nsCheckSockets is the `ss -uln` check: want sockets on 224.0.0.251:5353,
// none on 0.0.0.0:5353, none on IPv6 5353.
func nsCheckSockets(t *testing.T, want int) {
	t.Helper()
	group := mdnsGroup.As4()
	groupHex := fmt.Sprintf("%08X:14E9", binary.NativeEndian.Uint32(group[:]))
	got := 0
	for _, file := range []string{"/proc/net/udp", "/proc/net/udp6"} {
		for _, local := range procLocalAddrs(t, file) {
			if !strings.HasSuffix(local, ":14E9") {
				continue
			}
			if file != "/proc/net/udp" || local != groupHex {
				t.Errorf("%s has a 5353 socket on %s; want only %s", file, local, groupHex)
				continue
			}
			got++
		}
	}
	if got != want {
		t.Errorf("%d sockets on 224.0.0.251:5353, want %d", got, want)
	}
}

func procLocalAddrs(t *testing.T, file string) []string {
	f, err := os.Open(file)
	if err != nil {
		if os.IsNotExist(err) {
			return nil
		}
		t.Fatal(err)
	}
	defer f.Close()
	var out []string
	sc := bufio.NewScanner(f)
	sc.Scan() // header
	for sc.Scan() {
		if fields := strings.Fields(sc.Text()); len(fields) > 1 {
			out = append(out, fields[1])
		}
	}
	return out
}

// nsQ is one querier run in the peer namespace. With Dst empty it only listens.
type nsQ struct {
	Iface   string
	Src     string
	SrcPort int
	Dst     string
	Wait    time.Duration
}

// nsOut is what a querier saw: responses about our records.
type nsOut struct {
	Answers  int // responses to the query (multicast or unicast)
	Unicast  int // of which unicast to the sender socket
	Announce int // unsolicited responses with live records (listen mode)
	Goodbye  int // responses with TTL 0
	LANAddr  bool
	FP       bool
}

func nsQuery1(t *testing.T, peer int, q nsQ) nsOut {
	t.Helper()
	if q.Wait == 0 {
		q.Wait = 800 * time.Millisecond
	}
	return nsQueryAsync(t, peer, q)()
}

// nsQueryAsync starts a querier in the peer namespace; the returned func waits for its result.
func nsQueryAsync(t *testing.T, peer int, q nsQ) func() nsOut {
	t.Helper()
	cmd := exec.Command("nsenter", "-t", strconv.Itoa(peer), "-n", os.Args[0], "-test.run=^"+nsTestName+"$", "-test.count=1")
	cmd.Env = append(os.Environ(), nsRoleEnv+"=query",
		fmt.Sprintf("%s=%s,%s,%d,%s,%d", nsQueryEnv, q.Iface, q.Src, q.SrcPort, q.Dst, q.Wait.Milliseconds()))
	var buf strings.Builder
	cmd.Stdout, cmd.Stderr = &buf, &buf
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	return func() nsOut {
		t.Helper()
		err := cmd.Wait()
		_, line, ok := strings.Cut(buf.String(), nsResult)
		var o nsOut
		if !ok || err != nil {
			t.Fatalf("querier %+v failed: %v\n%s", q, err, buf.String())
		}
		line, _, _ = strings.Cut(line, "\n")
		if _, err := fmt.Sscanf(line, "%d %d %d %d %t %t", &o.Answers, &o.Unicast, &o.Announce, &o.Goodbye, &o.LANAddr, &o.FP); err != nil {
			t.Fatalf("querier output %q: %v", line, err)
		}
		return o
	}
}

// nsQuery runs in the peer namespace: listen on the group (and send one PTR
// query out of the given interface when a destination is given), then print
// what came back.
func nsQuery(t *testing.T) {
	f := strings.Split(os.Getenv(nsQueryEnv), ",")
	if len(f) != 5 {
		t.Fatalf("bad %s", nsQueryEnv)
	}
	srcPort, _ := strconv.Atoi(f[2])
	waitMS, _ := strconv.Atoi(f[4])
	ifc := mustIface(t, f[0])
	reuse := net.ListenConfig{Control: func(_, _ string, rc syscall.RawConn) error {
		return rc.Control(func(fd uintptr) { _ = syscall.SetsockoptInt(int(fd), syscall.SOL_SOCKET, syscall.SO_REUSEADDR, 1) })
	}}
	lc, err := reuse.ListenPacket(context.Background(), "udp4", "0.0.0.0:5353")
	if err != nil {
		t.Fatal(err)
	}
	defer lc.Close()
	// Listen on every link of the peer namespace, so an answer leaking out of the wrong interface is seen too.
	group := &net.UDPAddr{IP: net.IPv4(224, 0, 0, 251)}
	for _, name := range []string{"lanB", "tsB"} {
		if err := ipv4.NewPacketConn(lc).JoinGroup(mustIface(t, name), group); err != nil {
			t.Fatal(err)
		}
	}
	var (
		out nsOut
		mu  sync.Mutex
	)
	deadline := time.Now().Add(time.Duration(waitMS) * time.Millisecond)
	done := make(chan struct{}, 2)
	collect := func(c net.PacketConn, unicast bool) {
		defer func() { done <- struct{}{} }()
		_ = c.SetReadDeadline(deadline)
		b := make([]byte, 9000)
		for {
			n, _, err := c.ReadFrom(b)
			if err != nil {
				return
			}
			mu.Lock()
			nsTally(&out, b[:n], unicast, f[3] == "")
			mu.Unlock()
		}
	}
	readers := 1
	if f[3] != "" {
		sc, err := reuse.ListenPacket(context.Background(), "udp4", net.JoinHostPort(f[1], strconv.Itoa(srcPort)))
		if err != nil {
			t.Fatal(err)
		}
		defer sc.Close()
		sp := ipv4.NewPacketConn(sc)
		if err := sp.SetMulticastInterface(ifc); err != nil {
			t.Fatal(err)
		}
		msg, _ := (&dnsmessage.Message{Header: dnsmessage.Header{ID: 7}, Questions: []dnsmessage.Question{
			{Name: dnsmessage.MustNewName(svcName), Type: dnsmessage.TypePTR, Class: dnsmessage.ClassINET}}}).Pack()
		if _, err := sc.WriteTo(msg, net.UDPAddrFromAddrPort(netip.MustParseAddrPort(f[3]))); err != nil {
			t.Fatal(err)
		}
		readers++
		go collect(sc, true)
	}
	go collect(lc, false)
	for range readers {
		<-done
	}
	fmt.Printf("%s%d %d %d %d %t %t\n", nsResult, out.Answers, out.Unicast, out.Announce, out.Goodbye, out.LANAddr, out.FP)
}

// nsTally counts a received message if it is a response carrying our records.
func nsTally(out *nsOut, b []byte, unicast, listening bool) {
	var m dnsmessage.Message
	if m.Unpack(b) != nil || !m.Header.Response {
		return
	}
	ours, live := false, false
	for _, r := range append(m.Answers, m.Additionals...) {
		n := strings.ToLower(r.Header.Name.String())
		if !strings.HasSuffix(n, "._aristotle._tcp.local.") && n != svcName && n != TargetHost("nstest") {
			continue
		}
		ours = true
		if r.Header.TTL > 0 {
			live = true
		}
		switch body := r.Body.(type) {
		case *dnsmessage.AResource:
			out.LANAddr = out.LANAddr || body.A == [4]byte{192, 168, 50, 10}
		case *dnsmessage.TXTResource:
			out.FP = out.FP || strings.Contains(strings.Join(body.TXT, " "), "fp=")
		}
	}
	switch {
	case !ours:
	case !live:
		out.Goodbye++
	case listening:
		out.Announce++
	default:
		out.Answers++
		if unicast {
			out.Unicast++
		}
	}
}
