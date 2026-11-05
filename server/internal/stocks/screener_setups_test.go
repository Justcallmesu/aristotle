package stocks

import (
	"encoding/json"
	"fmt"
	"strings"
	"testing"
	"time"
)

// sessionsBack lists the n trading days ending on end (YYYY-MM-DD), oldest first.
func sessionsBack(end string, n int) []time.Time {
	d, err := time.ParseInLocation(time.DateOnly, end, WIB)
	if err != nil {
		panic(err)
	}
	var out []time.Time
	for ; len(out) < n; d = d.AddDate(0, 0, -1) {
		if IsTradingDay(d) {
			out = append([]time.Time{d}, out...)
		}
	}
	return out
}

// series builds daily bars ending on end from closes; H/L = C ± spread, O = C, V = vol (each overridable after).
func series(end string, closes []int64, spread, vol int64) []Bar {
	days := sessionsBack(end, len(closes))
	bars := make([]Bar, len(closes))
	for i, c := range closes {
		bars[i] = Bar{T: days[i].Add(9 * time.Hour).Unix(), O: c, H: c + spread, L: c - spread, C: c, V: vol}
	}
	return bars
}

func flat(n int, c int64) []int64 {
	out := make([]int64, n)
	for i := range out {
		out[i] = c
	}
	return out
}

// falling is n closes from start down by step.
func falling(n int, start, step int64) []int64 {
	out := make([]int64, n)
	for i := range out {
		out[i] = start - int64(i)*step
	}
	return out
}

func sigIDs(sigs []Signal) string {
	var out []string
	for _, g := range sigs {
		out = append(out, g.ID+":"+g.Dir)
	}
	return strings.Join(out, ",")
}

func findSig(sigs []Signal, id string) *Signal {
	for i := range sigs {
		if sigs[i].ID == id {
			return &sigs[i]
		}
	}
	return nil
}

func lq(p int64, pc *int64, vol int64, tm string, open int64) *liveQuote {
	return &liveQuote{Price: p, PrevClose: pc, Volume: i64(vol), MarketTime: tm, Open: open, Usable: true}
}

var thu = wib("2026-11-05 00:00")

// 60 completed sessions before Thu 2026-11-05: C 1000, H 1010, L 990, V 10 M → val20 = Rp 10 bn, avg20V 10 M.
func base60() []Bar { return series("2026-11-04", flat(60, 1000), 10, 10_000_000) }

func TestEvalIntradayTable(t *testing.T) {
	def := DefaultScreenerSettings()
	cases := []struct {
		name  string
		bars  []Bar
		q     *liveQuote
		want  string // fired setups
		check func(t *testing.T, ev codeEval)
	}{
		// Tm 09:50 → 50 data-min of 330; vr = 2.5 M / (10 M × 50/330) = 1.65: breakout (1050 > 1010, ≥ 1.5), no spike (< 2).
		{"breakout", base60(), lq(1050, i64(1000), 2_500_000, "2026-11-05T09:50:00+07:00", 0), "breakout:up", func(t *testing.T, ev codeEval) {
			b := findSig(ev.Signals, SetupBreakout)
			if !ptrEq(b.Values.High, 1010) || *b.Values.Lookback != 20 || !approx(b.Values.VolRatio, 1.65) || b.Date != "2026-11-05" {
				t.Fatalf("values = %+v", b)
			}
			if !approx(ev.ChangePct, 5) || ev.Price != 1050 || ev.Val20 != 10 || ev.AtARA {
				t.Fatalf("eval = %+v", ev)
			}
		}},
		{"breakout price not above the high", base60(), lq(1010, i64(1000), 2_500_000, "2026-11-05T09:50:00+07:00", 0), "", nil},
		{"breakout volume too low", base60(), lq(1050, i64(1000), 2_000_000, "2026-11-05T09:50:00+07:00", 0), "", nil}, // vr 1.32
		// Pacing by the data time (TS4): wall 09:40, Tm 09:30 = 30 data-min; vr = 2 M/(10 M × 30/330) = 2.2 → spike
		// (the wall clock's 40 min would give 1.65, no spike). Breakout too (1020 > 1010, 2.2 ≥ 1.5).
		{"spike paced by market time", base60(), lq(1020, i64(1000), 2_000_000, "2026-11-05T09:30:00+07:00", 0), "breakout:up,volume_spike:up", func(t *testing.T, ev codeEval) {
			if g := findSig(ev.Signals, SetupVolumeSpike); !approx(g.Values.VolRatio, 2.2) {
				t.Fatalf("spike = %+v", g)
			}
		}},
		// 25 data-min: breakout counts (≥ 15) but volume_spike waits for 30. vr = 2 M/(10 M × 25/330) = 2.64.
		{"spike needs 30 data-min", base60(), lq(1020, i64(1000), 2_000_000, "2026-11-05T09:25:00+07:00", 0), "breakout:up", nil},
		// 10 data-min: nothing paced fires.
		{"breakout needs 15 data-min", base60(), lq(1020, i64(1000), 2_000_000, "2026-11-05T09:10:00+07:00", 0), "", nil},
		// RSI14 of 60 closes falling by 10 is 0; with P = last + 60: AG 60/14, AL 130/14 → 100 − 100×130/190 = 31.58 ≥ 30.
		{"rsi rebound", series("2026-11-04", falling(60, 1600, 10), 5, 10_000_000), lq(1070, nil, 1, "2026-11-05T10:00:00+07:00", 0), "rsi_rebound:up", func(t *testing.T, ev codeEval) {
			if g := findSig(ev.Signals, SetupRSIRebound); !approx(g.Values.RSI, 31.58) {
				t.Fatalf("rsi = %+v", g)
			}
		}},
		// P = last + 50: 100 − 100×130/180 = 27.78 < 30.
		{"rsi below the level", series("2026-11-04", falling(60, 1600, 10), 5, 10_000_000), lq(1060, nil, 1, "2026-11-05T10:00:00+07:00", 0), "", nil},
		// BBCA 2026-11-05 (TS4 §0): base 4930, daily-bar open 5050 → +2.43 % ≥ 2: gap up; price 4920 ≤ base: filled.
		{"gap up BBCA daily open", series("2026-11-04", flat(60, 4930), 10, 10_000_000), lq(4920, i64(4930), 1, "2026-11-05T10:00:00+07:00", 5050), "gap:up", func(t *testing.T, ev codeEval) {
			g := findSig(ev.Signals, SetupGap)
			if !approx(g.Values.GapPct, 2.43) || !ptrEq(g.Values.Open, 5050) || g.Values.Filled == nil || !*g.Values.Filled {
				t.Fatalf("gap = %+v", g.Values)
			}
		}},
		// The first 5-minute bar's open 5025 would give +1.93 %: no gap (why TS4 takes the daily bar's open).
		{"gap BBCA first 5-min open", series("2026-11-04", flat(60, 4930), 10, 10_000_000), lq(4980, i64(4930), 1, "2026-11-05T10:00:00+07:00", 5025), "", nil},
		{"gap down", series("2026-11-04", flat(60, 4930), 10, 10_000_000), lq(4850, i64(4930), 1, "2026-11-05T10:00:00+07:00", 4800), "gap:down", func(t *testing.T, ev codeEval) {
			if g := findSig(ev.Signals, SetupGap); !approx(g.Values.GapPct, -2.64) || *g.Values.Filled {
				t.Fatalf("gap = %+v", g.Values)
			}
		}},
		{"gap open unknown", series("2026-11-04", flat(60, 4930), 10, 10_000_000), lq(4980, i64(4930), 1, "2026-11-05T10:00:00+07:00", 0), "", nil},
		// No-signal rules.
		{"50 bars", base60()[10:], lq(1050, i64(1000), 9_000_000, "2026-11-05T09:50:00+07:00", 0), "", func(t *testing.T, ev codeEval) {
			if !ev.NoBars {
				t.Fatal("want NoBars")
			}
		}},
		{"stale quote", base60(), &liveQuote{Price: 1050, Volume: i64(9_000_000), MarketTime: "2026-11-05T09:50:00+07:00"}, "", func(t *testing.T, ev codeEval) {
			if !ev.Stale || ev.Price != 1050 {
				t.Fatalf("eval = %+v", ev)
			}
		}},
		{"no quote", base60(), nil, "", func(t *testing.T, ev codeEval) {
			if !ev.Stale || ev.Price != 1000 || ev.ChangePct != nil {
				t.Fatalf("eval = %+v", ev)
			}
		}},
		// previousClose 1010 vs stored close 1000: 10 > 1 tick (5) → split/data mismatch, nothing fires.
		{"prev close mismatch", base60(), lq(1050, i64(1010), 9_000_000, "2026-11-05T09:50:00+07:00", 0), "", func(t *testing.T, ev codeEval) {
			if !ev.Mismatch {
				t.Fatal("want Mismatch")
			}
		}},
		{"prev close within a tick", base60(), lq(1050, i64(1005), 2_500_000, "2026-11-05T09:50:00+07:00", 0), "breakout:up", nil},
		// ARA from 1000 = floorTick(1250) = 1250: at the limit.
		{"at ara", base60(), lq(1250, i64(1000), 2_500_000, "2026-11-05T09:50:00+07:00", 0), "breakout:up", func(t *testing.T, ev codeEval) {
			if !ev.AtARA {
				t.Fatal("want AtARA")
			}
		}},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			ev := evalIntraday(thu, c.bars, c.q, def)
			if got := sigIDs(ev.Signals); got != c.want {
				t.Fatalf("fired %q, want %q (%+v)", got, c.want, ev)
			}
			if c.check != nil {
				c.check(t, ev)
			}
		})
	}
}

func TestEvalIntradayShowsYesterdaysMACross(t *testing.T) {
	// 59 sessions at 1000, Wed at 1100: MA20 1005 > MA50 1002, the day before both 1000 → cross up on Wed.
	bars := series("2026-11-04", append(flat(59, 1000), 1100), 10, 10_000_000)
	ev := evalIntraday(thu, bars, nil, DefaultScreenerSettings()) // even without a quote
	g := findSig(ev.Signals, SetupMACross)
	if g == nil || g.Dir != DirUp || g.Date != "2026-11-04" || !approx(g.Values.MA20, 1005) || !approx(g.Values.MA50, 1002) {
		t.Fatalf("ma cross = %+v", ev.Signals)
	}
	off := DefaultScreenerSettings()
	off.Setups.MACross.On = false
	if ev := evalIntraday(thu, bars, nil, off); len(ev.Signals) != 0 {
		t.Fatalf("off: %+v", ev.Signals)
	}
}

func TestEvalEODTable(t *testing.T) {
	def := DefaultScreenerSettings()
	jump := func(c, h, v int64) []Bar {
		b := series("2026-11-05", append(flat(60, 1000), c), 10, 10_000_000)
		b[60].H, b[60].V = h, v
		return b
	}
	cases := []struct {
		name  string
		bars  []Bar
		want  string
		score float64
	}{
		// C 1050 > max H of the 20 before (1010), V 20 M = 2 × avg → breakout and spike; MA20 1002.5 > MA50 1001 → cross up.
		// Score 3 + 2 + 2 + 0.1 × 2 = 7.2.
		{"breakout spike cross", jump(1050, 1060, 20_000_000), "breakout:up,volume_spike:up,ma_cross:up", 7.2},
		// V 15 M = 1.5 × avg: breakout yes, spike no (< 2).
		{"breakout only", jump(1050, 1060, 15_000_000), "breakout:up,ma_cross:up", 5.2},
		// Close 1010 is not above the 1010 high.
		{"no breakout at the high", jump(1010, 1015, 30_000_000), "volume_spike:up,ma_cross:up", 4.3},
		{"cross down", jump(950, 960, 10_000_000), "ma_cross:down", 0.1},
		// RSI(n−1) 0 < 30 ≤ RSI(n) 31.58.
		{"rsi rebound", series("2026-11-05", append(falling(60, 1600, 10), 1070), 5, 10_000_000), "rsi_rebound:up", 2.1},
		{"too few bars", series("2026-11-05", flat(50, 1000), 5, 1), "", 0},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			ev := evalEOD(c.bars, def)
			if got := sigIDs(ev.Signals); got != c.want {
				t.Fatalf("fired %q, want %q", got, c.want)
			}
			if got := score(ev); got != c.score {
				t.Fatalf("score %v, want %v", got, c.score)
			}
			for _, g := range ev.Signals {
				if g.Date != "2026-11-05" {
					t.Fatalf("date = %s", g.Date)
				}
			}
		})
	}
	// Lookback 60 needs 61 bars: with 61 it compares against the 60 before.
	set := def
	set.Setups.Breakout.Lookback = 60
	if ev := evalEOD(jump(1050, 1060, 20_000_000), set); findSig(ev.Signals, SetupBreakout) == nil {
		t.Fatal("lookback 60 breakout missing")
	}
}

func TestRankOrderLiquidityAndFlags(t *testing.T) {
	up := func(id string) Signal { return Signal{ID: id, Dir: DirUp} }
	down := func(id string) Signal { return Signal{ID: id, Dir: DirDown} }
	evals := []codeEval{
		{Code: "AAAA", Val20: 10, Signals: []Signal{up(SetupBreakout), up(SetupGap)}, VolRatio: f64(1)},         // 4.1
		{Code: "BBBB", Val20: 20, Signals: []Signal{up(SetupBreakout), up(SetupGap)}, VolRatio: f64(1)},         // 4.1, more liquid
		{Code: "CCCC", Val20: 50, Signals: []Signal{up(SetupBreakout), up(SetupVolumeSpike)}, AtARA: true},      // 5, at ARA: last
		{Code: "DDDD", Val20: 4.99, Signals: []Signal{up(SetupBreakout)}},                                       // illiquid
		{Code: "EEEE", Val20: 1, Signals: []Signal{down(SetupGap)}},                                             // watched: flag, no floor
		{Code: "FFFF", Val20: 30, Signals: []Signal{down(SetupMACross), up(SetupRSIRebound)}, VolRatio: f64(9)}, // held + screened
		{Code: "GGGG", NoBars: true},
		{Code: "HHHH", Val20: 30, Signals: []Signal{down(SetupGap)}}, // bearish, not watched: nothing
		{Code: "IIII", Mismatch: true, Signals: []Signal{up(SetupBreakout)}},
	}
	roles := map[string]codeRole{"AAAA": {Screened: true}, "BBBB": {Screened: true}, "CCCC": {Screened: true}, "DDDD": {Screened: true},
		"EEEE": {Screened: true, Watch: true}, "FFFF": {Screened: true, Held: true}, "GGGG": {Screened: true}, "HHHH": {Screened: true},
		"IIII": {Screened: true}}
	cands, flags, sk := rank(evals, roles, DefaultScreenerSettings())
	var order []string
	for _, c := range cands {
		order = append(order, fmt.Sprintf("%s:%v", c.Code, c.Score))
	}
	// FFFF: rsi 2 + 0.1 × min(9, 5) = 2.5.
	if got := strings.Join(order, " "); got != "BBBB:4.1 AAAA:4.1 FFFF:2.5 CCCC:5" {
		t.Fatalf("order = %s", got)
	}
	if sk != (screenSkipped{Illiquid: 2, NoBars: 1}) { // DDDD and EEEE under Rp 5 bn
		t.Fatalf("skipped = %+v", sk)
	}
	if len(flags) != 2 || flags[0].Code != "EEEE" || !flags[0].InWatchlist || flags[1].Code != "FFFF" || !flags[1].Held ||
		sigIDs(flags[1].Setups) != "ma_cross:down" || sigIDs(cands[2].Setups) != "rsi_rebound:up" {
		t.Fatalf("flags = %+v", flags)
	}
	// Floor 0 = off: DDDD joins (EEEE has no bullish setup).
	off := DefaultScreenerSettings()
	off.MinValueBn = 0
	if cands, _, sk := rank(evals, roles, off); len(cands) != 5 || sk.Illiquid != 0 {
		t.Fatalf("floor off: %d %+v", len(cands), sk)
	}
}

func TestScreenerWindowsSlots(t *testing.T) {
	count := func(day string) (int, string) {
		d := wib(day + " 00:00")
		n, spans := 0, []string{}
		for _, w := range screenerWindows(d) {
			spans = append(spans, w.Start.Format("15:04")+"-"+w.End.Format("15:04"))
			for s := w.Start; s.Before(w.End); s = s.Add(scanEveryMin * time.Minute) {
				n++
			}
		}
		return n, strings.Join(spans, " ")
	}
	if n, s := count("2026-11-05"); n != 34 || s != "09:15-12:15 13:40-16:15" {
		t.Fatalf("Thu: %d slots %s", n, s)
	}
	if n, s := count("2026-11-06"); n != 28 || s != "09:15-11:45 14:10-16:15" {
		t.Fatalf("Fri: %d slots %s", n, s)
	}
	for _, c := range []struct {
		at string
		in bool
	}{{"2026-11-05 09:14", false}, {"2026-11-05 09:15", true}, {"2026-11-05 12:20", false}, {"2026-11-05 16:14", true},
		{"2026-11-05 16:15", false}, {"2026-11-07 10:00", false}, {"2026-12-24 10:00", false}} {
		if got := inScanWindow(wib(c.at)); got != c.in {
			t.Errorf("%s: in window %v", c.at, got)
		}
	}
}

func TestScreenerSettingsValidation(t *testing.T) {
	h := newHarness(t)
	got := h.expect("GET", "/v1/stocks/screener/settings", "", 200, "")
	want := `{"min_value_bn":5,"setups":{"breakout":{"lookback":20,"on":true,"vol_mult":1.5},"gap":{"down":true,"min_pct":2,"on":true,"up":true},` +
		`"ma_cross":{"on":true},"rsi_rebound":{"level":30,"on":true},"volume_spike":{"mult":2,"on":true}}}`
	if b, _ := json.Marshal(got); string(b) != want {
		t.Fatalf("defaults = %s", b)
	}
	good := `{"min_value_bn":0,"setups":{"breakout":{"on":false,"lookback":60,"vol_mult":5},"volume_spike":{"on":true,"mult":1.5},` +
		`"ma_cross":{"on":false},"rsi_rebound":{"on":true,"level":45},"gap":{"on":true,"min_pct":0.5,"up":true,"down":false}}}`
	bad := map[string]string{
		"missing field":  strings.Replace(good, `,"down":false`, ``, 1),
		"missing setup":  strings.Replace(good, `"ma_cross":{"on":false},`, ``, 1),
		"missing inner":  strings.Replace(good, `"on":true,"level":45`, `"on":true`, 1),
		"unknown field":  strings.Replace(good, `"min_value_bn":0`, `"min_value_bn":0,"x":1`, 1),
		"floor high":     strings.Replace(good, `"min_value_bn":0`, `"min_value_bn":100.5`, 1),
		"lookback low":   strings.Replace(good, `"lookback":60`, `"lookback":9`, 1),
		"lookback frac":  strings.Replace(good, `"lookback":60`, `"lookback":20.5`, 1),
		"vol mult":       strings.Replace(good, `"vol_mult":5`, `"vol_mult":0.9`, 1),
		"spike mult":     strings.Replace(good, `"mult":1.5`, `"mult":1.4`, 1),
		"rsi level":      strings.Replace(good, `"level":45`, `"level":46`, 1),
		"gap pct":        strings.Replace(good, `"min_pct":0.5`, `"min_pct":0.4`, 1),
		"wrong type":     strings.Replace(good, `"on":false,"lookback"`, `"on":"no","lookback"`, 1),
		"two objects":    good + `{}`,
		"empty":          ``,
		"interval here":  strings.Replace(good, `"min_value_bn":0`, `"min_value_bn":0,"scan_minutes":10`, 1),
		"over 4 KiB pad": strings.Replace(good, `{"min_value_bn"`, `{`+strings.Repeat(" ", 4096)+`"min_value_bn"`, 1),
	}
	for name, body := range bad {
		t.Run(name, func(t *testing.T) { h.expect("PUT", "/v1/stocks/screener/settings", body, 400, "bad_request") })
	}
	got = h.expect("PUT", "/v1/stocks/screener/settings", good, 200, "")
	if got["min_value_bn"] != 0.0 || got["setups"].(map[string]any)["breakout"].(map[string]any)["lookback"] != 60.0 {
		t.Fatalf("put = %v", got)
	}
	// Survives a restart.
	h.svc.Close()
	h.start()
	if got := h.expect("GET", "/v1/stocks/screener/settings", "", 200, ""); got["setups"].(map[string]any)["gap"].(map[string]any)["down"] != false {
		t.Fatalf("reloaded = %v", got)
	}
}

func TestComputeIndicatorsPacesVolumeByMarketTime(t *testing.T) {
	// Wall clock 09:40, quote data time 09:30 (Yahoo's 10-min delay): 30 elapsed minutes, not 40 (TS4 §0).
	// avg20 1050 (rising60); 500 / (1050 × 30/330) = 5.24 (the wall clock gave 3.93: 25 % low).
	q := &Quote{Code: "TEST", Price: 1600, Volume: i64(500), MarketTime: strp("2026-11-05T09:30:00+07:00")}
	ind, err := ComputeIndicators(IndicatorInput{Now: wib("2026-11-05 09:40"), Daily: rising60(), Quote: q})
	if err != nil || !approx(ind.VolRatioPaced, 5.24) {
		t.Fatalf("paced = %v (%v)", derefF(ind.VolRatioPaced), err)
	}
	// A market time from another day (yesterday's quote before the open) is not used: the wall clock paces.
	q.MarketTime = strp("2026-11-04T16:00:00+07:00")
	ind, _ = ComputeIndicators(IndicatorInput{Now: wib("2026-11-05 09:40"), Daily: rising60(), Quote: q})
	if !approx(ind.VolRatioPaced, 3.93) {
		t.Fatalf("other-day market time: paced = %v", derefF(ind.VolRatioPaced))
	}
	// Volume from the 5-minute bars: paced to the end of the last bar (09:25 + 5 = 09:30), 400/(1050×30/330) = 4.19.
	intra := []Bar{{T: wib("2026-11-05 09:20").Unix(), O: 1600, H: 1600, L: 1600, C: 1600, V: 200},
		{T: wib("2026-11-05 09:25").Unix(), O: 1600, H: 1600, L: 1600, C: 1600, V: 200}}
	ind, _ = ComputeIndicators(IndicatorInput{Now: wib("2026-11-05 09:40"), Daily: rising60(), Intraday: intra})
	if !approx(ind.VolRatioPaced, 4.19) {
		t.Fatalf("bars: paced = %v", derefF(ind.VolRatioPaced))
	}
}
