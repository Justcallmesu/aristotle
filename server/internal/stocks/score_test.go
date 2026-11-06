package stocks

import (
	"fmt"
	"testing"
	"time"
)

// m5 is a 5-minute bar starting at day hh:mm WIB.
func m5(day, hhmm string, h, l, c int64) Bar {
	return Bar{T: wib(day + " " + hhmm).Unix(), O: c, H: h, L: l, C: c, V: 100}
}

// dly is a daily bar of day (Yahoo stamps IDX daily bars 09:00 WIB).
func dly(day string, h, l, c int64) Bar {
	return Bar{T: wib(day + " 09:00").Unix(), O: c, H: h, L: l, C: c, V: 1000}
}

// buyRec is a buy_today (entry 4900-4950, target 5100, stop 4800, price 4930) called at analyzedAt for session.
func buyRec(analyzedAt, session string) VerdictRecord {
	return VerdictRecord{ID: "vr_x", Code: "BBCA", Verdict: "buy_today", AnalyzedAt: analyzedAt, SessionDate: session, Price: 4930,
		EntryLow: i64(4900), EntryHigh: i64(4950), Target: i64(5100), Stop: i64(4800)}
}

func holdRec(target *int64) VerdictRecord {
	return VerdictRecord{ID: "vr_h", Code: "BBCA", Verdict: "hold", Held: true, AnalyzedAt: "2026-11-05T08:30:00+07:00",
		SessionDate: "2026-11-05", Price: 4930, Stop: i64(4800), Target: target}
}

func plainRec(verdict string) VerdictRecord {
	return VerdictRecord{ID: "vr_p", Code: "BBCA", Verdict: verdict, AnalyzedAt: "2026-11-05T08:30:00+07:00", SessionDate: "2026-11-05", Price: 4930}
}

func pctStr(p *float64) string {
	if p == nil {
		return "nil"
	}
	return fmt.Sprintf("%.2f", *p)
}

const thuDay = "2026-11-05"

func TestScoreRecordTable(t *testing.T) {
	early := "2026-11-05T08:30:00+07:00"
	fill := m5(thuDay, "09:00", 4960, 4920, 4940) // reaches the 4900-4950 zone
	waitEntry := plainRec("wait")
	waitEntry.EntryLow, waitEntry.EntryHigh = i64(4800), i64(4850)
	fri := "2026-11-06"
	tests := []struct {
		name            string
		rec             VerdictRecord
		intraday, daily []Bar
		outcome, source string
		result, info    string
	}{
		{"buy win after the fill", buyRec(early, thuDay),
			[]Bar{fill, m5(thuDay, "09:05", 5000, 4930, 4990), m5(thuDay, "09:10", 5110, 5000, 5100)}, []Bar{dly(thuDay, 5110, 4920, 5050)},
			OutcomeWin, SourceIntraday, "3.03", "nil"},
		{"buy loss after the fill", buyRec(early, thuDay),
			[]Bar{fill, m5(thuDay, "09:05", 4950, 4790, 4800)}, []Bar{dly(thuDay, 4960, 4790, 4850)},
			OutcomeLoss, SourceIntraday, "-3.03", "nil"},
		{"a bar touching both counts the stop", buyRec(early, thuDay),
			[]Bar{fill, m5(thuDay, "09:05", 5120, 4790, 4900)}, []Bar{dly(thuDay, 5120, 4790, 4900)},
			OutcomeLoss, SourceIntraday, "-3.03", "nil"},
		{"a target in the fill bar does not count", buyRec(early, thuDay),
			[]Bar{m5(thuDay, "09:00", 5120, 4940, 5000), m5(thuDay, "09:05", 5050, 4980, 5000)}, []Bar{dly(thuDay, 5120, 4940, 5000)},
			OutcomeClosed, SourceIntraday, "1.01", "nil"},
		{"a stop in the fill bar is a loss", buyRec(early, thuDay),
			[]Bar{m5(thuDay, "09:00", 4960, 4790, 4850), m5(thuDay, "09:05", 5120, 4850, 5100)}, []Bar{dly(thuDay, 5120, 4790, 5100)},
			OutcomeLoss, SourceIntraday, "-3.03", "nil"},
		{"buy closed: neither level by the close", buyRec(early, thuDay),
			[]Bar{fill, m5(thuDay, "16:10", 5000, 4950, 4990)}, []Bar{dly(thuDay, 5000, 4920, 4990)},
			OutcomeClosed, SourceIntraday, "0.81", "nil"},
		{"buy not triggered", buyRec(early, thuDay),
			[]Bar{m5(thuDay, "09:00", 5000, 4960, 4980), m5(thuDay, "09:05", 5120, 4970, 5100)}, []Bar{dly(thuDay, 5120, 4960, 5100)},
			OutcomeNotTriggered, SourceIntraday, "nil", "nil"},
		// P12: a 13:58 call ignores the 10:00 bar that touched the zone and the stop; it fills at 14:00 and wins.
		{"look-ahead: a 14:00 call ignores a 10:00 touch", buyRec("2026-11-05T13:58:00+07:00", thuDay),
			[]Bar{m5(thuDay, "10:00", 4960, 4790, 4850), m5(thuDay, "14:00", 4960, 4920, 4940), m5(thuDay, "14:05", 5110, 4940, 5100)},
			[]Bar{dly(thuDay, 5110, 4790, 5050)}, OutcomeWin, SourceIntraday, "3.03", "nil"},
		{"look-ahead: no bar after the call fills it", buyRec("2026-11-05T13:58:00+07:00", thuDay),
			[]Bar{m5(thuDay, "10:00", 4960, 4920, 4940), m5(thuDay, "14:00", 5050, 4980, 5000)}, []Bar{dly(thuDay, 5050, 4920, 5000)},
			OutcomeNotTriggered, SourceIntraday, "nil", "nil"},
		{"daily fallback: stop first", buyRec(early, thuDay), nil, []Bar{dly(thuDay, 5120, 4790, 5000)},
			OutcomeLoss, SourceDaily, "-3.03", "nil"},
		// P15a: on the daily bar a target in the fill day never counts (the day may have reached it before the fill).
		{"daily fallback: a target in the fill day does not count", buyRec(early, thuDay), nil, []Bar{dly(thuDay, 5120, 4900, 5000)},
			OutcomeClosed, SourceDaily, "1.01", "nil"},
		// Q125: the fill is a limit buy at entry_high: a bar opening below the zone fills; the stop is checked in that bar.
		{"Q125: a gap below the zone fills, then the target", buyRec(early, thuDay),
			[]Bar{m5(thuDay, "09:00", 4890, 4850, 4870), m5(thuDay, "09:05", 5110, 4950, 5100)}, []Bar{dly(thuDay, 5110, 4850, 5100)},
			OutcomeWin, SourceIntraday, "3.03", "nil"},
		{"Q125: a gap below the zone and the stop is a loss", buyRec(early, thuDay),
			[]Bar{m5(thuDay, "09:00", 4790, 4700, 4750), m5(thuDay, "09:05", 4800, 4700, 4760)}, []Bar{dly(thuDay, 4800, 4700, 4760)},
			OutcomeLoss, SourceIntraday, "-3.03", "nil"},
		{"Q125: a gap below the zone, then back up: closed", buyRec(early, thuDay),
			[]Bar{m5(thuDay, "09:00", 4880, 4850, 4870), m5(thuDay, "09:05", 4990, 4940, 4980), m5(thuDay, "16:10", 5000, 4970, 4990)},
			[]Bar{dly(thuDay, 5000, 4850, 4990)}, OutcomeClosed, SourceIntraday, "0.81", "nil"},
		{"Q125: daily fallback, a day below the zone fills", buyRec(early, thuDay), nil, []Bar{dly(thuDay, 4890, 4850, 4870)},
			OutcomeClosed, SourceDaily, "-1.62", "nil"},
		// P12: "start after" the call: a bar starting exactly at analyzed_at is excluded (its stop touch may precede the call).
		{"a bar starting exactly at the call does not count", buyRec("2026-11-05T09:00:00+07:00", thuDay),
			[]Bar{m5(thuDay, "09:00", 4960, 4790, 4850), m5(thuDay, "09:05", 4960, 4920, 4940), m5(thuDay, "09:10", 5110, 4940, 5100)},
			[]Bar{dly(thuDay, 5110, 4790, 5100)}, OutcomeWin, SourceIntraday, "3.03", "nil"},
		{"daily fallback: not triggered", buyRec(early, thuDay), nil, []Bar{dly(thuDay, 5120, 4960, 5000)},
			OutcomeNotTriggered, SourceDaily, "nil", "nil"},
		{"5m bars of other days only: daily", buyRec(early, thuDay), []Bar{m5("2026-11-04", "09:00", 4960, 4700, 4800)},
			[]Bar{dly(thuDay, 5000, 4920, 4990)}, OutcomeClosed, SourceDaily, "0.81", "nil"},
		{"no session: no bar at all", buyRec(early, thuDay), nil, nil, OutcomeNoSession, SourceDaily, "nil", "nil"},
		{"no session: other days only (unlisted holiday)", buyRec(early, thuDay), nil,
			[]Bar{dly("2026-11-04", 5000, 4900, 4950), dly(fri, 5000, 4900, 4950)}, OutcomeNoSession, SourceDaily, "nil", "nil"},
		{"no session: no info_3d even when later sessions exist (P16)", buyRec(early, thuDay), nil,
			[]Bar{dly(fri, 5000, 4900, 4950), dly("2026-11-09", 5000, 4900, 4950), dly("2026-11-10", 5000, 4900, 4950)},
			OutcomeNoSession, SourceDaily, "nil", "nil"},
		{"hold: stop first is a loss", holdRec(i64(5100)),
			[]Bar{m5(thuDay, "09:00", 4950, 4790, 4850), m5(thuDay, "09:05", 5110, 4850, 5100)}, []Bar{dly(thuDay, 5110, 4790, 5100)},
			OutcomeLoss, SourceIntraday, "-2.64", "nil"},
		{"hold: target first is a win", holdRec(i64(5100)),
			[]Bar{m5(thuDay, "09:00", 5110, 4900, 5100), m5(thuDay, "09:05", 5100, 4790, 4800)}, []Bar{dly(thuDay, 5110, 4790, 4800)},
			OutcomeWin, SourceIntraday, "3.45", "nil"},
		{"hold: both in one bar is a loss", holdRec(i64(5100)), []Bar{m5(thuDay, "09:00", 5110, 4790, 5000)},
			[]Bar{dly(thuDay, 5110, 4790, 5000)}, OutcomeLoss, SourceIntraday, "-2.64", "nil"},
		{"hold without a target: the stop held", holdRec(nil),
			[]Bar{m5(thuDay, "09:00", 5300, 4850, 5000), m5(thuDay, "16:10", 5000, 4950, 4980)}, []Bar{dly(thuDay, 5300, 4850, 4980)},
			OutcomeRight, SourceIntraday, "1.01", "nil"},
		{"hold daily fallback: stop first", holdRec(i64(5100)), nil, []Bar{dly(thuDay, 5110, 4790, 5000)},
			OutcomeLoss, SourceDaily, "-2.64", "nil"},
		{"avoid: close below the price is right", plainRec("avoid"), []Bar{m5(thuDay, "09:00", 5000, 4880, 4900)},
			[]Bar{dly(thuDay, 5000, 4880, 4900)}, OutcomeRight, SourceIntraday, "-0.61", "nil"},
		{"avoid: close equal to the price is right", plainRec("avoid"), nil, []Bar{dly(thuDay, 5000, 4880, 4930)},
			OutcomeRight, SourceDaily, "0.00", "nil"},
		{"sell: close above the price is wrong", plainRec("sell"), []Bar{m5(thuDay, "09:00", 5000, 4880, 4980)},
			[]Bar{dly(thuDay, 5000, 4880, 4980)}, OutcomeWrong, SourceIntraday, "1.01", "nil"},
		{"wait with an entry: from entry_high", waitEntry, nil, []Bar{dly(thuDay, 5000, 4880, 4900)},
			OutcomeWouldHave, SourceDaily, "1.03", "nil"},
		{"wait without an entry: from the price", plainRec("wait"), nil, []Bar{dly(thuDay, 5000, 4880, 4900)},
			OutcomeWouldHave, SourceDaily, "-0.61", "nil"},
		// Friday: the session breaks 11:30-14:00. A call in the break (11:40) ignores the morning's 11:25 stop touch and
		// fills in the 14:00 bar.
		{"Friday short session", buyRec("2026-11-06T11:40:00+07:00", fri),
			[]Bar{m5(fri, "11:25", 4960, 4790, 4850), m5(fri, "14:00", 4960, 4920, 4940), m5(fri, "15:00", 5110, 4940, 5100),
				m5(fri, "16:10", 5100, 5050, 5080)}, []Bar{dly(fri, 5110, 4790, 5080)}, OutcomeWin, SourceIntraday, "3.03", "nil"},
		// Q120b: a Thursday 16:30 call is for Friday: Thursday's closing-auction bars (stop touched) and close do not count.
		{"a call after 16:00 scores the next session", buyRec("2026-11-05T16:30:00+07:00", fri),
			[]Bar{m5(thuDay, "16:00", 4960, 4790, 4800), m5(thuDay, "16:10", 4900, 4790, 4800), m5(fri, "09:00", 4960, 4920, 4940),
				m5(fri, "09:05", 5110, 4940, 5100)}, []Bar{dly(thuDay, 4960, 4790, 4800), dly(fri, 5110, 4920, 5100)},
			OutcomeWin, SourceIntraday, "3.03", "nil"},
		{"close from the last 5m bar without a daily bar", buyRec(early, thuDay),
			[]Bar{fill, m5(thuDay, "16:10", 5000, 4950, 4990)}, nil, OutcomeClosed, SourceIntraday, "0.81", "nil"},
		{"info_3d: the third session after", buyRec(early, thuDay), nil,
			[]Bar{dly(thuDay, 5000, 4920, 4990), dly(fri, 5000, 4900, 4950), dly("2026-11-09", 5000, 4900, 4960), dly("2026-11-10", 5200, 5000, 5148)},
			OutcomeClosed, SourceDaily, "0.81", "4.00"},
	}
	at := wibs("2026-11-05 17:00:00")
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			sc := scoreRecord(tc.rec, tc.intraday, tc.daily, at)
			if sc.Outcome != tc.outcome || sc.Source != tc.source || pctStr(sc.ResultPct) != tc.result || pctStr(sc.Info3dPct) != tc.info {
				t.Fatalf("score = %s/%s result %s info %s, want %s/%s %s %s", sc.Outcome, sc.Source, pctStr(sc.ResultPct),
					pctStr(sc.Info3dPct), tc.outcome, tc.source, tc.result, tc.info)
			}
			if sc.Rule != ScoreRule || sc.ScoredAt != "2026-11-05T17:00:00+07:00" {
				t.Fatalf("rule %d scored_at %s", sc.Rule, sc.ScoredAt)
			}
		})
	}
}

// TestInfo3dAcrossWeekendAndHoliday: a Wednesday 2026-12-23 session; Thu 24 and Fri 25 are IDX holidays, then the
// weekend: the third session after is Wed 12-30. With only two later sessions the column stays empty.
func TestInfo3dAcrossWeekendAndHoliday(t *testing.T) {
	r := plainRec("avoid")
	r.SessionDate, r.Price = "2026-12-23", 1000
	if IsTradingDay(wib("2026-12-24 09:00")) || IsTradingDay(wib("2026-12-25 09:00")) {
		t.Fatal("the bundled calendar lost the Christmas holidays this test relies on")
	}
	daily := []Bar{dly("2026-12-23", 1000, 990, 1000), dly("2026-12-28", 1010, 990, 1010), dly("2026-12-29", 1020, 1000, 1020)}
	if p := info3dPct(r, daily); p != nil {
		t.Fatalf("two sessions after: info_3d = %v, want nil", *p)
	}
	daily = append(daily, dly("2026-12-30", 1060, 1020, 1050), dly("2027-01-04", 1100, 1050, 1100))
	if p := info3dPct(r, daily); pctStr(p) != "5.00" {
		t.Fatalf("info_3d = %s, want 5.00 (12-30 close)", pctStr(p))
	}
	sc := scoreRecord(r, nil, daily, time.Now())
	if sc.Outcome != OutcomeRight || pctStr(sc.Info3dPct) != "5.00" {
		t.Fatalf("score = %+v", sc)
	}
}

// TestScoreRecordUnscorable (MAJOR 3): a record without the levels its verdict needs, or with an unreadable call time or
// session date, scores unscorable without a panic, and a bad analyzed_at never falls open to every bar of the session.
func TestScoreRecordUnscorable(t *testing.T) {
	noLevels := buyRec("2026-11-05T08:30:00+07:00", thuDay)
	noLevels.EntryLow, noLevels.Target = nil, nil
	holdNoStop := holdRec(i64(5100))
	holdNoStop.Stop = nil
	badAt := buyRec("2026-11-05 08:30", thuDay)
	badDay := buyRec("2026-11-05T08:30:00+07:00", "2026-11-31")
	unknown := plainRec("maybe")
	buyWith := func(edit func(r *VerdictRecord)) VerdictRecord {
		r := buyRec("2026-11-05T08:30:00+07:00", thuDay)
		edit(&r)
		return r
	}
	avoidNoPrice := plainRec("avoid")
	avoidNoPrice.Price = 0
	session := []Bar{m5(thuDay, "09:00", 4960, 4920, 4940), m5(thuDay, "09:05", 5110, 4940, 5100)}
	daily := []Bar{dly(thuDay, 5110, 4920, 5100)}
	for name, r := range map[string]VerdictRecord{"buy without levels": noLevels, "hold without a stop": holdNoStop,
		"bad analyzed_at": badAt, "bad session_date": badDay, "unknown verdict": unknown,
		// verdict.go's level order, each part on its own: 0 < stop < entry_low <= entry_high < target.
		"buy stop zero":                  buyWith(func(r *VerdictRecord) { r.Stop = i64(0) }),
		"buy stop at entry_low":          buyWith(func(r *VerdictRecord) { r.Stop = i64(4900) }),
		"buy entry_low above entry_high": buyWith(func(r *VerdictRecord) { r.EntryLow = i64(4960) }),
		"buy target at entry_high":       buyWith(func(r *VerdictRecord) { r.Target = i64(4950) }),
		"buy price zero":                 buyWith(func(r *VerdictRecord) { r.Price = 0 }),
		"avoid price zero":               avoidNoPrice,
	} {
		t.Run(name, func(t *testing.T) {
			sc := scoreRecord(r, session, daily, wibs("2026-11-05 17:00:00"))
			if sc.Outcome != OutcomeUnscorable || sc.ResultPct != nil || sc.Info3dPct != nil {
				t.Fatalf("score = %+v result %s", sc, pctStr(sc.ResultPct))
			}
		})
	}
}

// TestLaterInfo3dSplitBothWays (P16): a refetched session close more than 1 % below (a split) or above (a reverse split)
// the stored close leaves info_3d empty; within 1 % it is filled.
func TestLaterInfo3dSplitBothWays(t *testing.T) {
	r := buyRec("2026-11-05T08:30:00+07:00", thuDay)
	r.Score = &Score{Rule: ScoreRule, Source: SourceIntraday, Outcome: OutcomeClosed, Close: i64(5080)}
	later := func(sessionClose, scale int64) []Bar {
		return []Bar{dly(thuDay, sessionClose, sessionClose, sessionClose), dly("2026-11-06", 5050*scale, 5000*scale, 5050*scale),
			dly("2026-11-09", 5050*scale, 5000*scale, 5050*scale), dly("2026-11-10", 5148*scale, 5000*scale, 5148*scale)}
	}
	for _, tc := range []struct {
		name  string
		daily []Bar
		moved bool
		info  string
	}{
		{"split 1:5", later(1016, 1), true, "nil"},
		{"reverse split 5:1", later(25400, 5), true, "nil"},
		{"1.1 % up", later(5136, 1), true, "nil"},
		{"within 1 %", later(5100, 1), false, "4.00"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			info, moved := laterInfo3d(r, tc.daily)
			if moved != tc.moved || pctStr(info) != tc.info {
				t.Fatalf("moved %v info %s, want %v %s", moved, pctStr(info), tc.moved, tc.info)
			}
		})
	}
}
