package discovery

import (
	"net/netip"
	"slices"
	"strconv"
	"strings"
	"testing"
	"unicode/utf8"

	"golang.org/x/net/dns/dnsmessage"
)

func testSpec() Spec {
	return Spec{Iface: "wlan0", Instance: "Aristotle on laptop", Target: TargetHost("laptop", ""), Port: 8760,
		Addrs: []netip.Prefix{netip.MustParsePrefix("192.168.1.5/24")}, TXT: []string{"v=1", "fp=ab12cd34ef567890", "proto=1"}}
}

func testZone(t *testing.T) *zone {
	t.Helper()
	z, err := newZone(testSpec())
	if err != nil {
		t.Fatal(err)
	}
	return z
}

func name(s string) dnsmessage.Name { return dnsmessage.MustNewName(s) }

// summary renders records as "name type ttl flush" lines for comparison.
func summary(rs []dnsmessage.Resource) []string {
	var out []string
	for _, r := range rs {
		flush := ""
		if r.Header.Class&cacheFlush != 0 {
			flush = " flush"
		}
		out = append(out, r.Header.Name.String()+" "+r.Header.Type.String()+" "+strconv.Itoa(int(r.Header.TTL))+flush)
	}
	return out
}

const (
	svcName  = "_aristotle._tcp.local."
	instName = "Aristotle on laptop._aristotle._tcp.local."
	hostName = "aristotle-laptop.local."
)

func TestRecordSetAndTTLs(t *testing.T) {
	z := testZone(t)
	want := []string{
		svcName + " TypePTR 4500",
		instName + " TypeSRV 120 flush",
		instName + " TypeTXT 4500 flush",
		hostName + " TypeA 120 flush",
	}
	if got := summary(z.all(false)); !slices.Equal(got, want) {
		t.Fatalf("records\n%v\nwant\n%v", got, want)
	}
	all := z.all(false)
	if srv := all[1].Body.(*dnsmessage.SRVResource); srv.Port != 8760 || srv.Target.String() != hostName {
		t.Fatalf("srv %+v", srv)
	}
	if ptr := all[0].Body.(*dnsmessage.PTRResource); ptr.PTR.String() != instName {
		t.Fatalf("ptr %+v", ptr)
	}
	if txt := all[2].Body.(*dnsmessage.TXTResource); !slices.Equal(txt.TXT, []string{"v=1", "fp=ab12cd34ef567890", "proto=1"}) {
		t.Fatalf("txt %+v", txt)
	}
	if a := all[3].Body.(*dnsmessage.AResource); a.A != [4]byte{192, 168, 1, 5} {
		t.Fatalf("a %+v", a)
	}
	if _, err := announcement(all); err != nil {
		t.Fatalf("pack: %v", err)
	}
}

func TestGoodbyeHasTTLZero(t *testing.T) {
	for _, r := range testZone(t).all(true) {
		if r.Header.TTL != 0 {
			t.Fatalf("goodbye %s %s has TTL %d", r.Header.Name, r.Header.Type, r.Header.TTL)
		}
	}
}

func TestTargetHostIsOurs(t *testing.T) {
	cases := map[string]string{
		"laptop":             "aristotle-laptop.local.",
		"Laptop.example.net": "aristotle-laptop.local.",
		"my_PC 2":            "aristotle-my-pc-2.local.",
		"":                   "aristotle-pc.local.",
	}
	for in, want := range cases {
		if got := TargetHost(in, ""); got != want {
			t.Errorf("TargetHost(%q) = %q, want %q", in, got, want)
		}
	}
	long := TargetHost("a123456789b123456789c123456789d123456789e123456789f123456789g123456789", "")
	if l := len(long) - len(".local."); l > 63 {
		t.Fatalf("label %d bytes", l)
	}
}

func TestZoneRefusesNonIPv4(t *testing.T) {
	s := testSpec()
	s.Addrs = []netip.Prefix{netip.MustParsePrefix("fd00::5/64")}
	if _, err := newZone(s); err == nil {
		t.Fatal("IPv6 address accepted")
	}
}

func q(n string, t dnsmessage.Type) dnsmessage.Question {
	return dnsmessage.Question{Name: name(n), Type: t, Class: dnsmessage.ClassINET}
}

func TestAnswerMatchesOnlyOurQuestions(t *testing.T) {
	z := testZone(t)
	cases := []struct {
		q            dnsmessage.Question
		ans, extras  []string
		descriptions string
	}{
		{q(svcName, dnsmessage.TypePTR), []string{"TypePTR"}, []string{"TypeSRV", "TypeTXT", "TypeA"}, "service PTR"},
		{q("_ARISTOTLE._tcp.local.", dnsmessage.TypePTR), []string{"TypePTR"}, []string{"TypeSRV", "TypeTXT", "TypeA"}, "case-insensitive"},
		{q(instName, dnsmessage.TypeSRV), []string{"TypeSRV"}, []string{"TypeA"}, "instance SRV"},
		{q(instName, dnsmessage.TypeTXT), []string{"TypeTXT"}, nil, "instance TXT"},
		{q(instName, dnsmessage.TypeALL), []string{"TypeSRV", "TypeTXT"}, []string{"TypeA"}, "instance ANY"},
		{q(hostName, dnsmessage.TypeA), []string{"TypeA"}, nil, "target A"},
		{q(hostName, dnsmessage.TypeAAAA), nil, nil, "target AAAA"},
		{q("laptop.local.", dnsmessage.TypeA), nil, nil, "avahi's host name"},
		{q("_services._dns-sd._udp.local.", dnsmessage.TypePTR), nil, nil, "service enumeration"},
		{q("_http._tcp.local.", dnsmessage.TypePTR), nil, nil, "other service"},
		{dnsmessage.Question{Name: name(svcName), Type: dnsmessage.TypePTR, Class: dnsmessage.ClassCHAOS}, nil, nil, "other class"},
	}
	types := func(rs []dnsmessage.Resource) []string {
		var out []string
		for _, r := range rs {
			out = append(out, r.Header.Type.String())
		}
		return out
	}
	for _, c := range cases {
		a, e := z.answer([]dnsmessage.Question{c.q})
		if !slices.Equal(types(a), c.ans) || !slices.Equal(types(e), c.extras) {
			t.Errorf("%s: answers %v extras %v, want %v %v", c.descriptions, types(a), types(e), c.ans, c.extras)
		}
	}
}

const longHost = "a123456789b123456789c123456789d123456789e123456789f123456789g123456789"

func TestNamesCarryFingerprintHint(t *testing.T) {
	const fpA, fpB = "AB12CD34EF567890", "99887766554433"
	if InstanceName("pc", fpA) == InstanceName("pc", fpB) || TargetHost("pc", fpA) == TargetHost("pc", fpB) {
		t.Fatal("same hostname, different fingerprints share a name")
	}
	if got := InstanceName("pc.lan", fpA); got != "Aristotle on pc (ab12cd34)" {
		t.Fatalf("instance %q", got)
	}
	if got := TargetHost("PC", fpA); got != "aristotle-pc-ab12cd34.local." {
		t.Fatalf("target %q", got)
	}
	for _, host := range []string{longHost, "Zażółć-gęślą-jaźń-Zażółć-gęślą-jaźń-Zażółć-gęślą-jaźń", "日本語のホスト名日本語のホスト名日本語のホスト名日本語のホスト名"} {
		inst := InstanceName(host, fpA)
		if len(inst) > maxLabel || !utf8.ValidString(inst) || !strings.HasSuffix(inst, " (ab12cd34)") {
			t.Errorf("instance %q (%d bytes)", inst, len(inst))
		}
		tgt := TargetHost(host, fpA)
		l := strings.TrimSuffix(tgt, ".local.")
		if len(l) > maxLabel || !strings.HasSuffix(l, "-ab12cd34") || strings.Trim(l, "abcdefghijklmnopqrstuvwxyz0123456789-") != "" {
			t.Errorf("target %q", tgt)
		}
		s := testSpec()
		s.Instance, s.Target = inst, tgt
		if _, err := newZone(s); err != nil {
			t.Errorf("zone for %q: %v", host, err)
		}
	}
	if got := InstanceName("日本語", fpA); got != "Aristotle on 日本語 (ab12cd34)" {
		t.Fatalf("non-ASCII instance %q", got)
	}
}
