// Package pairing issues and redeems one-time pairing codes.
//
// One code is active at a time. A code is 8 Crockford base32 characters
// (no I, L, O, U), lives 5 minutes and is consumed by the first correct use.
//
// Wrong attempts are counted per peer IP: after 5 wrong attempts that peer
// can no longer redeem the code, but the phone on another address still can,
// so one hostile host on the LAN cannot cancel the user's pairing with 5
// requests. After 20 wrong attempts in total the code is invalidated
// (20 guesses at 2^40 codes stay negligible).
package pairing

import (
	"crypto/rand"
	"crypto/subtle"
	"errors"
	"fmt"
	"net/netip"
	"strings"
	"sync"
	"time"
)

const (
	Alphabet           = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
	CodeLength         = 8
	TTL                = 5 * time.Minute
	MaxAttemptsPerPeer = 5
	MaxAttemptsTotal   = 20
)

// ErrInvalid is returned for every failed redeem (no code, expired, wrong,
// locked out) so a caller cannot tell the cases apart.
var ErrInvalid = errors.New("invalid or expired pairing code")

// ErrExhausted wraps ErrInvalid for the one attempt that used up the total
// budget and invalidated the code, so the server can log that once. Callers
// must still answer it exactly like ErrInvalid.
var ErrExhausted = fmt.Errorf("%w (code invalidated after %d wrong attempts)", ErrInvalid, MaxAttemptsTotal)

// Issued is a newly started code.
type Issued struct {
	Code      string
	ExpiresAt time.Time
}

type active struct {
	code      string
	name      string
	expiresAt time.Time
	failures  int                // total wrong attempts
	perPeer   map[netip.Addr]int // wrong attempts per peer IP (at most MaxAttemptsTotal keys)
}

// Codes holds the single active code.
type Codes struct {
	Now func() time.Time // default time.Now

	mu  sync.Mutex
	cur *active
}

func (c *Codes) now() time.Time {
	if c.Now != nil {
		return c.Now()
	}
	return time.Now()
}

// Start issues a new code, replacing any active one. name, when not empty,
// is the device name chosen on the laptop; it wins over the phone's.
func (c *Codes) Start(name string) (Issued, error) {
	code, err := newCode()
	if err != nil {
		return Issued{}, err
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	exp := c.now().Add(TTL)
	c.cur = &active{code: code, name: name, expiresAt: exp, perPeer: map[netip.Addr]int{}}
	return Issued{Code: code, ExpiresAt: exp}, nil
}

// Redeem consumes the active code if input matches it. peer is the client's
// IP (an invalid Addr shares one bucket). It returns the name given to Start
// (may be empty).
func (c *Codes) Redeem(input string, peer netip.Addr) (string, error) {
	peer = peer.Unmap()
	c.mu.Lock()
	defer c.mu.Unlock()
	cur := c.cur
	if cur == nil {
		return "", ErrInvalid
	}
	if !c.now().Before(cur.expiresAt) {
		c.cur = nil
		return "", ErrInvalid
	}
	if cur.perPeer[peer] >= MaxAttemptsPerPeer {
		return "", ErrInvalid // locked out: not compared, not counted
	}
	if subtle.ConstantTimeCompare([]byte(Normalize(input)), []byte(cur.code)) != 1 {
		return "", c.fail(cur, peer)
	}
	c.cur = nil
	return cur.name, nil
}

// fail records a wrong attempt. Caller holds c.mu.
func (c *Codes) fail(cur *active, peer netip.Addr) error {
	cur.perPeer[peer]++
	cur.failures++
	if cur.failures >= MaxAttemptsTotal {
		c.cur = nil
		return ErrExhausted
	}
	return ErrInvalid
}

// Normalize applies Crockford's input rules: case-insensitive, hyphens and
// spaces ignored, O read as 0 and I/L read as 1.
func Normalize(s string) string {
	return strings.Map(func(r rune) rune {
		switch r {
		case '-', ' ':
			return -1
		case 'o', 'O':
			return '0'
		case 'i', 'I', 'l', 'L':
			return '1'
		}
		if r >= 'a' && r <= 'z' {
			return r - 'a' + 'A'
		}
		return r
	}, s)
}

func newCode() (string, error) {
	var b [CodeLength]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", err
	}
	out := make([]byte, CodeLength)
	for i, v := range b {
		out[i] = Alphabet[v&31] // 256 is a multiple of 32: no modulo bias
	}
	return string(out), nil
}
