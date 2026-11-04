package discovery

import (
	"net/netip"
	"testing"

	"golang.org/x/net/dns/dnsmessage"
)

const (
	wlanIndex      = 3
	tailscaleIndex = 7
	dockerIndex    = 9
)

var wlan = link{index: wlanIndex, subnets: []netip.Prefix{netip.MustParsePrefix("192.168.1.0/24")}}

// query packs a standard query; qu sets the unicast-response bit on every question.
func query(t *testing.T, id uint16, qu bool, qs ...dnsmessage.Question) []byte {
	t.Helper()
	if qu {
		for i := range qs {
			qs[i].Class |= cacheFlush
		}
	}
	b, err := (&dnsmessage.Message{Header: dnsmessage.Header{ID: id}, Questions: qs}).Pack()
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func ptrQuery(t *testing.T) []byte { return query(t, 0, false, q(svcName, dnsmessage.TypePTR)) }

func pkt(ifIndex int, src, dst string, data []byte) inbound {
	return inbound{ifIndex: ifIndex, src: netip.MustParseAddrPort(src), dst: netip.MustParseAddr(dst), data: data}
}

func TestFilterDropsForeignPackets(t *testing.T) {
	z := testZone(t)
	resp, _ := announcement(z.all(false))
	cases := []struct {
		name   string
		in     inbound
		reason string
	}{
		{"tailscale0 arrival", pkt(tailscaleIndex, "100.64.0.9:5353", "224.0.0.251", ptrQuery(t)), "other interface"},
		{"tailscale0 arrival with a LAN-looking source", pkt(tailscaleIndex, "192.168.1.20:5353", "224.0.0.251", ptrQuery(t)), "other interface"},
		{"docker0 arrival", pkt(dockerIndex, "172.17.0.2:5353", "224.0.0.251", ptrQuery(t)), "other interface"},
		{"off-link source on our interface", pkt(wlanIndex, "10.9.9.9:5353", "224.0.0.251", ptrQuery(t)), "off-link source"},
		{"tailnet source on our interface", pkt(wlanIndex, "100.64.0.9:5353", "224.0.0.251", ptrQuery(t)), "off-link source"},
		{"unicast query to our address", pkt(wlanIndex, "192.168.1.20:5353", "192.168.1.5", ptrQuery(t)), "not sent to the mDNS group"},
		{"unicast legacy query to our address", pkt(wlanIndex, "192.168.1.20:40000", "192.168.1.5", ptrQuery(t)), "not sent to the mDNS group"},
		{"a response, not a query", pkt(wlanIndex, "192.168.1.20:5353", "224.0.0.251", resp), "not a query"},
		{"garbage", pkt(wlanIndex, "192.168.1.20:5353", "224.0.0.251", []byte{1, 2, 3}), "malformed"},
		{"someone else's name", pkt(wlanIndex, "192.168.1.20:5353", "224.0.0.251",
			query(t, 0, false, q("laptop.local.", dnsmessage.TypeA))), "no question for us"},
	}
	for _, c := range cases {
		p := z.decide(wlan, c.in)
		if p.mode != drop || p.reason != c.reason {
			t.Errorf("%s: mode %d reason %q, want drop %q", c.name, p.mode, p.reason, c.reason)
		}
	}
}

func TestFilterAnswersOnLinkQueries(t *testing.T) {
	z := testZone(t)
	group := netip.MustParseAddrPort("224.0.0.251:5353")

	p := z.decide(wlan, pkt(wlanIndex, "192.168.1.20:5353", "224.0.0.251", ptrQuery(t)))
	if p.mode != multicast || p.to != group || len(p.answers) != 1 || len(p.extras) != 3 {
		t.Fatalf("QM: %+v", p)
	}

	p = z.decide(wlan, pkt(wlanIndex, "192.168.1.20:5353", "224.0.0.251", query(t, 0, true, q(svcName, dnsmessage.TypePTR))))
	if p.mode != unicastQU || p.to.String() != "192.168.1.20:5353" {
		t.Fatalf("QU: %+v", p)
	}

	// A QU question for us next to a QM one for us: multicast.
	mixed := query(t, 0, false, q(svcName, dnsmessage.TypePTR), q(hostName, dnsmessage.TypeA))
	if p = z.decide(wlan, pkt(wlanIndex, "192.168.1.20:5353", "224.0.0.251", mixed)); p.mode != multicast {
		t.Fatalf("mixed: %+v", p)
	}
}

func TestLegacyQueryGetsUnicastReplyWithShortTTL(t *testing.T) {
	z := testZone(t)
	p := z.decide(wlan, pkt(wlanIndex, "192.168.1.20:40000", "224.0.0.251", query(t, 0x1234, false, q(svcName, dnsmessage.TypePTR))))
	if p.mode != legacy || p.to.String() != "192.168.1.20:40000" {
		t.Fatalf("legacy: %+v", p)
	}
	b, err := p.pack()
	if err != nil {
		t.Fatal(err)
	}
	var m dnsmessage.Message
	if err := m.Unpack(b); err != nil {
		t.Fatal(err)
	}
	if m.Header.ID != 0x1234 || !m.Header.Response || len(m.Questions) != 1 || len(m.Answers) != 1 {
		t.Fatalf("legacy reply %+v", m.Header)
	}
	for _, r := range append(m.Answers, m.Additionals...) {
		if r.Header.TTL > ttlLegacy || r.Header.Class&cacheFlush != 0 {
			t.Fatalf("legacy record %s ttl %d class %#x", r.Header.Type, r.Header.TTL, r.Header.Class)
		}
	}
}
