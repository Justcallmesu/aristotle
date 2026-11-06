package stocks

import (
	"slices"
	"time"
)

// The track-record scorer (spec Q117-Q120a, P12, P13; protocol §20.5): a pure function from one history record and
// its bars to a Score. No I/O: the track.score job fetches the bars and stores the result.

// ScoreRule is the version of the rules below; a stored score keeps the rule it was made with (P13).
const ScoreRule = 1

// Score outcomes.
const (
	OutcomeWin          = "win"           // buy_today/add: target after the fill; hold: target before the stop
	OutcomeLoss         = "loss"          // the stop first (a bar touching both counts the stop)
	OutcomeClosed       = "closed"        // buy_today/add: filled, neither level by the session close
	OutcomeNotTriggered = "not_triggered" // buy_today/add: no price at or below entry_high after the call (Q118, Q125)
	OutcomeRight        = "right"         // hold: the stop held; avoid/sell: the close not above the price at the call
	OutcomeWrong        = "wrong"         // avoid/sell: the close above the price at the call
	OutcomeWouldHave    = "would_have"    // wait: what a buy would have made, no right/wrong (Q120)
	OutcomeNoSession    = "no_session"    // no bar for the session (no trade, unknown code): never a loss
	OutcomeUnscorable   = "unscorable"    // P16: Yahoo kept failing for 10 trading days; excluded from every rate
)

// Score sources: how the levels were ordered.
const (
	SourceIntraday = "5m"    // the session's 5-minute bars that start after the call (P12)
	SourceDaily    = "daily" // the session's daily bar, stop first (Q119, P15a)
	SourceNone     = "none"  // unscorable: no bar decided it
)

const (
	// info3dSessions is the session after the call's session whose close the info column reads (Q117).
	info3dSessions = 3
	// splitMovePct: a refetched session close this far (%) from the stored one means Yahoo adjusted the history (a
	// split): info_3d is left empty (P16).
	splitMovePct = 1.0
)

// Score is a record's outcome on its session. ResultPct is from the base (entry_high for buy_today/add and a wait
// with an entry, else the price at the call) to the exit (target, stop or the close); null when not triggered or no
// session. Close is the session close used. Info3dPct is the close info3dSessions trading days later vs the same base.
type Score struct {
	Rule      int      `json:"rule"`
	Source    string   `json:"source"`
	Outcome   string   `json:"outcome"`
	ResultPct *float64 `json:"result_pct"`
	Close     *int64   `json:"close"`
	Info3dPct *float64 `json:"info_3d_pct"`
	ScoredAt  string   `json:"scored_at"`
}

// callLevels are the prices a record's rule reads (target only when hasTarget).
type callLevels struct {
	entryLow, entryHigh, target, stop int64
	hasTarget                         bool
}

// verdictCall is a record read for scoring: everything its rule needs, checked once, so the scorer has no nil pointer or
// unreadable time to trip on.
type verdictCall struct {
	verdict string
	at      time.Time // analyzed_at
	day     time.Time // session_date, 00:00 WIB
	price   int64
	base    int64 // what result_pct and info_3d are measured from (Q120a)
	lv      callLevels
}

// readCall checks that r can be scored (an RFC 3339 analyzed_at, a session date, a price, and the levels its verdict
// needs per the analyzer's level rules, verdict.go) and reads it; ok is false for a record that never can be.
func readCall(r VerdictRecord) (c verdictCall, ok bool) {
	at, err := time.Parse(time.RFC3339, r.AnalyzedAt)
	day, dayOK := parseSessionDay(r.SessionDate)
	if err != nil || !dayOK || r.Price <= 0 {
		return verdictCall{}, false
	}
	c = verdictCall{verdict: r.Verdict, at: at, day: day, price: r.Price, base: r.Price}
	switch r.Verdict {
	case "buy_today", "add":
		if r.EntryLow == nil || r.EntryHigh == nil || r.Target == nil || r.Stop == nil {
			return verdictCall{}, false
		}
		c.lv = callLevels{entryLow: *r.EntryLow, entryHigh: *r.EntryHigh, target: *r.Target, stop: *r.Stop, hasTarget: true}
		c.base = c.lv.entryHigh
		return c, 0 < c.lv.stop && c.lv.stop < c.lv.entryLow && c.lv.entryLow <= c.lv.entryHigh && c.lv.entryHigh < c.lv.target
	case "hold":
		if r.Stop == nil {
			return verdictCall{}, false
		}
		c.lv.stop = *r.Stop
		if r.Target != nil {
			c.lv.target, c.lv.hasTarget = *r.Target, true
		}
		return c, true
	case "wait":
		if r.EntryHigh != nil && *r.EntryHigh > 0 {
			c.base = *r.EntryHigh
		}
		return c, true
	case "avoid", "sell":
		return c, true
	}
	return verdictCall{}, false
}

// scorable reports whether r can ever be scored (the planner skips the others).
func scorable(r VerdictRecord) bool {
	_, ok := readCall(r)
	return ok
}

// unscorableScore is the P16 give-up score: no source, result, close or info.
func unscorableScore(scoredAt time.Time) Score {
	return Score{Rule: ScoreRule, Source: SourceNone, Outcome: OutcomeUnscorable, ScoredAt: *rfc(scoredAt)}
}

// scoreRecord scores r. intraday holds 5-minute bars (any days; only r's session counts), daily completed daily
// bars (any days). With no 5-minute bar of the session the daily bar scores it, stop first; with neither the
// outcome is no_session (no info_3d, P16). The caller passes only data that is final for the session (it has
// closed) and only scorable records; any other record scores unscorable.
func scoreRecord(r VerdictRecord, intraday, daily []Bar, scoredAt time.Time) Score {
	c, ok := readCall(r)
	if !ok {
		return unscorableScore(scoredAt)
	}
	sc := Score{Rule: ScoreRule, Source: SourceDaily, ScoredAt: *rfc(scoredAt)}
	session := barsOn(intraday, c.day)
	dayBars := barsOn(daily, c.day)
	var walk []Bar
	var closePx int64
	switch {
	case len(session) > 0:
		sc.Source = SourceIntraday
		walk = barsAfter(session, c.at)
		closePx = session[len(session)-1].C
	case len(dayBars) > 0:
		walk = dayBars[len(dayBars)-1:]
	default:
		sc.Outcome = OutcomeNoSession
		return sc
	}
	if len(dayBars) > 0 {
		closePx = dayBars[len(dayBars)-1].C // the daily close includes the closing auction (TR0 8d)
	}
	sc.Close = &closePx
	sc.Info3dPct = info3dOf(c, daily)
	outcome, exit := judgeSession(c, walk, closePx)
	sc.Outcome = outcome
	if exit > 0 {
		p := Pct(exit-c.base, c.base)
		sc.ResultPct = &p
	}
	return sc
}

// judgeSession is the rule per verdict type over walk (oldest first) and the session close; exit is the price the
// result is measured at, 0 for none.
func judgeSession(c verdictCall, walk []Bar, closePx int64) (outcome string, exit int64) {
	switch c.verdict {
	case "buy_today", "add":
		return judgeBuy(c.lv, walk, closePx)
	case "hold":
		return judgeHold(c.lv, walk, closePx)
	case "avoid", "sell":
		if closePx <= c.price {
			return OutcomeRight, closePx
		}
		return OutcomeWrong, closePx
	}
	return OutcomeWouldHave, closePx // wait
}

// judgeBuy: the fill is a limit buy at entry_high (Q125): the first bar whose low is at or below it (a gap below the
// zone fills too). In the fill bar a stop counts and a target does not (it may have come before the fill, P12; on a
// daily bar the whole day is the fill bar, P15a); after it the first level touched wins, a bar touching both counts
// the stop.
func judgeBuy(lv callLevels, walk []Bar, closePx int64) (string, int64) {
	fill := slices.IndexFunc(walk, func(b Bar) bool { return b.L <= lv.entryHigh })
	switch {
	case fill < 0:
		return OutcomeNotTriggered, 0
	case walk[fill].L <= lv.stop:
		return OutcomeLoss, lv.stop
	}
	if out, exit, hit := firstLevel(walk[fill+1:], lv); hit {
		return out, exit
	}
	return OutcomeClosed, closePx
}

// judgeHold: the stop first is a loss, the target (when given) first a win, neither: right, at the close (Q120a).
func judgeHold(lv callLevels, walk []Bar, closePx int64) (string, int64) {
	if out, exit, hit := firstLevel(walk, lv); hit {
		return out, exit
	}
	return OutcomeRight, closePx
}

// firstLevel is the first bar touching the stop (loss) or the target (win, when there is one); stop first.
func firstLevel(walk []Bar, lv callLevels) (string, int64, bool) {
	for _, b := range walk {
		if b.L <= lv.stop {
			return OutcomeLoss, lv.stop, true
		}
		if lv.hasTarget && b.H >= lv.target {
			return OutcomeWin, lv.target, true
		}
	}
	return "", 0, false
}

// info3dPct is r's info column from daily (nil for a record that cannot be scored or before the bar exists).
func info3dPct(r VerdictRecord, daily []Bar) *float64 {
	c, ok := readCall(r)
	if !ok {
		return nil
	}
	return info3dOf(c, daily)
}

// info3dOf is the close info3dSessions trading sessions after the call's session vs the base; nil until that
// session's bar exists. Sessions are counted from the bars, not the calendar (the bundled holiday list is
// incomplete for past dates, TR0 8c).
func info3dOf(c verdictCall, daily []Bar) *float64 {
	n := 0
	var last time.Time
	for _, b := range daily {
		d := barDay(b)
		if !d.After(c.day) || b.C <= 0 || d.Equal(last) {
			continue
		}
		last = d
		if n++; n == info3dSessions {
			p := Pct(b.C-c.base, c.base)
			return &p
		}
	}
	return nil
}

// laterInfo3d is the info column for a record scored earlier, from refetched daily bars. moved reports that the
// refetched session close is more than splitMovePct from the stored close (or cannot be compared): Yahoo adjusted
// the history (a split), so info stays empty (P16).
func laterInfo3d(r VerdictRecord, daily []Bar) (info *float64, moved bool) {
	c, ok := readCall(r)
	if !ok || r.Score == nil || r.Score.Close == nil || *r.Score.Close <= 0 {
		return nil, true
	}
	dayBars := barsOn(daily, c.day)
	if len(dayBars) == 0 {
		return nil, true
	}
	stored := *r.Score.Close
	if diff := Pct(dayBars[len(dayBars)-1].C-stored, stored); diff > splitMovePct || diff < -splitMovePct {
		return nil, true
	}
	return info3dOf(c, daily), false
}

// barsAfter keeps the bars that start after the call (P12: no look-ahead; a bar starting at the call is excluded).
func barsAfter(bars []Bar, at time.Time) []Bar {
	var out []Bar
	for _, b := range bars {
		if b.T > at.Unix() {
			out = append(out, b)
		}
	}
	return out
}

// parseSessionDay reads a YYYY-MM-DD session date as 00:00 WIB.
func parseSessionDay(s string) (time.Time, bool) {
	d, err := time.ParseInLocation(time.DateOnly, s, WIB)
	return d, err == nil
}
