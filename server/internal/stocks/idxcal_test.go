package stocks

import (
	"testing"
	"time"
)

func wib(s string) time.Time {
	t, err := time.ParseInLocation("2006-01-02 15:04", s, WIB)
	if err != nil {
		panic(err)
	}
	return t
}

func TestMarketStatus(t *testing.T) {
	tests := []struct {
		name, at, state string
		session         int
		closesAt, next  string
	}{
		{"Thu pre-open", "2026-11-05 08:50", StateClosed, 0, "", "2026-11-05T09:00:00+07:00"},
		{"Thu session 1", "2026-11-05 10:00", StateOpen, 1, "2026-11-05T12:00:00+07:00", ""},
		{"Thu lunch break", "2026-11-05 12:00", StateBreak, 0, "", "2026-11-05T13:30:00+07:00"},
		{"Thu session 2", "2026-11-05 13:30", StateOpen, 2, "2026-11-05T16:00:00+07:00", ""},
		{"Thu after close", "2026-11-05 16:00", StateClosed, 0, "", "2026-11-06T09:00:00+07:00"},
		{"Fri session 1 ends 11:30", "2026-11-06 11:29", StateOpen, 1, "2026-11-06T11:30:00+07:00", ""},
		{"Fri long break", "2026-11-06 11:30", StateBreak, 0, "", "2026-11-06T14:00:00+07:00"},
		{"Fri still break at 13:45", "2026-11-06 13:45", StateBreak, 0, "", "2026-11-06T14:00:00+07:00"},
		{"Fri session 2", "2026-11-06 14:00", StateOpen, 2, "2026-11-06T16:00:00+07:00", ""},
		{"Fri evening → Monday", "2026-11-06 18:00", StateClosed, 0, "", "2026-11-09T09:00:00+07:00"},
		{"Saturday", "2026-11-07 10:00", StateClosed, 0, "", "2026-11-09T09:00:00+07:00"},
		{"Christmas Eve holiday", "2026-12-24 10:00", StateClosed, 0, "", "2026-12-28T09:00:00+07:00"},
		{"year end", "2026-12-30 17:00", StateClosed, 0, "", "2027-01-04T09:00:00+07:00"},
		{"Lebaran week 2027", "2027-03-05 17:00", StateClosed, 0, "", "2027-03-16T09:00:00+07:00"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := Status(wib(tt.at).UTC()) // any input zone is read as WIB
			if got.State != tt.state {
				t.Fatalf("state = %s, want %s", got.State, tt.state)
			}
			if (got.Session == nil) != (tt.session == 0) || (got.Session != nil && *got.Session != tt.session) {
				t.Errorf("session = %v, want %d", got.Session, tt.session)
			}
			if str(got.ClosesAt) != tt.closesAt || str(got.NextOpen) != tt.next {
				t.Errorf("closes_at %q next_open %q, want %q %q", str(got.ClosesAt), str(got.NextOpen), tt.closesAt, tt.next)
			}
		})
	}
}

func str(p *string) string {
	if p == nil {
		return ""
	}
	return *p
}

// The 2027 list reproduces BEI's count of 241 trading days.
func TestHolidays2027Count(t *testing.T) {
	n := 0
	for d := wib("2027-01-01 10:00"); d.Year() == 2027; d = d.AddDate(0, 0, 1) {
		if IsTradingDay(d) {
			n++
		}
	}
	if n != 241 {
		t.Fatalf("2027 trading days = %d, want 241", n)
	}
}

func TestTickBands(t *testing.T) {
	tests := []struct{ price, tick int64 }{
		{1, 1}, {199, 1}, {200, 2}, {499, 2}, {500, 5}, {1999, 5}, {2000, 10}, {4999, 10}, {5000, 25}, {100000, 25},
	}
	for _, tt := range tests {
		if got := TickSize(tt.price); got != tt.tick {
			t.Errorf("TickSize(%d) = %d, want %d", tt.price, got, tt.tick)
		}
	}
}

func TestAutoReject(t *testing.T) {
	day := wib("2026-11-05 10:00")
	tests := []struct{ prev, ara, arb int64 }{
		{1, 2, 1}, {10, 11, 9}, {14, 18, 13}, {15, 20, 14}, {100, 135, 93}, {200, 270, 186},
		{4840, 6050, 4510}, {5000, 6250, 4650}, {8000, 9600, 7450},
	}
	for _, tt := range tests {
		ara, arb := AutoReject(tt.prev, day)
		if !ptrEq(ara, tt.ara) || !ptrEq(arb, tt.arb) {
			t.Errorf("AutoReject(%d) = %v/%v, want %d/%d", tt.prev, deref(ara), deref(arb), tt.ara, tt.arb)
		}
	}
	// The lower limit table ends 2026-12-31 (ARB re-evaluated): unknown afterwards, upper still known.
	if ara, arb := AutoReject(4840, wib("2027-01-04 10:00")); arb != nil || !ptrEq(ara, 6050) {
		t.Fatalf("2027 = %v/%v", deref(ara), deref(arb))
	}
}

func deref(p *int64) any {
	if p == nil {
		return nil
	}
	return *p
}
