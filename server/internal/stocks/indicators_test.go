package stocks

import (
	"errors"
	"testing"
	"time"
)

func approx(p *float64, want float64) bool { return p != nil && *p == want }

func TestRSIWilderHandComputed(t *testing.T) {
	// n=2: changes +2 -1 +2 0. Seed AG=(2+0)/2=1, AL=(0+1)/2=0.5; +2: AG=(1+2)/2=1.5, AL=0.25;
	// 0: AG=0.75, AL=0.125; RS=6, RSI=100-100/7=85.714.
	if got := round2p(rsiWilder([]float64{10, 12, 11, 13, 13}, 2)); !approx(got, 85.71) {
		t.Fatalf("n=2 RSI = %v, want 85.71", derefF(got))
	}
	// n=14: seven +2 and seven -1 → AG=1, AL=0.5, RSI=66.67; one more +3: AG=(13+3)/14, AL=6.5/14 → 71.11.
	closes := []float64{100}
	for i := 0; i < 14; i++ {
		d := 2.0
		if i%2 == 1 {
			d = -1
		}
		closes = append(closes, closes[len(closes)-1]+d)
	}
	if got := round2p(rsiWilder(closes, 14)); !approx(got, 66.67) {
		t.Fatalf("seed RSI = %v, want 66.67", derefF(got))
	}
	closes = append(closes, closes[len(closes)-1]+3)
	if got := round2p(rsiWilder(closes, 14)); !approx(got, 71.11) {
		t.Fatalf("RSI = %v, want 71.11", derefF(got))
	}
	if got := rsiWilder(closes[:14], 14); got != nil {
		t.Fatal("14 closes are too few for RSI14")
	}
	flat := make([]float64, 20)
	for i := range flat {
		flat[i] = 500
	}
	if got := rsiWilder(flat, 14); !approx(got, 50) {
		t.Fatalf("flat RSI = %v, want 50", derefF(got))
	}
}

func TestATRWilderHandComputed(t *testing.T) {
	// TR: b1 max(4,3,1)=4; b2 max(3,4,1)=4; b3 max(5,0,5)=5. n=2: seed (4+4)/2=4, then (4+5)/2=4.5.
	bars := []Bar{{H: 12, L: 8, C: 10}, {H: 13, L: 9, C: 12}, {H: 16, L: 13, C: 15}, {H: 15, L: 10, C: 11}}
	if got := atrWilder(bars, 2); !approx(got, 4.5) {
		t.Fatalf("ATR = %v, want 4.5", derefF(got))
	}
}

func TestTrendLabels(t *testing.T) {
	up, down, flat := make([]float64, 60), make([]float64, 60), make([]float64, 60)
	for i := range up {
		up[i], down[i], flat[i] = float64(1000+10*i), float64(1600-10*i), 1000
	}
	// up: MA20 = 1000+10×49.5 = 1495 > MA50 = 1345, MA20 five bars ago = 1445, close 1590.
	if got, ma20, ma50 := trend(up), sma(up, 20), sma(up, 50); got != TrendUp || *ma20 != 1495 || *ma50 != 1345 {
		t.Fatalf("up: %s %v %v", got, *ma20, *ma50)
	}
	if trend(down) != TrendDown || trend(flat) != TrendSide || trend(up[:49]) != TrendUnknown {
		t.Fatalf("down %s flat %s short %s", trend(down), trend(flat), trend(up[:49]))
	}
}

func TestSwingLevelsAndPivot(t *testing.T) {
	highs := []int64{10, 11, 15, 11, 10, 12, 18, 12, 11}
	lows := []int64{8, 9, 12, 9, 6, 9, 14, 10, 9}
	var bars []Bar
	for i := range highs {
		bars = append(bars, Bar{H: highs[i], L: lows[i], C: (highs[i] + lows[i]) / 2})
	}
	bars[len(bars)-1].C = 10 // pivot: H 11, L 9, C 10 → P 10, R1 11, S1 9
	lv := levels(bars, 13)
	// Swing highs 15 (idx 2) and 18 (idx 6); swing low 6 (idx 4). Price 13.
	if !ptrEq(lv.S1, 6) || lv.S2 != nil || !ptrEq(lv.R1, 15) || !ptrEq(lv.R2, 18) {
		t.Fatalf("levels = s1 %v s2 %v r1 %v r2 %v", deref(lv.S1), deref(lv.S2), deref(lv.R1), deref(lv.R2))
	}
	if !ptrEq(lv.Pivot, 10) || !ptrEq(lv.PivotR1, 11) || !ptrEq(lv.PivotS1, 9) || lv.High20 != nil {
		t.Fatalf("pivot = %v %v %v high20 %v", deref(lv.Pivot), deref(lv.PivotR1), deref(lv.PivotS1), lv.High20)
	}
	// 1000, 1010, 1014 lie within 1.5 % of 1000: mean 1008 snaps to tick 5 → 1010.
	if got := mergeLevels([]float64{1100, 1014, 1000, 1010}); len(got) != 2 || got[0] != 1010 || got[1] != 1100 {
		t.Fatalf("merged = %v", got)
	}
}

func TestTradingMinutesAndSessionDate(t *testing.T) {
	cases := []struct {
		now            string
		elapsed, total float64
		day            string
	}{
		{"2026-11-05 10:00", 60, 330, "2026-11-05"},  // Thu session 1
		{"2026-11-05 12:45", 180, 330, "2026-11-05"}, // lunch break
		{"2026-11-06 15:00", 210, 270, "2026-11-06"}, // Fri: 150 + 60
		{"2026-11-05 08:00", 0, 330, "2026-11-05"},   // before the open: today's session
		{"2026-11-05 17:00", 330, 330, "2026-11-06"}, // after the close: the next trading day
		{"2026-11-07 10:00", 0, 0, "2026-11-09"},     // Saturday → Monday
		{"2026-12-23 16:30", 330, 330, "2026-12-28"}, // holidays 24, 25 Dec, then the weekend
	}
	for _, c := range cases {
		now := wib(c.now)
		e, tot := tradingMinutes(now)
		if now.Weekday() != time.Saturday && (e != c.elapsed || tot != c.total) {
			t.Errorf("%s: minutes %v/%v, want %v/%v", c.now, e, tot, c.elapsed, c.total)
		}
		if got := SessionDate(now).Format(time.DateOnly); got != c.day {
			t.Errorf("%s: session date %s, want %s", c.now, got, c.day)
		}
	}
}

// rising60 is 60 completed daily bars before 2026-11-05: C = 1000+10i, H/L = C±5, V 1000 (the last 2000),
// plus today's partial bar (C 9999, which must never be used).
func rising60() []Bar {
	var bars []Bar
	for i := 0; i < 60; i++ {
		day := wib("2026-11-05 09:00").AddDate(0, 0, i-60)
		c := int64(1000 + 10*i)
		v := int64(1000)
		if i == 59 {
			v = 2000
		}
		bars = append(bars, Bar{T: day.Unix(), O: c, H: c + 5, L: c - 5, C: c, V: v})
	}
	return append(bars, Bar{T: wib("2026-11-05 09:00").Unix(), O: 1595, H: 9999, L: 1, C: 9999, V: 1})
}

func TestComputeIndicatorsDuringSession(t *testing.T) {
	q := &Quote{Code: "TEST", Price: 1600, Volume: i64(500), Week52High: i64(2000), Week52Low: i64(1000)}
	intra := []Bar{
		{T: wib("2026-11-05 09:00").Unix(), O: 1595, H: 1605, L: 1590, C: 1600, V: 100},
		{T: wib("2026-11-05 09:05").Unix(), O: 1600, H: 1610, L: 1600, C: 1605, V: 200},
		{T: wib("2026-11-05 09:10").Unix(), O: 1605, H: 1605, L: 1595, C: 1600, V: 100},
	}
	ind, err := ComputeIndicators(IndicatorInput{Now: wib("2026-11-05 10:00"), Daily: rising60(), Intraday: intra, Quote: q})
	if err != nil {
		t.Fatal(err)
	}
	// Base = yesterday's close 1590 (today's partial bar left out). ARA floorTick(1590×1.25=1987)=1985;
	// ARB ceilTick(⌈1590×0.93⌉=1479)=1480. Change (1600−1590)/1590 = 0.63 %.
	checks := []struct {
		name string
		ok   bool
	}{
		{"session", ind.SessionDate == "2026-11-05" && ind.MarketState == StateOpen},
		{"base", ind.Base == 1590 && ind.Price == 1600 && ind.Tick == 5},
		{"limits", ptrEq(ind.ARA, 1985) && ptrEq(ind.ARB, 1480)},
		{"change", approx(ind.ChangePct, 0.63) && approx(ind.LastChangePct, 0.63)}, // 1590 vs 1580 = 0.63 % too
		{"ma", approx(ind.MA20, 1495) && approx(ind.MA50, 1345)},
		{"dist", approx(ind.DistMA20Pct, 7.02) && approx(ind.DistMA50Pct, 18.96)},
		{"trend", ind.Trend == TrendUp},
		{"rsi", approx(ind.RSI14, 100)},
		{"atr", approx(ind.ATR14, 15)}, // TR = |H − prevC| = 15 every bar
		// avg20 = (19×1000+2000)/20 = 1050; 500/1050 = 0.48; paced 500/(1050×60/330) = 2.62; prev 2000/1000.
		{"volume", ptrEq(ind.AvgVol20, 1050) && ptrEq(ind.VolToday, 500) && approx(ind.VolRatio, 0.48) &&
			approx(ind.VolRatioPaced, 2.62) && approx(ind.VolRatioPrev, 2)},
		{"52w", approx(ind.Pos52, 60)},
		// No swings in a straight line; 20-day range 1395..1595; pivot of 1595/1585/1590 → 1590, 1595, 1585.
		{"levels", ind.Levels.S1 == nil && ind.Levels.R1 == nil && ptrEq(ind.Levels.High20, 1595) && ptrEq(ind.Levels.Low20, 1395) &&
			ptrEq(ind.Levels.Pivot, 1590) && ptrEq(ind.Levels.PivotR1, 1595) && ptrEq(ind.Levels.PivotS1, 1585)},
		// VWAP = (1598.33×100 + 1605×200 + 1600×100)/400 = 1602.08 → 1602; gap (1595−1590)/1590 = 0.31 %.
		{"intraday", ind.Intraday != nil && *ind.Intraday == Intraday{Bars: 3, Open: 1595, High: 1610, Low: 1590, VWAP: 1602, GapPct: 0.31}},
	}
	for _, c := range checks {
		if !c.ok {
			t.Errorf("%s wrong: %+v", c.name, ind)
		}
	}
}

func TestComputeIndicatorsAfterCloseUsesOwnClose(t *testing.T) {
	daily := rising60()
	daily[60].C, daily[60].H, daily[60].L = 1600, 1610, 1590 // today's bar is complete after 16:00
	// Yahoo's previousClose after the session is unreliable (TA0): the base must be the server's own close.
	q := &Quote{Code: "TEST", Price: 1600, PrevClose: i64(1600), Volume: i64(500)}
	ind, err := ComputeIndicators(IndicatorInput{Now: wib("2026-11-05 17:00"), Daily: daily, Quote: q})
	if err != nil {
		t.Fatal(err)
	}
	if ind.SessionDate != "2026-11-06" || ind.MarketState != StateClosed || ind.Base != 1600 {
		t.Fatalf("closed: %+v", ind)
	}
	// ARA from 1600: floorTick(2000)=2000; ARB ceilTick(1488)=1490. No live change or volume, no intraday.
	if !ptrEq(ind.ARA, 2000) || !ptrEq(ind.ARB, 1490) || ind.ChangePct != nil || ind.VolToday != nil || ind.Intraday != nil {
		t.Fatalf("closed fields: ara %v arb %v change %v vol %v intraday %v", deref(ind.ARA), deref(ind.ARB), ind.ChangePct, ind.VolToday, ind.Intraday)
	}
	if !approx(ind.Pos52, 98.37) { // from the bars: (1600−995)/(1610−995) = 98.37 %
		t.Fatalf("pos52 from bars = %v", derefF(ind.Pos52))
	}
}

func TestComputeIndicatorsEdges(t *testing.T) {
	if _, err := ComputeIndicators(IndicatorInput{Now: wib("2026-11-05 10:00"), Daily: rising60()[60:]}); !errors.Is(err, errNoData) {
		t.Fatalf("only today's bar: %v", err)
	}
	few := rising60()[50:60]
	ind, err := ComputeIndicators(IndicatorInput{Now: wib("2026-11-05 10:00"), Daily: few})
	if err != nil || ind.MA20 != nil || ind.RSI14 != nil || ind.AvgVol20 != nil || ind.Trend != TrendUnknown || ind.Price != 1590 {
		t.Fatalf("10 bars: %+v %v", ind, err)
	}
	// After 2026-12-31 the lower limit is unknown.
	daily := []Bar{{T: wib("2026-12-30 09:00").Unix(), O: 1000, H: 1010, L: 990, C: 1000, V: 1}}
	ind, _ = ComputeIndicators(IndicatorInput{Now: wib("2027-01-04 10:00"), Daily: daily})
	if ind.ARB != nil || !ptrEq(ind.ARA, 1250) {
		t.Fatalf("2027 limits: ara %v arb %v", deref(ind.ARA), deref(ind.ARB))
	}
}

func derefF(p *float64) any {
	if p == nil {
		return nil
	}
	return *p
}
