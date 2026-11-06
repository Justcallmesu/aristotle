package stocks

import (
	"cmp"
	"math"
	"slices"
	"time"
)

// Screener setups (spec Q86, TS4 report §3, protocol §20.3). Pure functions over the completed daily bars of
// the bar store and, intraday, one spark quote (price, previousClose, day volume, market time) plus the day's
// open from the daily chart bar. Thresholds come from ScreenerSettings. Comparisons use unrounded values;
// the values shown are rounded.

// Setup ids and directions.
const (
	SetupBreakout    = "breakout"
	SetupVolumeSpike = "volume_spike"
	SetupMACross     = "ma_cross"
	SetupRSIRebound  = "rsi_rebound"
	SetupGap         = "gap"
	DirUp            = "up"
	DirDown          = "down"
	ModeIntraday     = "intraday"
	ModeEOD          = "eod"
)

const (
	minScreenBars     = maLong + 1 // MA50 today and yesterday
	breakoutMinData   = 15         // data minutes before an intraday breakout counts (paced volume over-reads early)
	spikeMinData      = 30         // the same for volume_spike (morning skew)
	maxCandidates     = 50
	weightBreakout    = 3
	weightSpike       = 2
	weightMACross     = 2
	weightRSI         = 2
	weightGap         = 1
	scoreVolCap       = 5
	scoreVolWeight    = 0.1
	valueTradedScaleN = 1e9 // val20 and min_value_bn are in Rp billion
)

// SetupValues are the numbers a setup fired on (only the ones it uses are set).
type SetupValues struct {
	High     *int64   `json:"high,omitempty"`     // breakout: the highest high of the lookback
	Lookback *int     `json:"lookback,omitempty"` // breakout: sessions in that high
	VolRatio *float64 `json:"vol_ratio,omitempty"`
	RSI      *float64 `json:"rsi,omitempty"`
	GapPct   *float64 `json:"gap_pct,omitempty"`
	Open     *int64   `json:"open,omitempty"`   // gap: the day's open
	Filled   *bool    `json:"filled,omitempty"` // gap: the price is back through the base
	MA20     *float64 `json:"ma20,omitempty"`
	MA50     *float64 `json:"ma50,omitempty"`
}

// Signal is one fired setup. Date is the session the signal is about (an intraday ma_cross carries the
// date of the last completed session it was found on). FirstAt is filled from the hits log.
type Signal struct {
	ID      string      `json:"id"`
	Dir     string      `json:"dir"`
	Date    string      `json:"date"`
	FirstAt *string     `json:"first_at"`
	Values  SetupValues `json:"values"`
}

func (g Signal) bullish() bool { return g.Dir == DirUp }

func (g Signal) weight() float64 {
	switch g.ID {
	case SetupBreakout:
		return weightBreakout
	case SetupVolumeSpike:
		return weightSpike
	case SetupMACross:
		return weightMACross
	case SetupRSIRebound:
		return weightRSI
	case SetupGap:
		return weightGap
	}
	return 0
}

// liveQuote is the part of a spark quote a scan keeps (stocks-screener.json, so a settings change can re-rank
// without a Yahoo call). Usable: the quote passed the no-signal rules when it was read.
type liveQuote struct {
	Price      int64  `json:"p"`
	PrevClose  *int64 `json:"pc,omitempty"`
	Volume     *int64 `json:"v,omitempty"`
	MarketTime string `json:"tm,omitempty"`
	Open       int64  `json:"o,omitempty"` // the day's open from the daily chart bar; 0 = unknown
	Usable     bool   `json:"ok"`
}

// codeEval is one code's evaluation.
type codeEval struct {
	Code      string
	NoBars    bool // too few completed bars, or none fresh
	Mismatch  bool // previousClose and the stored close differ by more than a tick (split, data error)
	Stale     bool // intraday: no usable quote (only store-based signals)
	Price     int64
	ChangePct *float64
	Val20     float64 // mean value traded of the last 20 sessions, Rp bn (unrounded)
	VolRatio  *float64
	AtARA     bool
	Signals   []Signal
}

// valueTraded20 is mean(C×V) of the last 20 bars in Rp bn.
func valueTraded20(bars []Bar) float64 {
	w := bars[len(bars)-volWindow:]
	var sum float64
	for _, b := range w {
		sum += float64(b.C) * float64(b.V)
	}
	return sum / float64(len(w)) / valueTradedScaleN
}

func maxHigh(bars []Bar) int64 {
	h := bars[0].H
	for _, b := range bars[1:] {
		h = max(h, b.H)
	}
	return h
}

func dayString(t time.Time) string { return t.In(WIB).Format(time.DateOnly) }

// maCross is the EOD MA20/MA50 cross on the last bar of closes: up when MA20 crossed above MA50 on it.
func maCross(closes []float64, date string) *Signal {
	n := len(closes)
	if n < minScreenBars {
		return nil
	}
	f, s := sma(closes, maShort), sma(closes, maLong)
	pf, ps := sma(closes[:n-1], maShort), sma(closes[:n-1], maLong)
	dir := ""
	switch {
	case *f > *s && *pf <= *ps:
		dir = DirUp
	case *f < *s && *pf >= *ps:
		dir = DirDown
	default:
		return nil
	}
	return &Signal{ID: SetupMACross, Dir: dir, Date: date, Values: SetupValues{MA20: f, MA50: s}}
}

func intp(v int) *int { return &v }

func boolp(v bool) *bool { return &v }

// evalIntraday evaluates one code during day's session. bars are the completed daily bars before day (from a
// fresh store; nil when there are none); q is the scan's quote (nil when it failed).
func evalIntraday(day time.Time, bars []Bar, q *liveQuote, set ScreenerSettings) codeEval {
	if len(bars) < minScreenBars {
		return codeEval{NoBars: true}
	}
	n := len(bars) - 1
	base := bars[n].C
	closes := barCloses(bars)
	ev := codeEval{Price: base, Val20: valueTraded20(bars)}
	cross := (*Signal)(nil)
	if set.Setups.MACross.On {
		cross = maCross(closes, dayString(barDay(bars[n])))
	}
	if cross != nil {
		ev.Signals = append(ev.Signals, *cross)
	}
	// The usable check comes first: a quote with no trade today (suspended, not traded yet) carries a
	// previousClose one session older, which is no data mismatch; the code keeps its stored signals.
	if q == nil || !q.Usable {
		ev.Stale = true
		if q != nil {
			ev.Price = q.Price
		}
		return ev
	}
	if q.PrevClose != nil {
		if d := *q.PrevClose - base; d > TickSize(base) || -d > TickSize(base) {
			return codeEval{Mismatch: true, Price: q.Price, Val20: ev.Val20}
		}
	}
	p := q.Price
	ev.Price, ev.ChangePct = p, pct(float64(p), float64(base))
	if ara, _ := AutoReject(base, day); ara != nil && p >= *ara {
		ev.AtARA = true
	}
	date := dayString(day)
	tm, err := time.Parse(time.RFC3339, q.MarketTime)
	var elapsed float64
	if err == nil {
		var total float64
		elapsed, total = tradingMinutes(tm)
		if avg := meanVolume(bars[n-volWindow+1:]); q.Volume != nil && avg > 0 && elapsed > 0 {
			vr := float64(*q.Volume) / (float64(avg) * elapsed / total)
			ev.VolRatio = &vr
		}
	}
	st := set.Setups
	if b := st.Breakout; b.On && elapsed >= breakoutMinData && ev.VolRatio != nil && len(bars) >= b.Lookback {
		hi := maxHigh(bars[len(bars)-b.Lookback:])
		if p > hi && *ev.VolRatio >= b.VolMult {
			ev.Signals = append(ev.Signals, Signal{ID: SetupBreakout, Dir: DirUp, Date: date,
				Values: SetupValues{High: &hi, Lookback: intp(b.Lookback), VolRatio: round2(*ev.VolRatio)}})
		}
	}
	if v := st.VolumeSpike; v.On && elapsed >= spikeMinData && ev.VolRatio != nil && *ev.VolRatio >= v.Mult {
		ev.Signals = append(ev.Signals, Signal{ID: SetupVolumeSpike, Dir: DirUp, Date: date,
			Values: SetupValues{VolRatio: round2(*ev.VolRatio)}})
	}
	if r := st.RSIRebound; r.On {
		before := rsiWilder(closes, wilderPeriod)
		now := rsiWilder(append(slices.Clone(closes), float64(p)), wilderPeriod)
		if before != nil && now != nil && *before < r.Level && *now >= r.Level {
			ev.Signals = append(ev.Signals, Signal{ID: SetupRSIRebound, Dir: DirUp, Date: date, Values: SetupValues{RSI: round2(*now)}})
		}
	}
	if g := st.Gap; g.On && q.Open > 0 {
		gp := float64(q.Open-base) / float64(base) * 100
		dir, filled := "", false
		switch {
		case g.Up && gp >= g.MinPct:
			dir, filled = DirUp, p <= base
		case g.Down && gp <= -g.MinPct:
			dir, filled = DirDown, p >= base
		}
		if dir != "" {
			o := q.Open
			ev.Signals = append(ev.Signals, Signal{ID: SetupGap, Dir: dir, Date: date,
				Values: SetupValues{GapPct: round2(gp), Open: &o, Filled: boolp(filled)}})
		}
	}
	return ev
}

// evalEOD evaluates the last bar of bars (a completed session) against the sessions before it.
func evalEOD(bars []Bar, set ScreenerSettings) codeEval {
	if len(bars) < minScreenBars {
		return codeEval{NoBars: true}
	}
	n := len(bars) - 1
	last, prev := bars[n], bars[n-1]
	closes := barCloses(bars)
	date := dayString(barDay(last))
	ev := codeEval{Price: last.C, ChangePct: pct(float64(last.C), float64(prev.C)), Val20: valueTraded20(bars)}
	if ara, _ := AutoReject(prev.C, barDay(last)); ara != nil && last.C >= *ara {
		ev.AtARA = true
	}
	if avg := meanVolume(bars[n-volWindow : n]); avg > 0 {
		vr := float64(last.V) / float64(avg)
		ev.VolRatio = &vr
	}
	st := set.Setups
	if b := st.Breakout; b.On && ev.VolRatio != nil && n >= b.Lookback {
		hi := maxHigh(bars[n-b.Lookback : n])
		if last.C > hi && *ev.VolRatio >= b.VolMult {
			ev.Signals = append(ev.Signals, Signal{ID: SetupBreakout, Dir: DirUp, Date: date,
				Values: SetupValues{High: &hi, Lookback: intp(b.Lookback), VolRatio: round2(*ev.VolRatio)}})
		}
	}
	if v := st.VolumeSpike; v.On && ev.VolRatio != nil && *ev.VolRatio >= v.Mult {
		ev.Signals = append(ev.Signals, Signal{ID: SetupVolumeSpike, Dir: DirUp, Date: date,
			Values: SetupValues{VolRatio: round2(*ev.VolRatio)}})
	}
	if st.MACross.On {
		if c := maCross(closes, date); c != nil {
			ev.Signals = append(ev.Signals, *c)
		}
	}
	if r := st.RSIRebound; r.On {
		before, now := rsiWilder(closes[:n], wilderPeriod), rsiWilder(closes, wilderPeriod)
		if before != nil && now != nil && *before < r.Level && *now >= r.Level {
			ev.Signals = append(ev.Signals, Signal{ID: SetupRSIRebound, Dir: DirUp, Date: date, Values: SetupValues{RSI: round2(*now)}})
		}
	}
	return ev
}

// score is Σ bullish weights + 0.1 × min(vol_ratio, 5), to 1 decimal.
func score(ev codeEval) float64 {
	var s float64
	for _, g := range ev.Signals {
		if g.bullish() {
			s += g.weight()
		}
	}
	if ev.VolRatio != nil {
		s += scoreVolWeight * math.Min(*ev.VolRatio, scoreVolCap)
	}
	return math.Round(s*10) / 10
}

// codeRole says how a code takes part: screened (universe ∪ watchlist: may be a candidate) and/or flagged
// (watchlist ∪ holdings: bearish signals shown as flags).
type codeRole struct {
	Screened, Watch, Held bool
}

// ScreenCandidate is one candidate row (≥ 1 bullish setup, liquid).
type ScreenCandidate struct {
	Code        string   `json:"code"`
	Name        string   `json:"name"`
	InWatchlist bool     `json:"in_watchlist"`
	Held        bool     `json:"held"`
	Price       int64    `json:"price"`
	ChangePct   *float64 `json:"change_pct"`
	Val20       float64  `json:"val20"`
	Score       float64  `json:"score"`
	AtARA       bool     `json:"at_ara"`
	Setups      []Signal `json:"setups"`
}

// screenWatched is a watchlist or holding code whose bullish setups did not make it a candidate (illiquid, or a
// held code outside the screened set). It is not shown; it feeds the hits log, so slice 3 alerts cover every
// watched and held code (Q107).
type screenWatched struct {
	Code   string   `json:"code"`
	Watch  bool     `json:"watch"`
	Held   bool     `json:"held"`
	Score  float64  `json:"score"`
	Setups []Signal `json:"setups"`
}

// ScreenFlag is a bearish signal on a watchlist or holding code (Q106: flags only, no liquidity filter).
type ScreenFlag struct {
	Code        string   `json:"code"`
	Name        string   `json:"name"`
	InWatchlist bool     `json:"in_watchlist"`
	Held        bool     `json:"held"`
	Price       int64    `json:"price"`
	ChangePct   *float64 `json:"change_pct"`
	Setups      []Signal `json:"setups"`
}

type screenSkipped struct {
	Illiquid int `json:"illiquid"`
	NoBars   int `json:"no_bars"`
}

// rank turns evaluations into candidates (score desc, val20 desc, code; at_ara last; ≤ 50) and flags (code order).
// watched are the bullish setups of watched/held codes that are not candidates (code order).
func rank(evals []codeEval, roles map[string]codeRole, set ScreenerSettings) (cands []ScreenCandidate, flags []ScreenFlag, watched []screenWatched, sk screenSkipped) {
	cands, flags = []ScreenCandidate{}, []ScreenFlag{}
	for _, ev := range evals {
		r := roles[ev.Code]
		if ev.NoBars {
			if r.Screened {
				sk.NoBars++
			}
			continue
		}
		if ev.Mismatch {
			continue
		}
		var bull, bear []Signal
		for _, g := range ev.Signals {
			if g.bullish() {
				bull = append(bull, g)
			} else {
				bear = append(bear, g)
			}
		}
		candidate := false
		if r.Screened {
			if set.MinValueBn > 0 && ev.Val20 < set.MinValueBn {
				sk.Illiquid++
			} else if len(bull) > 0 {
				candidate = true
				cands = append(cands, ScreenCandidate{Code: ev.Code, InWatchlist: r.Watch, Held: r.Held, Price: ev.Price,
					ChangePct: ev.ChangePct, Val20: *round2(ev.Val20), Score: score(ev), AtARA: ev.AtARA, Setups: bull})
			}
		}
		if !candidate && (r.Watch || r.Held) && len(bull) > 0 {
			watched = append(watched, screenWatched{Code: ev.Code, Watch: r.Watch, Held: r.Held, Score: score(ev), Setups: bull})
		}
		if (r.Watch || r.Held) && len(bear) > 0 {
			flags = append(flags, ScreenFlag{Code: ev.Code, InWatchlist: r.Watch, Held: r.Held, Price: ev.Price,
				ChangePct: ev.ChangePct, Setups: bear})
		}
	}
	slices.SortStableFunc(cands, func(a, b ScreenCandidate) int {
		if a.AtARA != b.AtARA {
			if a.AtARA {
				return 1
			}
			return -1
		}
		return cmp.Or(cmp.Compare(b.Score, a.Score), cmp.Compare(b.Val20, a.Val20), cmp.Compare(a.Code, b.Code))
	})
	if len(cands) > maxCandidates {
		cands = cands[:maxCandidates]
	}
	slices.SortStableFunc(flags, func(a, b ScreenFlag) int { return cmp.Compare(a.Code, b.Code) })
	slices.SortStableFunc(watched, func(a, b screenWatched) int { return cmp.Compare(a.Code, b.Code) })
	return cands, flags, watched, sk
}
