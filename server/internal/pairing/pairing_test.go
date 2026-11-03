package pairing

import (
	"errors"
	"net/netip"
	"strings"
	"sync"
	"testing"
	"time"
)

type clock struct {
	mu sync.Mutex
	t  time.Time
}

func (c *clock) now() time.Time { c.mu.Lock(); defer c.mu.Unlock(); return c.t }
func (c *clock) add(d time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.t = c.t.Add(d)
}

func newCodes() (*Codes, *clock) {
	clk := &clock{t: time.Date(2026, 11, 3, 10, 0, 0, 0, time.UTC)}
	return &Codes{Now: clk.now}, clk
}

func TestCodeAlphabetAndLength(t *testing.T) {
	c, clk := newCodes()
	for range 500 {
		iss, err := c.Start("")
		if err != nil {
			t.Fatal(err)
		}
		if len(iss.Code) != CodeLength {
			t.Fatalf("code %q length %d", iss.Code, len(iss.Code))
		}
		if strings.Trim(iss.Code, Alphabet) != "" || strings.ContainsAny(iss.Code, "ILOU") {
			t.Fatalf("code %q outside the Crockford alphabet", iss.Code)
		}
		if !iss.ExpiresAt.Equal(clk.now().Add(5 * time.Minute)) {
			t.Fatalf("expiry %v", iss.ExpiresAt)
		}
	}
}

func TestRedeem(t *testing.T) {
	tests := []struct {
		name    string
		advance time.Duration
		input   func(code string) string
		wantErr bool
	}{
		{"exact", 0, func(c string) string { return c }, false},
		{"lower case with hyphen", 0, func(c string) string { return strings.ToLower(c[:4] + "-" + c[4:]) }, false},
		{"just before expiry", TTL - time.Second, func(c string) string { return c }, false},
		{"at expiry", TTL, func(c string) string { return c }, true},
		{"wrong", 0, func(c string) string { return wrongCode(c) }, true},
		{"empty", 0, func(string) string { return "" }, true},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			c, clk := newCodes()
			iss, _ := c.Start("laptop-name")
			clk.add(tt.advance)
			name, err := c.Redeem(tt.input(iss.Code), phone)
			if tt.wantErr {
				if !errors.Is(err, ErrInvalid) {
					t.Fatalf("err = %v, want ErrInvalid", err)
				}
				return
			}
			if err != nil || name != "laptop-name" {
				t.Fatalf("name=%q err=%v", name, err)
			}
		})
	}
}

func TestNormalizeCrockfordAliases(t *testing.T) {
	if got := Normalize("o1l-iab cd"); got != "0111ABCD" {
		t.Fatalf("got %q", got)
	}
}

func TestSingleUse(t *testing.T) {
	c, _ := newCodes()
	iss, _ := c.Start("")
	if _, err := c.Redeem(iss.Code, phone); err != nil {
		t.Fatal(err)
	}
	if _, err := c.Redeem(iss.Code, phone); !errors.Is(err, ErrInvalid) {
		t.Fatalf("second use err = %v", err)
	}
}

var (
	phone    = netip.MustParseAddr("192.168.1.20")
	attacker = netip.MustParseAddr("192.168.1.66")
)

func peerN(i int) netip.Addr { return netip.AddrFrom4([4]byte{10, 0, byte(i >> 8), byte(i)}) }

func TestPerPeerLockout(t *testing.T) {
	c, _ := newCodes()
	iss, _ := c.Start("")
	for i := range MaxAttemptsPerPeer - 1 {
		if _, err := c.Redeem(wrongCode(iss.Code), attacker); err == nil {
			t.Fatalf("wrong attempt %d accepted", i+1)
		}
	}
	// Four wrong attempts leave the code usable, even for the same peer.
	if _, err := c.Redeem(iss.Code, attacker); err != nil {
		t.Fatalf("code dead after 4 wrong attempts: %v", err)
	}

	iss, _ = c.Start("")
	for range MaxAttemptsPerPeer {
		_, _ = c.Redeem(wrongCode(iss.Code), attacker)
	}
	if _, err := c.Redeem(iss.Code, attacker); !errors.Is(err, ErrInvalid) {
		t.Fatalf("locked-out peer redeemed the code: %v", err)
	}
	// The attacker's 5 tries do not cancel the phone's pairing.
	if _, err := c.Redeem(iss.Code, phone); err != nil {
		t.Fatalf("phone refused after another peer's lockout: %v", err)
	}
}

func TestIPv4MappedPeerSharesBucket(t *testing.T) {
	c, _ := newCodes()
	iss, _ := c.Start("")
	mapped := netip.AddrFrom16(attacker.As16())
	for range MaxAttemptsPerPeer {
		_, _ = c.Redeem(wrongCode(iss.Code), mapped)
	}
	if _, err := c.Redeem(iss.Code, attacker); !errors.Is(err, ErrInvalid) {
		t.Fatalf("mapped and plain address counted apart: %v", err)
	}
}

func TestLockedPeerAttemptsDoNotCount(t *testing.T) {
	c, _ := newCodes()
	iss, _ := c.Start("")
	for range 100 {
		_, _ = c.Redeem(wrongCode(iss.Code), attacker)
	}
	if _, err := c.Redeem(iss.Code, phone); err != nil {
		t.Fatalf("a locked-out peer used up the total budget: %v", err)
	}
}

func TestGlobalCapInvalidates(t *testing.T) {
	c, _ := newCodes()
	iss, _ := c.Start("")
	var last error
	for i := range MaxAttemptsTotal {
		_, last = c.Redeem(wrongCode(iss.Code), peerN(i)) // one try each: no peer locks out
		if i < MaxAttemptsTotal-1 && errors.Is(last, ErrExhausted) {
			t.Fatalf("exhausted after %d attempts", i+1)
		}
	}
	if !errors.Is(last, ErrExhausted) || !errors.Is(last, ErrInvalid) {
		t.Fatalf("20th wrong attempt err = %v, want ErrExhausted wrapping ErrInvalid", last)
	}
	if _, err := c.Redeem(iss.Code, phone); !errors.Is(err, ErrInvalid) || errors.Is(err, ErrExhausted) {
		t.Fatalf("code usable after the global cap: %v", err)
	}
}

func TestNineteenSpreadAttemptsKeepCode(t *testing.T) {
	c, _ := newCodes()
	iss, _ := c.Start("")
	for i := range MaxAttemptsTotal - 1 {
		_, _ = c.Redeem(wrongCode(iss.Code), peerN(i%4)) // 4 peers, at most 5 each
	}
	if _, err := c.Redeem(iss.Code, phone); err != nil {
		t.Fatalf("code dead after 19 spread attempts: %v", err)
	}
}

func TestNewCodeReplacesOld(t *testing.T) {
	c, _ := newCodes()
	old, _ := c.Start("")
	cur, _ := c.Start("")
	if old.Code == cur.Code {
		t.Skip("random collision")
	}
	if _, err := c.Redeem(old.Code, phone); err == nil {
		t.Fatal("old code still valid")
	}
	if _, err := c.Redeem(cur.Code, phone); err != nil {
		t.Fatalf("current code refused after a wrong attempt: %v", err)
	}
}

func TestConcurrentRedeemSucceedsOnce(t *testing.T) {
	c, _ := newCodes()
	iss, _ := c.Start("")
	var wg sync.WaitGroup
	var mu sync.Mutex
	wins := 0
	for range 20 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if _, err := c.Redeem(iss.Code, phone); err == nil {
				mu.Lock()
				wins++
				mu.Unlock()
			}
		}()
	}
	wg.Wait()
	if wins != 1 {
		t.Fatalf("code redeemed %d times", wins)
	}
}

func wrongCode(code string) string {
	b := []byte(code)
	if b[0] == 'Z' {
		b[0] = 'Y'
	} else {
		b[0] = 'Z'
	}
	return string(b)
}
