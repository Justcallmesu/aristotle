package stocks

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"maps"
	"slices"
	"strings"
	"time"
	"unicode/utf8"
)

// Analyzer prompt, verdict schema and checks (docs/v2-stocks/runs/TA0/report.md §7a/§7d, spec Q96-Q98,
// Q101, P6). The model only judges numbers the server computed; every price it gives is checked against
// the tick table and the auto-reject limits and is never snapped.

const (
	verdictSchema    = "stock_verdict/1"
	maxNewsInPrompt  = 8
	maxSummaryRunes  = 280
	maxReasonRunes   = 200
	minReasons       = 3
	maxReasons       = 5
	maxRepairErrors  = 5
	minRewardRisk    = 1.5
	maxStopATR       = 2.0
	maxEntryDistPct  = 3.0
	analysisLangID   = "id"
	analysisLangEN   = "en"
	confidenceLevels = "low|med|high"
)

// Verdicts (Q98): not held → buy_today / wait / avoid; held → add / hold / sell.
var (
	verdictsNotHeld = []string{"buy_today", "wait", "avoid"}
	verdictsHeld    = []string{"add", "hold", "sell"}
)

// Flags kept with a verdict that passed the checks.
const (
	FlagLowRewardRisk = "low_reward_risk"
	FlagWideStop      = "wide_stop"
	FlagEntryFar      = "entry_far"
	FlagTargetAboveR1 = "target_above_r1"
	FlagMarketClosed  = "market_closed"
	FlagLimitsUnknown = "limits_unknown"
)

// NewsRef is one headline given to the model, kept with the result so the phone can show sources.
type NewsRef struct {
	ID          string `json:"id"`
	Source      string `json:"source"`
	Title       string `json:"title"`
	Link        string `json:"link"`
	PublishedAt string `json:"published_at"`
	Summary     string `json:"-"`
}

// HoldingInput is the user's position as the model sees it (never logged).
type HoldingInput struct {
	Lots     int64    `json:"lots"`
	AvgPrice float64  `json:"avg_price"`
	PLPct    *float64 `json:"pl_pct"` // after fees, at the current price
}

// analysisInput is everything one analysis is built from.
type analysisInput struct {
	Code, Name string
	Lang       string
	AsOf       string // RFC 3339 WIB: the time of the newest price used
	Ind        Indicators
	Holding    *HoldingInput
	News       []NewsRef
}

func (in analysisInput) held() bool { return in.Holding != nil }

func (in analysisInput) verdicts() []string {
	if in.held() {
		return verdictsHeld
	}
	return verdictsNotHeld
}

// indicatorIDs maps the citable ind:* ids to their values (only the known ones).
func (in analysisInput) indicatorIDs() map[string]any {
	ind := in.Ind
	m := map[string]any{"ind:price": ind.Price, "ind:trend": ind.Trend}
	putF := func(k string, v *float64) {
		if v != nil {
			m[k] = *v
		}
	}
	putI := func(k string, v *int64) {
		if v != nil {
			m[k] = *v
		}
	}
	putF("ind:change_pct", ind.ChangePct)
	putF("ind:last_change_pct", ind.LastChangePct)
	putF("ind:ma20", ind.MA20)
	putF("ind:ma50", ind.MA50)
	putF("ind:dist_ma20_pct", ind.DistMA20Pct)
	putF("ind:dist_ma50_pct", ind.DistMA50Pct)
	putF("ind:rsi14", ind.RSI14)
	putF("ind:atr14", ind.ATR14)
	putI("ind:avg_vol20", ind.AvgVol20)
	putI("ind:vol_today", ind.VolToday)
	putF("ind:vol_ratio", ind.VolRatio)
	putF("ind:vol_ratio_paced", ind.VolRatioPaced)
	putF("ind:vol_ratio_prev", ind.VolRatioPrev)
	putI("ind:high52", ind.High52)
	putI("ind:low52", ind.Low52)
	putF("ind:pos52", ind.Pos52)
	if d := ind.Intraday; d != nil {
		m["ind:open"], m["ind:day_high"], m["ind:day_low"], m["ind:vwap"], m["ind:gap_pct"] = d.Open, d.High, d.Low, d.VWAP, d.GapPct
	}
	return m
}

// levelIDs maps the citable lvl:* ids to their prices.
func (in analysisInput) levelIDs() map[string]any {
	lv := in.Ind.Levels
	m := map[string]any{}
	for k, v := range map[string]*int64{"lvl:s1": lv.S1, "lvl:s2": lv.S2, "lvl:r1": lv.R1, "lvl:r2": lv.R2,
		"lvl:high20": lv.High20, "lvl:low20": lv.Low20, "lvl:pivot": lv.Pivot, "lvl:pivot_r1": lv.PivotR1, "lvl:pivot_s1": lv.PivotS1} {
		if v != nil {
			m[k] = *v
		}
	}
	return m
}

func (in analysisInput) holdingIDs() map[string]any {
	if in.Holding == nil {
		return nil
	}
	m := map[string]any{"hold:lots": in.Holding.Lots, "hold:avg_price": in.Holding.AvgPrice}
	if in.Holding.PLPct != nil {
		m["hold:pl_pct"] = *in.Holding.PLPct
	}
	return m
}

// sourceIDs is every id a reason may cite.
func (in analysisInput) sourceIDs() map[string]bool {
	ids := map[string]bool{}
	for _, m := range []map[string]any{in.indicatorIDs(), in.levelIDs(), in.holdingIDs()} {
		for k := range m {
			ids[k] = true
		}
	}
	for _, n := range in.News {
		ids[n.ID] = true
	}
	return ids
}

// dataJSON is the DATA block of the prompt.
func (in analysisInput) dataJSON() string {
	type newsData struct {
		ID          string `json:"id"`
		Source      string `json:"source"`
		PublishedAt string `json:"published_at"`
		Title       string `json:"title"`
		Summary     string `json:"summary"`
	}
	news := []newsData{}
	for _, n := range in.News {
		news = append(news, newsData{n.ID, n.Source, n.PublishedAt, n.Title, n.Summary})
	}
	var holding any
	if h := in.holdingIDs(); h != nil {
		holding = h
	}
	data := map[string]any{
		"as_of": in.AsOf, "code": in.Code, "name": in.Name, "session_date": in.Ind.SessionDate, "market_state": in.Ind.MarketState,
		"quote":  map[string]any{"price": in.Ind.Price, "prev_close": in.Ind.Base, "tick": in.Ind.Tick},
		"limits": map[string]any{"ara": in.Ind.ARA, "arb": in.Ind.ARB}, "indicators": in.indicatorIDs(), "levels": in.levelIDs(),
		"holding": holding, "news": news,
	}
	b, _ := json.Marshal(data)
	return string(b)
}

// analysisPrompt is the one prompt.submit text (TA0 §7a). Hermes owns the system prompt, so our
// "system" block leads the user text.
func analysisPrompt(in analysisInput) string {
	lang := "English"
	if in.Lang == analysisLangID {
		lang = "Bahasa Indonesia"
	}
	limits := "ARA/ARB unknown for this date: keep prices near the current price"
	if in.Ind.ARA != nil && in.Ind.ARB != nil {
		limits = fmt.Sprintf("every price is an integer IDR in [%d, %d]", *in.Ind.ARB, *in.Ind.ARA)
	}
	var b strings.Builder
	fmt.Fprintf(&b, "SYSTEM: You are an IDX day-trading analyst. Judge only the DATA below. Never invent prices, figures or news. You have no tools.\n")
	fmt.Fprintf(&b, "TASK: %s for the session on %s (market %s). Held: %s. Verdicts allowed: %s.\n",
		in.Code, in.Ind.SessionDate, in.Ind.MarketState, yesNo(in.held()), strings.Join(in.verdicts(), "|"))
	fmt.Fprintf(&b, "RULES: %s and a multiple of its band's tick (<200:1, <500:2, <2000:5, <5000:10, else 25).\n", limits)
	b.WriteString("buy_today/add: stop < entry.low <= entry.high < target. hold: stop set below the price, entry null. " +
		"sell/avoid: entry, target, stop null. wait: entry = trigger zone or null.\n")
	fmt.Fprintf(&b, "Base levels on DATA.levels and DATA.indicators. Give %d-%d reasons, each citing at least one id from DATA "+
		"(ind:*, lvl:*, news:*, hold:*). Write summary and reasons in %s. Reply with ONLY this JSON object, no prose, no code fence:\n",
		minReasons, maxReasons, lang)
	fmt.Fprintf(&b, `{"schema":"%s","code":"%s","data_as_of":"<copy DATA.as_of>","verdict":"<one allowed verdict>","confidence":"%s",`+
		`"entry":{"low":0,"high":0} or null,"target":0 or null,"stop":0 or null,"summary":"<=%d chars",`+
		`"reasons":[{"text":"<=%d chars","sources":["ind:rsi14"]}]}`+"\n", verdictSchema, in.Code, confidenceLevels, maxSummaryRunes, maxReasonRunes)
	b.WriteString("DATA: ")
	b.WriteString(in.dataJSON())
	return b.String()
}

func repairPrompt(errs []string) string {
	if len(errs) > maxRepairErrors {
		errs = errs[:maxRepairErrors]
	}
	return "Your reply failed validation: " + strings.Join(errs, "; ") + ". Reply again with ONLY the corrected JSON object."
}

func yesNo(b bool) string {
	if b {
		return "yes"
	}
	return "no"
}

// Reason is one cited reason of a verdict.
type Reason struct {
	Text    string   `json:"text"`
	Sources []string `json:"sources"`
}

// Verdict is a parsed, schema-valid model reply.
type Verdict struct {
	Verdict    string
	Confidence string
	EntryLow   *int64
	EntryHigh  *int64
	Target     *int64
	Stop       *int64
	Summary    string
	Reasons    []Reason
}

var verdictKeys = []string{"schema", "code", "data_as_of", "verdict", "confidence", "entry", "target", "stop", "summary", "reasons"}

// parseVerdict strips one surrounding code fence and checks the reply against the schema (exactly one
// object, exactly the known keys, enums, the code/data_as_of echo, reason counts and lengths, and that
// every source id exists in DATA). It returns the schema errors; the verdict is usable only without any.
func parseVerdict(reply string, in analysisInput) (Verdict, []string) {
	var raw map[string]json.RawMessage
	if err := decodeOne(stripFence(reply), &raw); err != nil || raw == nil {
		return Verdict{}, []string{"the reply is not exactly one JSON object"}
	}
	var errs []string
	add := func(format string, a ...any) { errs = append(errs, fmt.Sprintf(format, a...)) }
	for _, k := range slices.Sorted(maps.Keys(raw)) {
		if !slices.Contains(verdictKeys, k) {
			add("unknown field %q", k)
		}
	}
	for _, k := range verdictKeys {
		if _, ok := raw[k]; !ok {
			add("missing field %q", k)
		}
	}
	if len(errs) > 0 {
		return Verdict{}, errs
	}
	var v Verdict
	str := func(k string) string {
		var s string
		if json.Unmarshal(raw[k], &s) != nil {
			add("%s must be a string", k)
		}
		return s
	}
	if s := str("schema"); s != verdictSchema {
		add("schema must be %q", verdictSchema)
	}
	if s := str("code"); s != in.Code {
		add("code must be %q", in.Code)
	}
	if s := str("data_as_of"); s != in.AsOf {
		add("data_as_of must be %q (copy DATA.as_of)", in.AsOf)
	}
	v.Verdict = str("verdict")
	if !slices.Contains(in.verdicts(), v.Verdict) {
		add("verdict must be one of %s", strings.Join(in.verdicts(), "|"))
	}
	v.Confidence = str("confidence")
	if !slices.Contains(strings.Split(confidenceLevels, "|"), v.Confidence) {
		add("confidence must be one of %s", confidenceLevels)
	}
	v.Summary = str("summary")
	if n := utf8.RuneCountInString(v.Summary); n == 0 || n > maxSummaryRunes {
		add("summary must be 1-%d characters", maxSummaryRunes)
	}
	v.EntryLow, v.EntryHigh = parseEntry(raw["entry"], add)
	v.Target, v.Stop = parsePrice("target", raw["target"], add), parsePrice("stop", raw["stop"], add)
	v.Reasons = parseReasons(raw["reasons"], in.sourceIDs(), add)
	return v, errs
}

func stripFence(s string) string {
	s = strings.TrimSpace(s)
	if !strings.HasPrefix(s, "```") || !strings.HasSuffix(s, "```") || len(s) < 6 {
		return s
	}
	s = strings.TrimSuffix(s, "```")
	if i := strings.IndexByte(s, '\n'); i >= 0 {
		return strings.TrimSpace(s[i+1:])
	}
	return strings.TrimSpace(strings.TrimPrefix(s, "```"))
}

// decodeOne decodes exactly one JSON value with nothing after it.
func decodeOne(s string, v any) error {
	dec := json.NewDecoder(strings.NewReader(s))
	dec.UseNumber()
	if err := dec.Decode(v); err != nil {
		return err
	}
	if _, err := dec.Token(); !errors.Is(err, io.EOF) {
		return errors.New("trailing data")
	}
	return nil
}

func parseEntry(raw json.RawMessage, add func(string, ...any)) (*int64, *int64) {
	if isNull(raw) {
		return nil, nil
	}
	var e map[string]json.RawMessage
	if decodeOne(string(raw), &e) != nil || len(e) != 2 || e["low"] == nil || e["high"] == nil {
		add(`entry must be {"low":<int>,"high":<int>} or null`)
		return nil, nil
	}
	low, high := parsePrice("entry.low", e["low"], add), parsePrice("entry.high", e["high"], add)
	if low == nil || high == nil {
		add("entry.low and entry.high must both be set")
		return nil, nil
	}
	return low, high
}

// parsePrice reads null or a positive integer IDR.
func parsePrice(name string, raw json.RawMessage, add func(string, ...any)) *int64 {
	if isNull(raw) {
		return nil
	}
	var n json.Number
	if bytes.HasPrefix(bytes.TrimSpace(raw), []byte(`"`)) || decodeOne(string(raw), &n) != nil {
		add("%s must be an integer IDR or null", name)
		return nil
	}
	v, err := n.Int64()
	if err != nil || v < MinPrice {
		add("%s must be a positive integer IDR, got %s", name, n)
		return nil
	}
	return &v
}

func parseReasons(raw json.RawMessage, ids map[string]bool, add func(string, ...any)) []Reason {
	var reasons []json.RawMessage
	if decodeOne(string(raw), &reasons) != nil {
		add("reasons must be an array")
		return nil
	}
	if len(reasons) < minReasons || len(reasons) > maxReasons {
		add("give %d-%d reasons, got %d", minReasons, maxReasons, len(reasons))
	}
	var out []Reason
	for i, r := range reasons {
		var rr Reason
		dec := json.NewDecoder(bytes.NewReader(r))
		dec.DisallowUnknownFields()
		if dec.Decode(&rr) != nil {
			add(`reasons[%d] must be {"text":"…","sources":["…"]}`, i)
			continue
		}
		if n := utf8.RuneCountInString(rr.Text); n == 0 || n > maxReasonRunes {
			add("reasons[%d].text must be 1-%d characters", i, maxReasonRunes)
		}
		if len(rr.Sources) == 0 {
			add("reasons[%d] must cite at least one DATA id", i)
		}
		for _, s := range rr.Sources {
			if !ids[s] {
				add("reasons[%d] cites %q, which is not in DATA", i, s)
			}
		}
		out = append(out, rr)
	}
	return out
}

func isNull(raw json.RawMessage) bool { return len(raw) == 0 || string(bytes.TrimSpace(raw)) == "null" }

// checkVerdict is the sanity check after the schema (TA0 §7d, P6): rejects are sent back in the repair
// turn; flags are kept with an accepted verdict.
func checkVerdict(v Verdict, ind Indicators) (rejects, flags []string) {
	rej := func(format string, a ...any) { rejects = append(rejects, fmt.Sprintf(format, a...)) }
	if ind.ARA == nil || ind.ARB == nil {
		flags = append(flags, FlagLimitsUnknown)
	}
	for _, p := range []struct {
		name string
		v    *int64
	}{{"entry.low", v.EntryLow}, {"entry.high", v.EntryHigh}, {"target", v.Target}, {"stop", v.Stop}} {
		if p.v == nil {
			continue
		}
		if t := TickSize(*p.v); *p.v%t != 0 {
			rej("%s %d is not a multiple of its tick %d", p.name, *p.v, t)
		}
		if ind.ARA != nil && *p.v > *ind.ARA {
			rej("%s %d is above ARA %d", p.name, *p.v, *ind.ARA)
		}
		if ind.ARB != nil && *p.v < *ind.ARB {
			rej("%s %d is below ARB %d", p.name, *p.v, *ind.ARB)
		}
	}
	if v.EntryLow != nil && *v.EntryLow > *v.EntryHigh {
		rej("entry.low %d is above entry.high %d", *v.EntryLow, *v.EntryHigh)
	}
	switch v.Verdict {
	case "buy_today", "add":
		if v.EntryLow == nil || v.Target == nil || v.Stop == nil {
			rej("%s needs entry, target and stop", v.Verdict)
		} else if !(*v.Stop < *v.EntryLow && *v.EntryHigh < *v.Target) {
			rej("%s needs stop < entry.low <= entry.high < target", v.Verdict)
		}
	case "hold":
		if v.Stop == nil || *v.Stop >= ind.Price {
			rej("hold needs a stop below the price %d", ind.Price)
		}
		if v.EntryLow != nil {
			rej("hold must have entry null")
		}
	case "sell", "avoid":
		if v.EntryLow != nil || v.Target != nil || v.Stop != nil {
			rej("%s must have entry, target and stop null", v.Verdict)
		}
	case "wait":
		if v.EntryLow != nil && v.Stop != nil && *v.Stop >= *v.EntryLow {
			rej("wait: stop must be below entry.low")
		}
		if v.EntryHigh != nil && v.Target != nil && *v.Target <= *v.EntryHigh {
			rej("wait: target must be above entry.high")
		}
	}
	if len(rejects) == 0 {
		flags = append(flags, verdictFlags(v, ind)...)
	}
	return rejects, flags
}

func verdictFlags(v Verdict, ind Indicators) []string {
	var flags []string
	if v.EntryLow != nil && v.Target != nil && v.Stop != nil && *v.EntryLow > *v.Stop {
		if rr := float64(*v.Target-*v.EntryHigh) / float64(*v.EntryLow-*v.Stop); rr < minRewardRisk {
			flags = append(flags, FlagLowRewardRisk)
		}
	}
	if v.EntryLow != nil && v.Stop != nil && ind.ATR14 != nil && float64(*v.EntryLow-*v.Stop) > maxStopATR**ind.ATR14 {
		flags = append(flags, FlagWideStop)
	}
	if v.EntryLow != nil && ind.Price > 0 {
		p := float64(ind.Price)
		if float64(*v.EntryLow) > p*(1+maxEntryDistPct/100) || float64(*v.EntryHigh) < p*(1-maxEntryDistPct/100) {
			flags = append(flags, FlagEntryFar)
		}
	}
	if r1 := ind.Levels.R1; v.Target != nil && r1 != nil && *v.Target > *r1+TickSize(*r1) {
		flags = append(flags, FlagTargetAboveR1)
	}
	if ind.MarketState == StateClosed {
		flags = append(flags, FlagMarketClosed)
	}
	return flags
}

// newsForCode picks up to 8 articles matching code: strong matches first, newest first within each.
func newsForCode(articles []Article, cands []Candidate, code string) []NewsRef {
	var strong, weak []NewsRef
	for _, a := range articles {
		m := MatchArticle(a.Title, a.Summary, cands)
		if !slices.Contains(m.Codes, code) {
			continue
		}
		ref := NewsRef{Source: a.Source, Title: a.Title, Link: a.Link, PublishedAt: a.Published.In(WIB).Format(time.RFC3339), Summary: a.Summary}
		if m.Strength == MatchStrong {
			strong = append(strong, ref)
		} else {
			weak = append(weak, ref)
		}
	}
	out := append(strong, weak...)
	if len(out) > maxNewsInPrompt {
		out = out[:maxNewsInPrompt]
	}
	for i := range out {
		out[i].ID = fmt.Sprintf("news:%d", i+1)
	}
	return out
}
