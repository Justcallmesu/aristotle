package stocks

import "time"

// IDX calendar and display rules. Every table here is bundled and edited by hand: holidays each
// September (BEI's next-year list), the auto-reject table when BEI changes it. Sources: docs/v2-stocks/runs/TS0/report.md §5-§6,
// docs/spec.md §6 (hours, Kep-00003/BEI/04-2025, effective 2025-12-15).

// WIB is IDX time (UTC+7, no daylight saving); fixed so no tzdata is needed.
var WIB = time.FixedZone("WIB", 7*60*60)

// LotSize is shares per lot (Peraturan II-A VI.4).
const LotSize = 100

// MinPrice is the lowest share price since 2026-09-28 (Kep-00136/BEI/09-2026).
const MinPrice = 1

// holidays are the exchange closures on weekdays. 2026: only the dates still ahead when this was
// written (Peng-00171/BEI.POP/09-2025). 2027: Peng-00169/BEI.POP/09-2026 as read from news (SKB
// 1205/3/2 of 2026; 241 trading days, which this list reproduces), ASSUMED until checked on idx.co.id.
var holidays = map[string]bool{
	"2026-12-24": true, "2026-12-25": true, "2026-12-31": true,
	"2027-01-01": true, "2027-01-05": true, "2027-02-05": true,
	"2027-03-08": true, "2027-03-09": true, "2027-03-10": true, "2027-03-11": true, "2027-03-12": true,
	"2027-03-15": true, "2027-03-25": true, "2027-03-26": true,
	"2027-05-06": true, "2027-05-17": true, "2027-05-18": true, "2027-05-19": true, "2027-05-20": true,
	"2027-06-01": true, "2027-08-17": true, "2027-12-24": true, "2027-12-31": true,
}

// HolidaysThrough is the last year the bundled holiday list covers.
const HolidaysThrough = 2027

// IsTradingDay reports whether IDX trades on the WIB date of t.
func IsTradingDay(t time.Time) bool {
	t = t.In(WIB)
	if wd := t.Weekday(); wd == time.Saturday || wd == time.Sunday {
		return false
	}
	return !holidays[t.Format(time.DateOnly)]
}

type clock struct{ h, m int }

// session is one continuous-trading window of a day.
type session struct{ start, end clock }

// Sessions (WIB): S1 09:00-12:00 (Fri -11:30); S2 13:30 (Fri 14:00) - 16:00. Session 2's end covers the
// pre-closing auction (15:50-16:01:59) as "open": the closing price is still being set.
func daySessions(wd time.Weekday) [2]session {
	if wd == time.Friday {
		return [2]session{{clock{9, 0}, clock{11, 30}}, {clock{14, 0}, clock{16, 0}}}
	}
	return [2]session{{clock{9, 0}, clock{12, 0}}, {clock{13, 30}, clock{16, 0}}}
}

func at(day time.Time, c clock) time.Time {
	y, mo, d := day.Date()
	return time.Date(y, mo, d, c.h, c.m, 0, 0, WIB)
}

// Market states (protocol §20).
const (
	StateOpen   = "open"
	StateBreak  = "break"
	StateClosed = "closed"
)

// MarketStatus is the "market" object of GET /v1/stocks/quotes.
type MarketStatus struct {
	State    string  `json:"state"`
	Session  *int    `json:"session"`   // 1 or 2 while open, else null
	ClosesAt *string `json:"closes_at"` // end of the current session while open
	NextOpen *string `json:"next_open"` // start of the next session unless open
}

// Status is the market state at now.
func Status(now time.Time) MarketStatus {
	now = now.In(WIB)
	if IsTradingDay(now) {
		ss := daySessions(now.Weekday())
		for i, s := range ss {
			start, end := at(now, s.start), at(now, s.end)
			if !now.Before(start) && now.Before(end) {
				n := i + 1
				return MarketStatus{State: StateOpen, Session: &n, ClosesAt: rfc(end)}
			}
		}
		if s2 := at(now, ss[1].start); !now.Before(at(now, ss[0].end)) && now.Before(s2) {
			return MarketStatus{State: StateBreak, NextOpen: rfc(s2)}
		}
	}
	return MarketStatus{State: StateClosed, NextOpen: rfc(NextOpen(now))}
}

// NextOpen is the next session-1 start after now (today's when it has not begun).
func NextOpen(now time.Time) time.Time {
	day := now.In(WIB)
	if IsTradingDay(day) && day.Before(at(day, clock{9, 0})) {
		return at(day, clock{9, 0})
	}
	for i := 0; i < 30; i++ {
		day = day.AddDate(0, 0, 1)
		if IsTradingDay(day) {
			break
		}
	}
	return at(day, clock{9, 0})
}

func rfc(t time.Time) *string {
	s := t.In(WIB).Format(time.RFC3339)
	return &s
}

// TickSize is the price step for a price band (fraksi harga, VI.5.2, by the previous price).
func TickSize(price int64) int64 {
	switch {
	case price < 200:
		return 1
	case price < 500:
		return 2
	case price < 2000:
		return 5
	case price < 5000:
		return 10
	default:
		return 25
	}
}

// arRule is one dated row of the auto-reject table; To is inclusive, "" = no end yet.
type arRule struct {
	From, To string
	limit    func(prev int64) int64
}

// araRules: upper auto-reject (Kep-00136/BEI/09-2026, from 2026-09-28): Rp1-10 +Rp1; >10-200 35 %;
// >200-5,000 25 %; >5,000 20 %. Rounded down to the tick at the limit.
var araRules = []arRule{{From: "2026-09-28", limit: func(prev int64) int64 {
	switch {
	case prev <= 10:
		return prev + 1
	case prev <= 200:
		return floorTick(prev * 135 / 100)
	case prev <= 5000:
		return floorTick(prev * 125 / 100)
	default:
		return floorTick(prev * 120 / 100)
	}
}}}

// arbRules: lower auto-reject 7 % above Rp14, Rp1 for Rp1-14 (Kep-00157/BEI/10-2026 and idx.co.id
// press release 2734), set only to 2026-12-31; rounded up to the tick. After that: unknown (null).
var arbRules = []arRule{{From: "2026-10-20", To: "2026-12-31", limit: func(prev int64) int64 {
	if prev <= 14 {
		return max(prev-1, MinPrice)
	}
	return ceilTick((prev*93 + 99) / 100)
}}}

func floorTick(p int64) int64 { return p - p%TickSize(p) }

func ceilTick(p int64) int64 {
	if r := p % TickSize(p); r != 0 {
		return p + TickSize(p) - r
	}
	return p
}

// AutoReject gives the upper and lower price limits for a previous close on day; nil when the
// bundled table has no rule for that day.
func AutoReject(prev int64, day time.Time) (ara, arb *int64) {
	if prev < MinPrice {
		return nil, nil
	}
	d := day.In(WIB).Format(time.DateOnly)
	return applyRule(araRules, prev, d), applyRule(arbRules, prev, d)
}

func applyRule(rules []arRule, prev int64, d string) *int64 {
	for _, r := range rules {
		if d >= r.From && (r.To == "" || d <= r.To) {
			v := r.limit(prev)
			return &v
		}
	}
	return nil
}
