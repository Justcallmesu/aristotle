package discovery

import (
	"net/netip"

	"golang.org/x/net/dns/dnsmessage"
)

// mdnsPort is the mDNS port; mdnsGroup the IPv4 mDNS group (RFC 6762 §3).
const mdnsPort = 5353

var mdnsGroup = netip.AddrFrom4([4]byte{224, 0, 0, 251})

// link is the interface a responder serves: its index and on-link prefixes.
type link struct {
	index   int
	subnets []netip.Prefix
}

func (l link) onLink(a netip.Addr) bool {
	for _, p := range l.subnets {
		if p.Contains(a) {
			return true
		}
	}
	return false
}

// inbound is one received datagram with its IP_PKTINFO metadata.
type inbound struct {
	ifIndex int            // arrival interface
	src     netip.AddrPort // sender
	dst     netip.Addr     // IP header destination
	data    []byte
}

// replyMode is how a reply goes out.
type replyMode int

const (
	drop      replyMode = iota
	multicast           // to 224.0.0.251:5353 on the interface (QM question)
	unicastQU           // to the asker (every answered question had the QU bit)
	legacy              // to the asker's port, ID and questions echoed, TTL ≤ 10 (RFC 6762 §6.7)
)

// plan is the decision for one inbound packet.
type plan struct {
	mode      replyMode
	reason    string // why it was dropped
	to        netip.AddrPort
	id        uint16
	questions []dnsmessage.Question
	answers   []dnsmessage.Resource
	extras    []dnsmessage.Resource
}

func dropped(reason string) plan { return plan{mode: drop, reason: reason} }

// decide is the packet filter: it drops everything that did not arrive on
// our interface, was not sent to the mDNS group (a unicast query), comes
// from an off-link source, is not a standard query, or asks nothing about our
// records; otherwise it says what to answer and where.
func (z *zone) decide(l link, in inbound) plan {
	switch {
	case in.ifIndex != l.index:
		return dropped("other interface")
	case in.dst != mdnsGroup:
		return dropped("not sent to the mDNS group")
	case !in.src.Addr().Is4() || !l.onLink(in.src.Addr()):
		return dropped("off-link source")
	}
	var p dnsmessage.Parser
	h, err := p.Start(in.data)
	if err != nil {
		return dropped("malformed")
	}
	if h.Response || h.OpCode != 0 {
		return dropped("not a query")
	}
	qs, err := p.AllQuestions()
	if err != nil {
		return dropped("malformed")
	}
	answers, extras := z.answer(qs)
	if len(answers) == 0 {
		return dropped("no question for us")
	}
	out := plan{answers: answers, extras: extras}
	switch {
	case in.src.Port() != mdnsPort:
		out.mode, out.to, out.id, out.questions = legacy, in.src, h.ID, qs
	case allQU(z, qs):
		out.mode, out.to = unicastQU, in.src
	default:
		out.mode, out.to = multicast, netip.AddrPortFrom(mdnsGroup, mdnsPort)
	}
	return out
}

// allQU reports whether every question we answer asked for a unicast reply.
func allQU(z *zone, qs []dnsmessage.Question) bool {
	for _, q := range qs {
		if a, _ := z.answer([]dnsmessage.Question{q}); len(a) > 0 && q.Class&cacheFlush == 0 {
			return false
		}
	}
	return true
}

// pack builds the reply message for a plan.
func (p plan) pack() ([]byte, error) {
	m := dnsmessage.Message{Header: dnsmessage.Header{Response: true, Authoritative: true},
		Answers: p.answers, Additionals: p.extras}
	if p.mode == legacy {
		m.Header.ID, m.Questions = p.id, p.questions
		m.Answers, m.Additionals = forLegacy(p.answers), forLegacy(p.extras)
	}
	return m.Pack()
}

// forLegacy caps the TTL and clears the cache-flush bit (RFC 6762 §6.7).
func forLegacy(rs []dnsmessage.Resource) []dnsmessage.Resource {
	out := make([]dnsmessage.Resource, len(rs))
	for i, r := range rs {
		r.Header.TTL = min(r.Header.TTL, ttlLegacy)
		r.Header.Class &^= cacheFlush
		out[i] = r
	}
	return out
}

// announcement is the unsolicited response carrying records (RFC 6762 §8.3, §10.1).
func announcement(records []dnsmessage.Resource) ([]byte, error) {
	m := dnsmessage.Message{Header: dnsmessage.Header{Response: true, Authoritative: true}, Answers: records}
	return m.Pack()
}
