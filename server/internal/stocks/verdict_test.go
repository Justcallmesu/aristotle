package stocks

import (
	"encoding/json"
	"fmt"
	"slices"
	"strings"
	"testing"
	"time"
)

// testInput is TSTA at 1600 in session 1 (base 1590: ARA 1985, ARB 1480, ATR 15), not held.
func testInput(t *testing.T) analysisInput {
	t.Helper()
	q := &Quote{Code: "TSTA", Price: 1600, Volume: i64(500), Week52High: i64(2000), Week52Low: i64(1000)}
	ind, err := ComputeIndicators(IndicatorInput{Now: wib("2026-11-05 10:00"), Daily: rising60(), Quote: q})
	if err != nil {
		t.Fatal(err)
	}
	return analysisInput{Code: "TSTA", Name: "Test Abadi", Lang: analysisLangEN, AsOf: "2026-11-05T09:50:00+07:00", Ind: ind,
		News: []NewsRef{{ID: "news:1", Source: "cnbc", Title: "TSTA laba naik", PublishedAt: "2026-11-05T08:00:00+07:00"}}}
}

// reply builds a model reply; fields override the valid buy_today default.
func reply(fields map[string]any) string {
	m := map[string]any{"schema": verdictSchema, "code": "TSTA", "data_as_of": "2026-11-05T09:50:00+07:00", "verdict": "buy_today",
		"confidence": "med", "entry": map[string]any{"low": 1595, "high": 1600}, "target": 1650, "stop": 1570, "summary": "Uptrend above MA20.",
		"reasons": []map[string]any{{"text": "RSI is high", "sources": []string{"ind:rsi14"}}, {"text": "Above pivot", "sources": []string{"lvl:pivot"}},
			{"text": "Earnings news", "sources": []string{"news:1", "ind:trend"}}}}
	for k, v := range fields {
		if v == "DELETE" {
			delete(m, k)
		} else {
			m[k] = v
		}
	}
	b, _ := json.Marshal(m)
	return string(b)
}

func TestParseVerdictAcceptsValidAndFenced(t *testing.T) {
	in := testInput(t)
	for _, r := range []string{reply(nil), "```json\n" + reply(nil) + "\n```", "  " + reply(nil) + "\n"} {
		v, errs := parseVerdict(r, in)
		if len(errs) != 0 {
			t.Fatalf("errs = %v for %q", errs, r)
		}
		if v.Verdict != "buy_today" || !ptrEq(v.EntryLow, 1595) || !ptrEq(v.EntryHigh, 1600) || !ptrEq(v.Target, 1650) ||
			!ptrEq(v.Stop, 1570) || len(v.Reasons) != 3 || v.Reasons[2].Sources[0] != "news:1" {
			t.Fatalf("verdict = %+v", v)
		}
	}
}

func TestParseVerdictSchemaErrors(t *testing.T) {
	in := testInput(t)
	twoReasons := []map[string]any{{"text": "a", "sources": []string{"ind:rsi14"}}, {"text": "b", "sources": []string{"ind:rsi14"}}}
	cases := []struct {
		name, reply, want string
	}{
		{"prose", "Here you go: " + reply(nil), "not exactly one JSON object"},
		{"two objects", reply(nil) + reply(nil), "not exactly one JSON object"},
		{"unknown field", reply(map[string]any{"note": "x"}), `unknown field "note"`},
		{"missing field", reply(map[string]any{"stop": "DELETE"}), `missing field "stop"`},
		{"schema", reply(map[string]any{"schema": "v2"}), "schema must be"},
		{"code echo", reply(map[string]any{"code": "BBCA"}), `code must be "TSTA"`},
		{"as_of echo", reply(map[string]any{"data_as_of": "now"}), "data_as_of must be"},
		{"held verdict when not held", reply(map[string]any{"verdict": "hold"}), "verdict must be one of buy_today|wait|avoid"},
		{"confidence", reply(map[string]any{"confidence": "medium"}), "confidence must be one of"},
		{"float price", reply(map[string]any{"target": 1650.5}), "target must be a positive integer"},
		{"string price", reply(map[string]any{"stop": "1570"}), "stop must be an integer IDR or null"},
		{"entry shape", reply(map[string]any{"entry": map[string]any{"low": 1595}}), "entry must be"},
		{"too few reasons", reply(map[string]any{"reasons": twoReasons}), "give 3-5 reasons, got 2"},
		{"unknown source", reply(map[string]any{"reasons": append(twoReasons, map[string]any{"text": "c", "sources": []string{"ind:macd"}})}), `cites "ind:macd"`},
		{"no source", reply(map[string]any{"reasons": append(twoReasons, map[string]any{"text": "c", "sources": []string{}})}), "must cite at least one"},
		{"long summary", reply(map[string]any{"summary": strings.Repeat("x", 281)}), "summary must be 1-280"},
		{"long reason", reply(map[string]any{"reasons": append(twoReasons, map[string]any{"text": strings.Repeat("y", 201), "sources": []string{"ind:rsi14"}})}), "reasons[2].text must be 1-200"},
	}
	for _, c := range cases {
		_, errs := parseVerdict(c.reply, in)
		if !slices.ContainsFunc(errs, func(e string) bool { return strings.Contains(e, c.want) }) {
			t.Errorf("%s: errs = %v, want one containing %q", c.name, errs, c.want)
		}
	}
	held := in
	held.Holding = &HoldingInput{Lots: 10, AvgPrice: 1500}
	if _, errs := parseVerdict(reply(nil), held); len(errs) != 1 || !strings.Contains(errs[0], "add|hold|sell") {
		t.Fatalf("buy_today while held: %v", errs)
	}
	if _, errs := parseVerdict(reply(map[string]any{"verdict": "hold", "entry": nil, "reasons": []map[string]any{
		{"text": "a", "sources": []string{"hold:avg_price"}}, {"text": "b", "sources": []string{"hold:lots"}}, {"text": "c", "sources": []string{"lvl:s1"}}}}), held); len(errs) != 1 ||
		!strings.Contains(errs[0], `"lvl:s1"`) {
		t.Fatalf("hold ids are citable, lvl:s1 (no swing) is not: %v", errs)
	}
}

func p64(v int64) *int64 { return &v }

func TestCheckVerdict(t *testing.T) {
	in := testInput(t) // price 1600, ARA 1985, ARB 1480, ATR 15, market open
	buy := Verdict{Verdict: "buy_today", EntryLow: p64(1595), EntryHigh: p64(1600), Target: p64(1650), Stop: p64(1570)}
	with := func(f func(*Verdict)) Verdict { v := buy; f(&v); return v }
	rejected := []struct {
		name string
		v    Verdict
		want string
	}{
		{"off tick", with(func(v *Verdict) { v.Target = p64(1652) }), "target 1652 is not a multiple of its tick 5"},
		{"above ARA", with(func(v *Verdict) { v.Target = p64(1990) }), "target 1990 is above ARA 1985"},
		{"below ARB", with(func(v *Verdict) { v.Stop = p64(1475) }), "stop 1475 is below ARB 1480"},
		{"entry order", with(func(v *Verdict) { v.EntryLow, v.EntryHigh = p64(1605), p64(1600) }), "entry.low 1605 is above entry.high 1600"},
		{"stop above entry", with(func(v *Verdict) { v.Stop = p64(1600) }), "stop < entry.low <= entry.high < target"},
		{"target below entry", with(func(v *Verdict) { v.Target = p64(1600) }), "stop < entry.low <= entry.high < target"},
		{"buy without stop", with(func(v *Verdict) { v.Stop = nil }), "needs entry, target and stop"},
		{"hold without stop", Verdict{Verdict: "hold"}, "hold needs a stop below the price 1600"},
		{"hold stop at price", Verdict{Verdict: "hold", Stop: p64(1600)}, "hold needs a stop below"},
		{"hold with entry", Verdict{Verdict: "hold", Stop: p64(1570), EntryLow: p64(1595), EntryHigh: p64(1600)}, "hold must have entry null"},
		{"sell with target", Verdict{Verdict: "sell", Target: p64(1700)}, "sell must have entry, target and stop null"},
		{"avoid with entry", Verdict{Verdict: "avoid", EntryLow: p64(1595), EntryHigh: p64(1600)}, "avoid must have"},
		{"wait stop above zone", Verdict{Verdict: "wait", EntryLow: p64(1550), EntryHigh: p64(1560), Stop: p64(1555)}, "wait: stop must be below entry.low"},
	}
	for _, c := range rejected {
		rej, flags := checkVerdict(c.v, in.Ind)
		if !slices.ContainsFunc(rej, func(e string) bool { return strings.Contains(e, c.want) }) || len(flags) != 0 {
			t.Errorf("%s: rejects %v flags %v, want %q", c.name, rej, flags, c.want)
		}
	}
	accepted := []struct {
		name  string
		v     Verdict
		flags []string
	}{
		{"clean buy", buy, nil},
		{"avoid", Verdict{Verdict: "avoid"}, nil},
		{"hold", Verdict{Verdict: "hold", Stop: p64(1570), Target: p64(1700)}, nil},
		{"wait zone near", Verdict{Verdict: "wait", EntryLow: p64(1555), EntryHigh: p64(1560)}, nil},                   // 1560 ≥ 1600×0.97 = 1552
		{"wait zone far", Verdict{Verdict: "wait", EntryLow: p64(1540), EntryHigh: p64(1550)}, []string{FlagEntryFar}}, // 1550 < 1552
		// rr = (1620−1600)/(1595−1570) = 0.8.
		{"low reward/risk", with(func(v *Verdict) { v.Target = p64(1620) }), []string{FlagLowRewardRisk}},
		// 1595 − 1560 = 35 > 2×ATR 30.
		{"wide stop", with(func(v *Verdict) { v.Stop, v.Target = p64(1560), p64(1700) }), []string{FlagWideStop}},
	}
	for _, c := range accepted {
		rej, flags := checkVerdict(c.v, in.Ind)
		if len(rej) != 0 || fmt.Sprint(flags) != fmt.Sprint(c.flags) && !(len(flags) == 0 && c.flags == nil) {
			t.Errorf("%s: rejects %v flags %v, want flags %v", c.name, rej, flags, c.flags)
		}
	}
	// Target more than a tick above r1; market closed; unknown lower limit skips the ARB check.
	ind := in.Ind
	ind.Levels.R1 = p64(1640)
	ind.MarketState = StateClosed
	ind.ARB = nil
	rej, flags := checkVerdict(with(func(v *Verdict) { v.Stop = p64(1580); v.Target = p64(1660) }), ind)
	if len(rej) != 0 || fmt.Sprint(flags) != fmt.Sprint([]string{FlagLimitsUnknown, FlagTargetAboveR1, FlagMarketClosed}) {
		t.Fatalf("closed/r1/limits: rejects %v flags %v", rej, flags)
	}
	ind.Levels.R1 = p64(1655) // 1660 is one tick above r1: allowed
	if _, flags := checkVerdict(with(func(v *Verdict) { v.Stop = p64(1580); v.Target = p64(1660) }), ind); slices.Contains(flags, FlagTargetAboveR1) {
		t.Fatalf("one tick above r1 must not flag: %v", flags)
	}
}

func TestAnalysisPrompt(t *testing.T) {
	in := testInput(t)
	p := analysisPrompt(in)
	for _, want := range []string{"You have no tools", "TASK: TSTA for the session on 2026-11-05 (market open). Held: no. Verdicts allowed: buy_today|wait|avoid.",
		"every price is an integer IDR in [1480, 1985]", "Write summary and reasons in English", `"schema":"stock_verdict/1","code":"TSTA"`} {
		if !strings.Contains(p, want) {
			t.Errorf("prompt lacks %q:\n%s", want, p)
		}
	}
	_, data, ok := strings.Cut(p, "DATA: ")
	if !ok {
		t.Fatal("no DATA")
	}
	var d map[string]any
	if err := json.Unmarshal([]byte(data), &d); err != nil {
		t.Fatalf("DATA is not JSON: %v", err)
	}
	ind := d["indicators"].(map[string]any)
	if d["as_of"] != in.AsOf || d["holding"] != nil || ind["ind:rsi14"] != 100.0 || ind["ind:trend"] != "up" ||
		d["levels"].(map[string]any)["lvl:pivot"] != 1590.0 || d["news"].([]any)[0].(map[string]any)["id"] != "news:1" {
		t.Fatalf("DATA = %s", data)
	}
	in.Lang, in.Holding = analysisLangID, &HoldingInput{Lots: 10, AvgPrice: 1500, PLPct: f64(6.1)}
	in.Ind.ARB = nil
	p = analysisPrompt(in)
	for _, want := range []string{"Held: yes. Verdicts allowed: add|hold|sell.", "Bahasa Indonesia", `"hold:avg_price":1500`, `"hold:pl_pct":6.1`, "ARA/ARB unknown"} {
		if !strings.Contains(p, want) {
			t.Errorf("held prompt lacks %q", want)
		}
	}
	if r := repairPrompt([]string{"a", "b", "c", "d", "e", "f"}); r != "Your reply failed validation: a; b; c; d; e. Reply again with ONLY the corrected JSON object." {
		t.Fatalf("repair = %q", r)
	}
}

func TestNewsForCodeStrongFirstAndCapped(t *testing.T) {
	base := wib("2026-11-05 08:00")
	var arts []Article
	for i := 0; i < 12; i++ {
		title := fmt.Sprintf("Kabar TSTA hari %d", i) // weak: bare code
		if i%3 == 0 {
			title = fmt.Sprintf("Saham TSTA melonjak %d", i) // strong: after "saham"
		}
		arts = append(arts, Article{Source: "cnbc", Title: title, Link: fmt.Sprint("https://x/", i), Published: base.Add(-time.Duration(i) * time.Hour)})
	}
	arts = append(arts, Article{Source: "kontan", Title: "IHSG naik", Link: "https://x/ihsg", Published: base})
	got := newsForCode(arts, []Candidate{{Code: "TSTA"}}, "TSTA")
	if len(got) != 8 || got[0].ID != "news:1" || got[7].ID != "news:8" {
		t.Fatalf("got %d refs: %+v", len(got), got)
	}
	for i, want := range []string{"Saham TSTA melonjak 0", "Saham TSTA melonjak 3", "Saham TSTA melonjak 6", "Saham TSTA melonjak 9", "Kabar TSTA hari 1"} {
		if got[i].Title != want {
			t.Fatalf("order[%d] = %q, want %q", i, got[i].Title, want)
		}
	}
}
