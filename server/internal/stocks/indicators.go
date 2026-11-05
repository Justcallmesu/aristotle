package stocks

import (
	"errors"
	"math"
	"slices"
	"time"
)

// Analyzer indicators (docs/v2-stocks/runs/TA0/report.md §7c, spec Q97). Pure functions over the bars TS1
// already fetches: 1y daily bars and today's 5-minute bars. Every average runs over COMPLETED sessions
// (bars dated before the session the verdict is for), so today's partial bar never enters them. Prices
// are integer IDR; levels are snapped to the nearest tick of their band.

// errNoData: no completed daily bar to compute from (422 no_data).
var errNoData = errors.New("no completed daily bars")

const (
	maShort       = 20
	maLong        = 50
	wilderPeriod  = 14
	trendLookback = 5
	volWindow     = 20
	swingWindow   = 60
	swingSide     = 2
	levelMergePct = 1.5
	minIntraday   = 3
)

// Trend labels.
const (
	TrendUp      = "up"
	TrendDown    = "down"
	TrendSide    = "side"
	TrendUnknown = "unknown"
)

// IndicatorInput is what the indicators are computed from.
type IndicatorInput struct {
	Now      time.Time
	Daily    []Bar  // daily bars, oldest first; may end with today's partial bar
	Intraday []Bar  // 5-minute bars of the latest day, oldest first; may be empty
	Quote    *Quote // the latest quote, or nil
}

// Intraday are today's session statistics from 5-minute bars.
type Intraday struct {
	Bars   int     `json:"bars"`
	Open   int64   `json:"open"`
	High   int64   `json:"high"`
	Low    int64   `json:"low"`
	VWAP   int64   `json:"vwap"`
	GapPct float64 `json:"gap_pct"` // open vs the limit base
}

// Levels are support/resistance prices, snapped to tick.
type Levels struct {
	S1      *int64 `json:"s1"`
	S2      *int64 `json:"s2"`
	R1      *int64 `json:"r1"`
	R2      *int64 `json:"r2"`
	High20  *int64 `json:"high20"`
	Low20   *int64 `json:"low20"`
	Pivot   *int64 `json:"pivot"`
	PivotR1 *int64 `json:"pivot_r1"`
	PivotS1 *int64 `json:"pivot_s1"`
}

// Indicators is the analyzer's computed input. Pointers are nil when there are too few bars.
type Indicators struct {
	SessionDate   string    `json:"session_date"` // the WIB trading day the verdict is for
	MarketState   string    `json:"market_state"` // open | break | closed at Now
	Price         int64     `json:"price"`
	Base          int64     `json:"base"` // the limit base: the last completed session's close
	Tick          int64     `json:"tick"` // tick at Price
	ARA           *int64    `json:"ara"`
	ARB           *int64    `json:"arb"`
	ChangePct     *float64  `json:"change_pct"`      // Price vs Base, only while the session date's market is open or on break
	LastChangePct *float64  `json:"last_change_pct"` // the last completed session vs the one before
	MA20          *float64  `json:"ma20"`
	MA50          *float64  `json:"ma50"`
	DistMA20Pct   *float64  `json:"dist_ma20_pct"`
	DistMA50Pct   *float64  `json:"dist_ma50_pct"`
	Trend         string    `json:"trend"`
	RSI14         *float64  `json:"rsi14"`
	ATR14         *float64  `json:"atr14"`
	AvgVol20      *int64    `json:"avg_vol20"`
	VolToday      *int64    `json:"vol_today"`
	VolRatio      *float64  `json:"vol_ratio"`       // today's volume ÷ the 20-session average
	VolRatioPaced *float64  `json:"vol_ratio_paced"` // the same, scaled to the elapsed trading minutes
	VolRatioPrev  *float64  `json:"vol_ratio_prev"`  // the last completed session ÷ the 20 sessions before it
	High52        *int64    `json:"high52"`
	Low52         *int64    `json:"low52"`
	Pos52         *float64  `json:"pos52"` // 0 = at the 52-week low, 100 = at the high
	Levels        Levels    `json:"levels"`
	Intraday      *Intraday `json:"intraday"`
}

// ComputeIndicators computes every indicator; errNoData without a completed daily bar.
func ComputeIndicators(in IndicatorInput) (Indicators, error) {
	now := in.Now.In(WIB)
	day := SessionDate(now)
	done := completedBars(in.Daily, day)
	if len(done) == 0 {
		return Indicators{}, errNoData
	}
	closes := barCloses(done)
	last := done[len(done)-1]
	today := barsOn(in.Intraday, day)
	live := sameDay(day, now) && Status(now).State != StateClosed
	ind := Indicators{SessionDate: day.Format(time.DateOnly), MarketState: Status(now).State, Base: last.C, Trend: TrendUnknown}
	ind.Price = currentPrice(in.Quote, today, last.C)
	ind.Tick = TickSize(ind.Price)
	ind.ARA, ind.ARB = AutoReject(last.C, day)
	if live {
		ind.ChangePct = pct(float64(ind.Price), float64(last.C))
	}
	if len(closes) >= 2 {
		ind.LastChangePct = pct(float64(last.C), float64(closes[len(closes)-2]))
	}
	ind.MA20, ind.MA50 = sma(closes, maShort), sma(closes, maLong)
	ind.DistMA20Pct, ind.DistMA50Pct = pctOf(ind.Price, ind.MA20), pctOf(ind.Price, ind.MA50)
	ind.Trend = trend(closes)
	ind.RSI14 = round2p(rsiWilder(closes, wilderPeriod))
	ind.ATR14 = round2p(atrWilder(done, wilderPeriod))
	ind.volume(done, in.Quote, today, now, live)
	ind.week52(in.Daily, in.Quote, now)
	ind.Levels = levels(done, ind.Price)
	if len(today) >= minIntraday && sameDay(day, now) {
		ind.Intraday = intraday(today, last.C)
	}
	return ind, nil
}

// SessionDate is the trading day a verdict made at now is for: today on a trading day before the
// close (16:00 WIB), else the next trading day.
func SessionDate(now time.Time) time.Time {
	now = now.In(WIB)
	if IsTradingDay(now) && now.Before(at(now, daySessions(now.Weekday())[1].end)) {
		return at(now, clock{0, 0})
	}
	return at(NextOpen(now), clock{0, 0})
}

func barDay(b Bar) time.Time { return at(time.Unix(b.T, 0).In(WIB), clock{0, 0}) }

func sameDay(a, b time.Time) bool {
	return at(a.In(WIB), clock{0, 0}).Equal(at(b.In(WIB), clock{0, 0}))
}

// completedBars keeps the daily bars dated before day.
func completedBars(daily []Bar, day time.Time) []Bar {
	var out []Bar
	for _, b := range daily {
		if barDay(b).Before(day) && b.C > 0 {
			out = append(out, b)
		}
	}
	return out
}

func barsOn(bars []Bar, day time.Time) []Bar {
	var out []Bar
	for _, b := range bars {
		if barDay(b).Equal(day) && b.C > 0 {
			out = append(out, b)
		}
	}
	return out
}

func barCloses(bars []Bar) []float64 {
	out := make([]float64, len(bars))
	for i, b := range bars {
		out[i] = float64(b.C)
	}
	return out
}

// currentPrice: the quote's price, else the last 5-minute close of the session day, else the last close.
func currentPrice(q *Quote, today []Bar, lastClose int64) int64 {
	switch {
	case q != nil && q.Price > 0:
		return q.Price
	case len(today) > 0:
		return today[len(today)-1].C
	default:
		return lastClose
	}
}

// sma is the mean of the last n values, nil with fewer.
func sma(vals []float64, n int) *float64 {
	if len(vals) < n || n == 0 {
		return nil
	}
	var sum float64
	for _, v := range vals[len(vals)-n:] {
		sum += v
	}
	return round2(sum / float64(n))
}

// trend: up when the last close > MA20 > MA50 and MA20 rose over the last 5 bars; down for the mirror;
// side otherwise; unknown without enough bars.
func trend(closes []float64) string {
	ma20, ma50 := sma(closes, maShort), sma(closes, maLong)
	if ma20 == nil || ma50 == nil || len(closes) < maShort+trendLookback {
		return TrendUnknown
	}
	prev := sma(closes[:len(closes)-trendLookback], maShort)
	c := closes[len(closes)-1]
	switch {
	case c > *ma20 && *ma20 > *ma50 && *ma20 > *prev:
		return TrendUp
	case c < *ma20 && *ma20 < *ma50 && *ma20 < *prev:
		return TrendDown
	default:
		return TrendSide
	}
}

// rsiWilder: average gain and loss seeded with the simple mean of the first n changes, then
// avg = (avg×(n−1) + x)/n; RSI = 100 − 100/(1+AG/AL); 100 when AL = 0, 50 when the price never moved.
func rsiWilder(closes []float64, n int) *float64 {
	if len(closes) < n+1 {
		return nil
	}
	var ag, al float64
	for i := 1; i <= n; i++ {
		g, l := gainLoss(closes[i] - closes[i-1])
		ag, al = ag+g, al+l
	}
	ag, al = ag/float64(n), al/float64(n)
	for i := n + 1; i < len(closes); i++ {
		g, l := gainLoss(closes[i] - closes[i-1])
		ag = (ag*float64(n-1) + g) / float64(n)
		al = (al*float64(n-1) + l) / float64(n)
	}
	var rsi float64
	switch {
	case al == 0 && ag == 0:
		rsi = 50
	case al == 0:
		rsi = 100
	default:
		rsi = 100 - 100/(1+ag/al)
	}
	return &rsi
}

func gainLoss(d float64) (float64, float64) {
	if d > 0 {
		return d, 0
	}
	return 0, -d
}

// atrWilder: true range max(H−L, |H−prevC|, |L−prevC|), seeded with the mean of the first n, then Wilder.
func atrWilder(bars []Bar, n int) *float64 {
	if len(bars) < n+1 {
		return nil
	}
	tr := func(i int) float64 {
		h, l, pc := float64(bars[i].H), float64(bars[i].L), float64(bars[i-1].C)
		return math.Max(h-l, math.Max(math.Abs(h-pc), math.Abs(l-pc)))
	}
	var atr float64
	for i := 1; i <= n; i++ {
		atr += tr(i)
	}
	atr /= float64(n)
	for i := n + 1; i < len(bars); i++ {
		atr = (atr*float64(n-1) + tr(i)) / float64(n)
	}
	return &atr
}

// volume fills the volume ratios. Today's volume counts only while the session day trades (live).
func (ind *Indicators) volume(done []Bar, q *Quote, today []Bar, now time.Time, live bool) {
	if len(done) >= volWindow {
		avg := meanVolume(done[len(done)-volWindow:])
		ind.AvgVol20 = &avg
		if live {
			ind.todayVolume(avg, q, today, now)
		}
	}
	if len(done) >= volWindow+1 {
		before := meanVolume(done[len(done)-volWindow-1 : len(done)-1])
		if before > 0 {
			ind.VolRatioPrev = round2(float64(done[len(done)-1].V) / float64(before))
		}
	}
}

func (ind *Indicators) todayVolume(avg int64, q *Quote, today []Bar, now time.Time) {
	var vol int64
	// The volume is paced by its own data time, not the wall clock: Yahoo is ~10 min behind, so at 09:40 the
	// volume covers 30 minutes, not 40 (TS4 §0). The quote's market time when it is today's, else the end of
	// the last 5-minute bar; the wall clock only when neither is known.
	paceAt := now
	switch {
	case q != nil && q.Volume != nil:
		vol = *q.Volume
		if q.MarketTime != nil {
			if tm, err := time.Parse(time.RFC3339, *q.MarketTime); err == nil && sameDay(tm, now) && !tm.After(now) {
				paceAt = tm
			}
		}
	case len(today) > 0:
		for _, b := range today {
			vol += b.V
		}
		if end := time.Unix(today[len(today)-1].T, 0).Add(5 * time.Minute); end.Before(now) {
			paceAt = end
		}
	default:
		return
	}
	ind.VolToday = &vol
	if avg <= 0 {
		return
	}
	ind.VolRatio = round2(float64(vol) / float64(avg))
	elapsed, total := tradingMinutes(paceAt)
	if elapsed > 0 {
		ind.VolRatioPaced = round2(float64(vol) / (float64(avg) * elapsed / total))
	}
}

func meanVolume(bars []Bar) int64 {
	var sum int64
	for _, b := range bars {
		sum += b.V
	}
	return int64(math.Round(float64(sum) / float64(len(bars))))
}

// tradingMinutes returns the continuous-trading minutes elapsed at now and the day's total (330
// Mon-Thu, 270 Fri, protocol §20 hours).
func tradingMinutes(now time.Time) (elapsed, total float64) {
	now = now.In(WIB)
	for _, s := range daySessions(now.Weekday()) {
		start, end := at(now, s.start), at(now, s.end)
		total += end.Sub(start).Minutes()
		if now.After(start) {
			stop := end
			if now.Before(end) {
				stop = now
			}
			elapsed += stop.Sub(start).Minutes()
		}
	}
	return elapsed, total
}

// week52 takes the quote's 52-week range, else the daily bars of the last year.
func (ind *Indicators) week52(daily []Bar, q *Quote, now time.Time) {
	if q != nil && q.Week52High != nil && q.Week52Low != nil {
		ind.High52, ind.Low52 = q.Week52High, q.Week52Low
	} else {
		from := now.AddDate(-1, 0, 0).Unix()
		var hi, lo int64
		for _, b := range daily {
			if b.T < from || b.C <= 0 {
				continue
			}
			if hi == 0 || b.H > hi {
				hi = b.H
			}
			if lo == 0 || b.L < lo {
				lo = b.L
			}
		}
		if hi == 0 {
			return
		}
		ind.High52, ind.Low52 = &hi, &lo
	}
	if *ind.High52 > *ind.Low52 {
		ind.Pos52 = round2(float64(ind.Price-*ind.Low52) / float64(*ind.High52-*ind.Low52) * 100)
	}
}

// levels: swing highs and lows of the last 60 sessions (a high above the 2 bars on each side, lows
// likewise), merged within 1.5 %, the 2 nearest below and above price; the 20-day range; the floor
// pivot of the last session.
func levels(done []Bar, price int64) Levels {
	var lv Levels
	recent := done[max(0, len(done)-swingWindow):]
	merged := mergeLevels(swings(recent))
	var below, above []int64
	for _, v := range merged {
		switch {
		case v < price:
			below = append(below, v)
		case v > price:
			above = append(above, v)
		}
	}
	slices.Reverse(below)
	lv.S1, lv.S2 = nth(below, 0), nth(below, 1)
	lv.R1, lv.R2 = nth(above, 0), nth(above, 1)
	if len(done) >= volWindow {
		hi, lo := done[len(done)-volWindow].H, done[len(done)-volWindow].L
		for _, b := range done[len(done)-volWindow:] {
			hi, lo = max(hi, b.H), min(lo, b.L)
		}
		lv.High20, lv.Low20 = &hi, &lo
	}
	last := done[len(done)-1]
	p := float64(last.H+last.L+last.C) / 3
	lv.Pivot, lv.PivotR1, lv.PivotS1 = snapPtr(p), snapPtr(2*p-float64(last.L)), snapPtr(2*p-float64(last.H))
	return lv
}

// swings returns every swing high and swing low price.
func swings(bars []Bar) []float64 {
	var out []float64
	for i := swingSide; i < len(bars)-swingSide; i++ {
		high, low := true, true
		for j := i - swingSide; j <= i+swingSide; j++ {
			if j == i {
				continue
			}
			high = high && bars[i].H > bars[j].H
			low = low && bars[i].L < bars[j].L
		}
		if high {
			out = append(out, float64(bars[i].H))
		}
		if low {
			out = append(out, float64(bars[i].L))
		}
	}
	return out
}

// mergeLevels sorts the prices and replaces each run within 1.5 % of its first price by the run's
// mean, snapped to tick.
func mergeLevels(vals []float64) []int64 {
	slices.Sort(vals)
	var out []int64
	for i := 0; i < len(vals); {
		j, sum := i, 0.0
		for j < len(vals) && vals[j] <= vals[i]*(1+levelMergePct/100) {
			sum += vals[j]
			j++
		}
		snapped := snapTick(sum / float64(j-i))
		if len(out) == 0 || out[len(out)-1] != snapped {
			out = append(out, snapped)
		}
		i = j
	}
	return out
}

// intraday: the session's open, high, low, VWAP = Σ(((H+L+C)/3)×V)/ΣV, and the gap vs base.
func intraday(bars []Bar, base int64) *Intraday {
	in := &Intraday{Bars: len(bars), Open: bars[0].O, High: bars[0].H, Low: bars[0].L}
	var pv, vol float64
	for _, b := range bars {
		in.High, in.Low = max(in.High, b.H), min(in.Low, b.L)
		pv += float64(b.H+b.L+b.C) / 3 * float64(b.V)
		vol += float64(b.V)
	}
	if vol > 0 {
		in.VWAP = int64(math.Round(pv / vol))
	} else {
		in.VWAP = bars[len(bars)-1].C
	}
	if g := pct(float64(in.Open), float64(base)); g != nil {
		in.GapPct = *g
	}
	return in
}

// snapTick rounds a price to the nearest tick of its band.
func snapTick(v float64) int64 {
	p := int64(math.Round(v))
	t := TickSize(p)
	return int64(math.Round(float64(p)/float64(t))) * t
}

func snapPtr(v float64) *int64 {
	if v < MinPrice {
		return nil
	}
	s := snapTick(v)
	return &s
}

func nth(vals []int64, i int) *int64 {
	if i >= len(vals) {
		return nil
	}
	v := vals[i]
	return &v
}

// pct is (a−b)/b×100 to 2 decimals; nil when b is 0.
func pct(a, b float64) *float64 {
	if b == 0 {
		return nil
	}
	return round2((a - b) / b * 100)
}

func pctOf(price int64, ma *float64) *float64 {
	if ma == nil {
		return nil
	}
	return pct(float64(price), *ma)
}

func round2(v float64) *float64 {
	r := math.Round(v*100) / 100
	return &r
}

func round2p(v *float64) *float64 {
	if v == nil {
		return nil
	}
	return round2(*v)
}
