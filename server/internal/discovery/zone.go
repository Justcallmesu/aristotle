package discovery

import (
	"fmt"
	"strings"

	"golang.org/x/net/dns/dnsmessage"
)

// Record TTLs (RFC 6762 §10): host-bound records short, the rest long.
const (
	ttlHost   = 120  // SRV and A
	ttlOther  = 4500 // PTR and TXT
	ttlLegacy = 10   // cap for legacy unicast replies (RFC 6762 §6.7)
)

// cacheFlush is the class top bit on unique records (RFC 6762 §10.2) and the
// QU bit on questions (§5.4).
const cacheFlush = 0x8000

const maxLabel = 63

// zone is the record set one interface announces: the service PTR, the
// instance SRV and TXT, and the A records of a target host name we own.
type zone struct {
	service  dnsmessage.Name // _aristotle._tcp.local.
	instance dnsmessage.Name // Aristotle on <host>._aristotle._tcp.local.
	target   dnsmessage.Name // aristotle-<host>.local.
	port     uint16
	addrs    [][4]byte
	txt      []string
}

func newZone(s Spec) (*zone, error) {
	if s.Port <= 0 || s.Port > 0xffff {
		return nil, fmt.Errorf("port %d out of range", s.Port)
	}
	z := &zone{port: uint16(s.Port), txt: s.TXT}
	for _, p := range s.Addrs {
		if !p.Addr().Is4() {
			return nil, fmt.Errorf("address %s is not IPv4", p)
		}
		z.addrs = append(z.addrs, p.Addr().As4())
	}
	if len(z.addrs) == 0 {
		return nil, fmt.Errorf("no address to announce")
	}
	var err error
	if z.service, err = dnsmessage.NewName(ServiceType + ".local."); err != nil {
		return nil, err
	}
	if z.instance, err = dnsmessage.NewName(label(s.Instance) + "." + ServiceType + ".local."); err != nil {
		return nil, err
	}
	if z.target, err = dnsmessage.NewName(s.Target); err != nil {
		return nil, err
	}
	return z, nil
}

// label makes s one DNS label: no dots, at most 63 bytes, cut on a rune boundary.
func label(s string) string {
	s = strings.ReplaceAll(s, ".", "-")
	for len(s) > maxLabel {
		r := []rune(s)
		s = string(r[:len(r)-1])
	}
	return s
}

// TargetHost is the mDNS host name the A records use: "aristotle-<short
// hostname>.local.", lowercase letters, digits and hyphens only. It is never
// "<host>.local.", which avahi owns.
func TargetHost(host string) string {
	var b strings.Builder
	for _, r := range strings.ToLower(ShortHost(host)) {
		if (r >= 'a' && r <= 'z') || (r >= '0' && r <= '9') {
			b.WriteRune(r)
		} else {
			b.WriteByte('-')
		}
	}
	l := strings.Trim("aristotle-"+b.String(), "-")
	if len(l) > maxLabel {
		l = strings.TrimRight(l[:maxLabel], "-")
	}
	return l + ".local."
}

func header(name dnsmessage.Name, t dnsmessage.Type, unique bool, ttl uint32) dnsmessage.ResourceHeader {
	class := dnsmessage.ClassINET
	if unique {
		class |= cacheFlush
	}
	return dnsmessage.ResourceHeader{Name: name, Type: t, Class: class, TTL: ttl}
}

// ttl returns full when live, 0 for a goodbye.
func ttl(full uint32, goodbye bool) uint32 {
	if goodbye {
		return 0
	}
	return full
}

func (z *zone) ptr(goodbye bool) dnsmessage.Resource {
	return dnsmessage.Resource{Header: header(z.service, dnsmessage.TypePTR, false, ttl(ttlOther, goodbye)),
		Body: &dnsmessage.PTRResource{PTR: z.instance}}
}

func (z *zone) srv(goodbye bool) dnsmessage.Resource {
	return dnsmessage.Resource{Header: header(z.instance, dnsmessage.TypeSRV, true, ttl(ttlHost, goodbye)),
		Body: &dnsmessage.SRVResource{Port: z.port, Target: z.target}}
}

func (z *zone) text(goodbye bool) dnsmessage.Resource {
	return dnsmessage.Resource{Header: header(z.instance, dnsmessage.TypeTXT, true, ttl(ttlOther, goodbye)),
		Body: &dnsmessage.TXTResource{TXT: z.txt}}
}

func (z *zone) a(goodbye bool) []dnsmessage.Resource {
	out := make([]dnsmessage.Resource, 0, len(z.addrs))
	for _, ad := range z.addrs {
		out = append(out, dnsmessage.Resource{Header: header(z.target, dnsmessage.TypeA, true, ttl(ttlHost, goodbye)),
			Body: &dnsmessage.AResource{A: ad}})
	}
	return out
}

// all is the full record set: the announcement, or with goodbye the TTL-0 withdrawal.
func (z *zone) all(goodbye bool) []dnsmessage.Resource {
	return append([]dnsmessage.Resource{z.ptr(goodbye), z.srv(goodbye), z.text(goodbye)}, z.a(goodbye)...)
}

// answer returns the records that answer qs, and the additional records
// (RFC 6763 §12) that go with them. Questions for other names get nothing.
func (z *zone) answer(qs []dnsmessage.Question) (answers, extras []dnsmessage.Resource) {
	var ans, ext set
	for _, q := range qs {
		if c := q.Class &^ cacheFlush; c != dnsmessage.ClassINET && c != dnsmessage.ClassANY {
			continue
		}
		switch {
		case sameName(q.Name, z.service) && wants(q, dnsmessage.TypePTR):
			ans.add(z.ptr(false))
			ext.add(z.srv(false), z.text(false))
			ext.add(z.a(false)...)
		case sameName(q.Name, z.instance):
			if wants(q, dnsmessage.TypeSRV) {
				ans.add(z.srv(false))
				ext.add(z.a(false)...)
			}
			if wants(q, dnsmessage.TypeTXT) {
				ans.add(z.text(false))
			}
		case sameName(q.Name, z.target) && wants(q, dnsmessage.TypeA):
			ans.add(z.a(false)...)
		}
	}
	return ans.list, ext.without(ans)
}

func wants(q dnsmessage.Question, t dnsmessage.Type) bool {
	return q.Type == t || q.Type == dnsmessage.TypeALL
}

func sameName(a, b dnsmessage.Name) bool { return strings.EqualFold(a.String(), b.String()) }

// key identifies one record set for rate limiting and de-duplication.
type key struct {
	name string
	typ  dnsmessage.Type
}

func keyOf(r dnsmessage.Resource) key {
	return key{strings.ToLower(r.Header.Name.String()), r.Header.Type}
}

// set is an ordered list of records with at most one copy of each record set.
type set struct {
	list []dnsmessage.Resource
	seen map[key]bool
}

func (s *set) add(rs ...dnsmessage.Resource) {
	if s.seen == nil {
		s.seen = map[key]bool{}
	}
	added := map[key]bool{}
	for _, r := range rs {
		k := keyOf(r)
		if s.seen[k] && !added[k] {
			continue
		}
		s.seen[k], added[k] = true, true
		s.list = append(s.list, r)
	}
}

func (s *set) without(o set) []dnsmessage.Resource {
	var out []dnsmessage.Resource
	for _, r := range s.list {
		if !o.seen[keyOf(r)] {
			out = append(out, r)
		}
	}
	return out
}
