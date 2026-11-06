package stocks

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/justcallmesu/aristotle/server/internal/hermes"
)

// TBA1: the TSC2 review minors, holding stop/target, alerts, the automatic runs and the 08:30 brief. Fakes only:
// fakeProvider for Yahoo, fakeAnalyst for Hermes, a fake clock, a temp state dir.

type tbaHarness struct {
	*screenHarness
	fa      *fakeAnalyst
	feedMu  sync.Mutex
	feed    string // the cnbc feed body (the other feeds answer an empty channel)
	retry   chan time.Time
	lb      *lockedBuf
	fetches atomic.Int32 // feed fetches (news polls)
}

const emptyRSS = `<?xml version="1.0"?><rss version="2.0"><channel></channel></rss>`

func feedItem(title, link string, pub time.Time) string {
	return fmt.Sprintf(`<item><title>%s</title><link>%s</link><pubDate>%s</pubDate><description>ringkasan</description></item>`,
		title, link, pub.Format(time.RFC1123Z))
}

func newTBA(t *testing.T, now string, fa *fakeAnalyst) *tbaHarness {
	t.Helper()
	h := &tbaHarness{screenHarness: &screenHarness{harness: &harness{t: t, prov: newFakeProvider(), clock: newClock(now), dir: t.TempDir(),
		logs: &bytes.Buffer{}}}, fa: fa, retry: make(chan time.Time, 4), feed: emptyRSS}
	h.lb = &lockedBuf{b: h.logs}
	h.openTBA()
	h.expect("PUT", "/v1/stocks/universe", `{"lq45":[],"idx80":["TLKM","BBCA"],"valid_to":"2027-01-29"}`, 200, "")
	h.expect("PUT", "/v1/stocks/watchlist/ANTM", "", 200, "")
	return h
}

func (h *tbaHarness) openTBA() {
	var a Analyst
	if h.fa != nil {
		a = h.fa
	}
	h.svc = New(Options{StateDir: h.dir, Provider: h.prov, Now: h.clock.Now, Analyst: a,
		FetchFeed: func(_ context.Context, u string) ([]byte, error) {
			h.fetches.Add(1)
			h.feedMu.Lock()
			defer h.feedMu.Unlock()
			if u == DefaultFeeds[0].URL {
				return []byte(h.feed), nil
			}
			return []byte(emptyRSS), nil
		},
		Log: slog.New(slog.NewTextHandler(h.lb, nil)), NewsAfter: func(time.Duration) <-chan time.Time { return nil },
		SweepAfter: func(time.Duration) <-chan time.Time { return nil }, BriefRetryAfter: func(time.Duration) <-chan time.Time { return h.retry },
		BarsPace: func(ctx context.Context) error { return ctx.Err() }})
	h.t.Cleanup(h.svc.Close)
}

// lockedBuf lets the job goroutines and the test read the log without a race.
type lockedBuf struct {
	mu sync.Mutex
	b  *bytes.Buffer
}

func (l *lockedBuf) Write(p []byte) (int, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.b.Write(p)
}

func (h *tbaHarness) logText() string {
	h.lb.mu.Lock()
	defer h.lb.mu.Unlock()
	return h.logs.String()
}

func (f *fakeAnalyst) sessionOpts() []SessionOptions {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]SessionOptions(nil), f.opts...)
}

func (h *tbaHarness) setFeed(items ...string) {
	h.feedMu.Lock()
	defer h.feedMu.Unlock()
	h.feed = `<?xml version="1.0"?><rss version="2.0"><channel>` + strings.Join(items, "") + `</channel></rss>`
}

func (h *tbaHarness) alerts() []Alert {
	h.svc.alerts.mu.Lock()
	defer h.svc.alerts.mu.Unlock()
	return append([]Alert(nil), h.svc.alerts.doc.Items...)
}

func alertKeys(items []Alert) string {
	var out []string
	for _, a := range items {
		code := ""
		if a.Code != nil {
			code = *a.Code
		}
		k := a.Kind + ":" + code
		if lv, ok := a.Detail["level"].(string); ok {
			k += ":" + lv
		}
		if a.Silent {
			k += "(silent)"
		}
		out = append(out, k)
	}
	return strings.Join(out, " ")
}

// antmReply is a valid buy_today for ANTM at 3150 (data time 09:45).
func antmReply() string {
	return reply(map[string]any{"code": "ANTM", "data_as_of": "2026-11-05T09:45:00+07:00", "entry": map[string]any{"low": 3140, "high": 3150},
		"target": 3250, "stop": 3080, "reasons": []map[string]any{{"text": "Volume", "sources": []string{"ind:price"}},
			{"text": "Trend", "sources": []string{"ind:trend"}}, {"text": "RSI", "sources": []string{"ind:rsi14"}}}})
}

// --- TSC2 review minors ---

func TestScreenerSettingsRefuseNull(t *testing.T) {
	h := newHarness(t)
	for _, body := range []string{
		strings.Replace(mustSettings(t), `"min_value_bn":5`, `"min_value_bn":null`, 1),
		strings.Replace(mustSettings(t), `"breakout":{"on":true`, `"breakout":{"on":null`, 1),
		strings.Replace(mustSettings(t), `"ma_cross":{"on":true}`, `"ma_cross":null`, 1),
	} {
		h.expect("PUT", "/v1/stocks/screener/settings", body, 400, "bad_request")
	}
	h.expect("PUT", "/v1/stocks/screener/settings", mustSettings(t), 200, "")
}

func mustSettings(t *testing.T) string {
	b, err := json.Marshal(DefaultScreenerSettings())
	if err != nil {
		t.Fatal(err)
	}
	return string(b)
}

// MINOR-1: a run that read its settings before a PUT computes again with the PUT's settings before it installs.
func TestScreenerStaleSettingsRecomputed(t *testing.T) {
	h := newScreenHarness(t, "2026-11-05T16:30:00+07:00")
	set0, rev0 := h.svc.screen.settingsRev()
	off := DefaultScreenerSettings()
	off.Setups.Breakout.On = false
	b, _ := json.Marshal(off)
	h.expect("PUT", "/v1/stocks/screener/settings", string(b), 200, "")
	var seen []bool
	installed := h.svc.screen.installComputed(set0, rev0, func(set ScreenerSettings) screenResult {
		seen = append(seen, set.Setups.Breakout.On)
		return screenResult{Mode: ModeEOD, SessionDate: "2026-11-05", ComputedAt: "x", Candidates: []ScreenCandidate{}, Flags: []ScreenFlag{}}
	}, nil, HitEOD, nil)
	if !installed || fmt.Sprint(seen) != "[true false]" {
		t.Fatalf("installed %v, computed with breakout on = %v (want the stale run recomputed with the PUT's settings)", installed, seen)
	}
	// A re-rank whose base was replaced meanwhile is dropped.
	if h.svc.screen.installComputed(off, rev0+1, func(ScreenerSettings) screenResult { return screenResult{} }, nil, HitRerank,
		func() bool { return false }) {
		t.Fatal("a re-rank of a replaced result was installed")
	}
}

// MINOR-5: a quote with no trade today (older market time, older previousClose) keeps the stored signals.
func TestNoTradeTodayKeepsStoredSignals(t *testing.T) {
	bars := series("2026-11-04", append(flat(60, 1000), 1050), 10, 10_000_000)
	q := &liveQuote{Price: 1000, PrevClose: i64(990), MarketTime: "2026-11-04T15:50:00+07:00", Usable: false}
	ev := evalIntraday(wib("2026-11-05 00:00"), bars, q, DefaultScreenerSettings())
	if ev.Mismatch || !ev.Stale || sigIDs(ev.Signals) != "ma_cross:up" {
		t.Fatalf("eval = %+v", ev)
	}
	q.Usable = true // traded today with a previousClose 1 session off: a mismatch
	if ev := evalIntraday(wib("2026-11-05 00:00"), bars, q, DefaultScreenerSettings()); !ev.Mismatch {
		t.Fatalf("usable mismatch = %+v", ev)
	}
}

// MINOR-3 and MINOR-4: hits carry their origin; a watched illiquid code's bullish setups are logged; an open with no
// bar for today is retried by the next scan, and opens never enter the phone's chart cache.
func TestScreenerHitsOriginWatchedAndOpenRetry(t *testing.T) {
	h := newScreenHarness(t, "2026-11-05T09:00:00+07:00")
	noSpike := DefaultScreenerSettings()
	noSpike.Setups.VolumeSpike.On = false
	b, _ := json.Marshal(noSpike)
	h.expect("PUT", "/v1/stocks/screener/settings", string(b), 200, "")
	h.storeBars("BBCA", 4930, 10_000_000)
	h.storeBars("TLKM", 3000, 10_000_000)
	h.storeBars("ANTM", 3100, 100_000)
	h.setQuote("BBCA", 4980, 4930, 3_000_000, "2026-11-05T09:45:00+07:00")
	h.setQuote("TLKM", 3000, 3000, 1_000_000, "2026-11-05T09:45:00+07:00")
	h.setQuote("ANTM", 3150, 3100, 100_000, "2026-11-05T09:45:00+07:00") // vr 7.3, above the 20-day high: illiquid but watched
	// BBCA's 5d chart has no bar for today yet at the first scan.
	h.prov.mu.Lock()
	h.prov.rangeCharts = map[string]Chart{"BBCA|" + openRange: {Code: "BBCA", Bars: []Bar{{T: wib("2026-11-04 09:00").Unix(), O: 4930, H: 4930, L: 4930, C: 4930, V: 1}}}}
	h.prov.mu.Unlock()
	h.openChart("TLKM", 3000, 3000)
	h.openChart("ANTM", 3100, 3100)
	h.tickAt("2026-11-05T09:55:10+07:00")
	doc := h.screenerDoc()
	var antm []string
	for _, x := range doc.Hits {
		if x.Origin != HitScan {
			t.Fatalf("hit origin = %+v", x)
		}
		if x.Code == "ANTM" {
			antm = append(antm, x.Setup)
			if !x.Watch || x.Score == 0 {
				t.Fatalf("ANTM hit = %+v", x)
			}
		}
	}
	if strings.Join(antm, ",") != "breakout" {
		t.Fatalf("ANTM hits = %v (illiquid watched codes must reach the hits log)", antm)
	}
	if got := h.expect("GET", "/v1/stocks/screener", "", 200, ""); strings.Contains(candCodes(got, "candidates"), "ANTM") || strings.Contains(candCodes(got, "candidates"), "gap") {
		t.Fatalf("candidates = %s", candCodes(got, "candidates"))
	}
	h.openChart("BBCA", 4930, 5050)
	_, c0, _ := h.prov.calls()
	h.tickAt("2026-11-05T10:05:10+07:00")
	if _, c, _ := h.prov.calls(); c != c0+1 {
		t.Fatalf("chart calls %d → %d: only BBCA's open is asked again", c0, c)
	}
	if got := h.expect("GET", "/v1/stocks/screener", "", 200, ""); !strings.Contains(candCodes(got, "candidates"), "gap:up") {
		t.Fatalf("no gap after the retry: %s", candCodes(got, "candidates"))
	}
	h.svc.market.mu.Lock()
	for k := range h.svc.market.charts {
		if strings.HasSuffix(k, "|"+openRange) {
			t.Errorf("open chart %s in the phone's chart cache", k)
		}
	}
	h.svc.market.mu.Unlock()
	// A settings change re-ranks: the spikes it shows first are logged with origin rerank (never alerted).
	seq := h.screenerDoc().Seq
	b, _ = json.Marshal(DefaultScreenerSettings())
	h.expect("PUT", "/v1/stocks/screener/settings", string(b), 200, "")
	var rr []string
	for _, x := range h.screenerDoc().Hits {
		if x.Seq > seq {
			rr = append(rr, x.Code+":"+x.Setup+":"+x.Origin)
		}
	}
	if strings.Join(rr, " ") != "BBCA:volume_spike:rerank ANTM:volume_spike:rerank" {
		t.Fatalf("re-rank hits = %v", rr)
	}
}

// --- holdings stop/target (Q104) ---

func TestHoldingLevels(t *testing.T) {
	h := newHarness(t)
	for _, body := range []string{
		`{"lots":1,"avg_price":4800,"stop":4805}`,                 // above avg
		`{"lots":1,"avg_price":4800,"stop":4701}`,                 // off tick (10 above 2000)
		`{"lots":1,"avg_price":4800,"stop":4700,"target":4700}`,   // stop ≥ target
		`{"lots":1,"avg_price":4800,"target":0}`,                  // not positive
		`{"lots":1,"avg_price":4800,"target":5002}`,               // off tick (25 above 5000)
		`{"lots":1,"avg_price":4800,"stop":"4700"}`,               // not a number
		`{"lots":1,"avg_price":4800,"stop":4700,"target":5100.5}`, // not an integer
	} {
		h.expect("PUT", "/v1/stocks/holdings/BBCA", body, 400, "bad_request")
	}
	got := h.expect("PUT", "/v1/stocks/holdings/BBCA", `{"lots":1,"avg_price":4800,"stop":4700,"target":5100}`, 200, "")
	row := got["holdings"].([]any)[0].(map[string]any)
	if row["stop"] != 4700.0 || row["target"] != 5100.0 {
		t.Fatalf("row = %v", row)
	}
	st, err := os.ReadFile(filepath.Join(h.dir, stateFile))
	if err != nil || strings.Contains(string(st), "stop") || !strings.Contains(string(st), `"version": 1`) {
		t.Fatalf("stocks.json = %s (must stay version 1 without the new fields)", st)
	}
	if _, err := loadState(h.dir); err != nil {
		t.Fatal(err)
	}
	lv, err := os.ReadFile(filepath.Join(h.dir, levelsFile))
	if err != nil || !strings.Contains(string(lv), `"stop": 4700`) {
		t.Fatalf("levels file = %s, %v", lv, err)
	}
	// Restart keeps them; a PUT without them clears them; a DELETE drops them.
	h.svc.Close()
	h.start()
	if row := h.expect("GET", "/v1/stocks/holdings", "", 200, "")["holdings"].([]any)[0].(map[string]any); row["target"] != 5100.0 {
		t.Fatalf("after restart = %v", row)
	}
	if row := h.expect("PUT", "/v1/stocks/holdings/BBCA", `{"lots":2,"avg_price":4800,"stop":null}`, 200, "")["holdings"].([]any)[0].(map[string]any); row["stop"] != nil || row["target"] != nil {
		t.Fatalf("cleared = %v", row)
	}
	h.expect("PUT", "/v1/stocks/holdings/BBCA", `{"lots":2,"avg_price":4800,"stop":4500}`, 200, "")
	h.expect("DELETE", "/v1/stocks/holdings/BBCA", "", 200, "")
	h.expect("PUT", "/v1/stocks/holdings/BBCA", `{"lots":2,"avg_price":4800}`, 200, "")
	if len(h.svc.holdingLevels()) != 0 {
		t.Fatalf("levels after delete = %v", h.svc.holdingLevels())
	}
}

// --- alerts and automatic runs ---

func TestAlertsLevelsSetupsAutoRun(t *testing.T) {
	fa := &fakeAnalyst{replies: []any{antmReply()}}
	h := newTBA(t, "2026-11-05T09:00:00+07:00", fa)
	h.storeBars("BBCA", 4930, 10_000_000)
	h.storeBars("TLKM", 3000, 10_000_000)
	h.storeBars("ANTM", 3100, 100_000)
	h.expect("PUT", "/v1/stocks/holdings/BBCA", `{"lots":1,"avg_price":5000,"stop":4900,"target":4980}`, 200, "")
	h.expect("PUT", "/v1/stocks/watchlist/TLKM", "", 200, "")
	// A kept TLKM verdict for today, made last evening: the day's low counts for its stop.
	h.svc.analyzer.keep("TLKM", AnalysisResult{Code: "TLKM", SessionDate: "2026-11-05", AnalyzedAt: "2026-11-04T20:00:00+07:00", Verdict: "buy_today",
		Confidence: "med", EntryLow: i64(2990), EntryHigh: i64(3000), Target: i64(3100), Stop: i64(2950), Trigger: TriggerManual})
	h.setQuote("BBCA", 4980, 4930, 300_000, "2026-11-05T09:45:00+07:00") // quiet volume: no setup on the held BBCA
	h.setQuote("TLKM", 3000, 3000, 1_000_000, "2026-11-05T09:45:00+07:00")
	h.prov.mu.Lock()
	q := h.prov.quotes["TLKM"]
	q.Low, q.High = i64(2940), i64(3010)
	h.prov.quotes["TLKM"] = q
	h.prov.mu.Unlock()
	h.setQuote("ANTM", 3150, 3100, 100_000, "2026-11-05T09:45:00+07:00")
	h.openChart("BBCA", 4930, 4930)
	h.openChart("TLKM", 3000, 3000)
	h.prov.mu.Lock() // ANTM's daily chart has no bar for today yet: its open (and gap) comes with the next scan
	h.prov.rangeCharts["ANTM|"+openRange] = Chart{Code: "ANTM", Bars: []Bar{{T: wib("2026-11-04 09:00").Unix(), O: 3100, H: 3100, L: 3100, C: 3100, V: 1}}}
	h.prov.mu.Unlock()

	// The scan body directly (no tick): the alerts job's own 5-min slot would drain the same hits concurrently.
	scan := func(now string) {
		h.setNow(now)
		if err := h.svc.runScanJob(context.Background(), JobRun{}); err != nil {
			t.Fatal(err)
		}
	}
	scan("2026-11-05T09:55:10+07:00")
	waitFor(t, func() bool { return strings.Contains(alertKeys(h.alerts()), "analysis:ANTM") })
	// BBCA (held, target 4980) and TLKM (verdict: entry zone, stop via the day low); ANTM (watched, 2 setups) →
	// a setup alert, an automatic run on Sonnet, its analysis alert.
	if got := alertKeys(h.alerts()); got != "level:BBCA:target level:TLKM:entry level:TLKM:stop setup:ANTM analysis:ANTM" {
		t.Fatalf("alerts = %s", got)
	}
	items := h.alerts()
	if items[0].Silent || items[0].DataAsOf == nil || *items[0].DataAsOf != "2026-11-05T09:45:00+07:00" || items[0].Detail["source"] != "holding" {
		t.Fatalf("level = %+v", items[0])
	}
	if items[3].Text != "Breakout, Lonjakan volume" || !strings.HasPrefix(items[4].Title, "ANTM: Beli hari ini, sedang") {
		t.Fatalf("texts = %q / %q (Indonesian until the phone sends a language)", items[3].Text, items[4].Title)
	}
	if o := fa.sessionOpts(); len(o) != 1 || o[0].Model != AutoModel {
		t.Fatalf("session options = %+v (auto runs pin Sonnet)", o)
	}
	res := h.expect("GET", "/v1/stocks/analysis/ANTM", "", 200, "")["result"].(map[string]any)
	if res["trigger"] != TriggerAuto || res["model"] != AutoModel {
		t.Fatalf("auto result = %v", res)
	}
	if ar := h.expect("GET", "/v1/stocks/alerts/status", "", 200, "")["auto_runs"].(map[string]any); ar["used"] != 1.0 || fmt.Sprint(ar["codes"]) != "[ANTM]" {
		t.Fatalf("auto_runs = %v", ar)
	}
	// The next scan: nothing repeats (dedupe per day), and ANTM is never run twice in a day.
	h.setQuote("ANTM", 3200, 3100, 200_000, "2026-11-05T09:55:00+07:00")
	h.openChart("ANTM", 3100, 3200) // gap up now too: a new hit, still no second run
	scan("2026-11-05T10:05:10+07:00")
	if got := alertKeys(h.alerts()); got != "level:BBCA:target level:TLKM:entry level:TLKM:stop setup:ANTM analysis:ANTM" {
		t.Fatalf("alerts after the second scan = %s", got)
	}
	if _, _, _, opened := fa.snapshot(); opened != 1 {
		t.Fatalf("opened %d sessions", opened)
	}
	if !strings.Contains(h.logText(), "reason=code_done") {
		t.Fatalf("no skip log: %s", h.logText())
	}
	for _, secret := range []string{"Beli hari ini", "Uptrend", "4980"} {
		if strings.Contains(h.logText(), secret) {
			t.Fatalf("log carries %q", secret)
		}
	}
	// Restart: the ring, the cursor and the counter survive.
	h.svc.Close()
	h.openTBA()
	if got := h.expect("GET", "/v1/stocks/alerts", "", 200, ""); len(got["items"].([]any)) != 5 || got["auto_runs"].(map[string]any)["used"] != 1.0 {
		t.Fatalf("after restart = %v", got)
	}
}

// The end-of-day screen at the end of bars.daily: a held code's bullish eod setups alert at once (16:20 is inside
// 08:00-16:30); with 3 setups it also starts an automatic run (the verdict is for the next session).
func TestAlertsFromEODScreen(t *testing.T) {
	fa := &fakeAnalyst{replies: []any{hermes.ErrConnLost}}
	h := newTBA(t, "2026-11-05T16:00:00+07:00", fa)
	h.expect("PUT", "/v1/stocks/holdings/BBCA", `{"lots":1,"avg_price":4800}`, 200, "")
	bars := series("2026-11-05", append(flat(60, 1000), 1050), 10, 10_000_000)
	bars[60].H, bars[60].V = 1060, 20_000_000
	h.prov.mu.Lock()
	h.prov.charts["BBCA"] = Chart{Code: "BBCA", Name: "Bank Central Asia Tbk", Bars: bars}
	h.prov.charts["TLKM"] = Chart{Code: "TLKM", Bars: series("2026-11-05", flat(61, 3000), 10, 10_000_000)}
	h.prov.charts["ANTM"] = Chart{Code: "ANTM", Bars: series("2026-11-05", flat(61, 3000), 10, 10_000_000)}
	h.prov.mu.Unlock()
	h.tickAt("2026-11-05T16:20:05+07:00")
	waitFor(t, func() bool { return strings.Contains(h.logText(), "automatic analysis failed") })
	items := h.alerts()
	if alertKeys(items) != "setup:BBCA" || items[0].Text != "Breakout, Lonjakan volume, MA20↑50" || *items[0].DataAsOf != "2026-11-05T16:00:00+07:00" {
		t.Fatalf("alerts = %s %+v", alertKeys(items), items)
	}
	// The run failed in Hermes (no analysis alert), but it started: it counts.
	if v := h.svc.alerts.autoRunsView(); v.Used != 1 || fmt.Sprint(v.Codes) != "[BBCA]" {
		t.Fatalf("auto_runs = %+v", v)
	}
}

func TestAutoRunCapRefusalAndFreshVerdict(t *testing.T) {
	fa := &fakeAnalyst{replies: []any{antmReply()}}
	h := newTBA(t, "2026-11-05T10:00:00+07:00", fa)
	ae := h.svc.alerts
	now := h.clock.Now()
	ae.mu.Lock()
	ae.doc.AutoRuns = autoRuns{Date: "2026-11-05", Used: AutoRunCap, Codes: []string{"AAAA", "BBBB", "CCCC", "DDDD", "EEEE"}}
	ae.mu.Unlock()
	ae.proc.Lock()
	ae.maybeAutoRun(now, "ANTM")
	ae.proc.Unlock()
	if _, _, _, opened := fa.snapshot(); opened != 0 || !strings.Contains(h.logText(), "reason=cap") {
		t.Fatalf("cap: opened %d", opened)
	}
	// A new day resets the counter; Hermes down is a refusal that does not count.
	ae.mu.Lock()
	ae.doc.AutoRuns.Date = "2026-11-04"
	ae.mu.Unlock()
	fa.mu.Lock()
	fa.checkErr = hermes.ErrHermesNotRunning
	fa.mu.Unlock()
	ae.proc.Lock()
	ae.maybeAutoRun(now, "ANTM")
	ae.proc.Unlock()
	if v := ae.autoRunsView(); v.Used != 0 || !strings.Contains(h.logText(), "error=hermes_unavailable") {
		t.Fatalf("refused run counted: %+v", v)
	}
	// A verdict for today younger than 60 min: no run.
	fa.mu.Lock()
	fa.checkErr = nil
	fa.mu.Unlock()
	h.svc.analyzer.keep("ANTM", AnalysisResult{Code: "ANTM", SessionDate: "2026-11-05", AnalyzedAt: "2026-11-05T09:30:00+07:00", Verdict: "wait"})
	ae.proc.Lock()
	ae.maybeAutoRun(now, "ANTM")
	ae.proc.Unlock()
	if v := ae.autoRunsView(); v.Used != 0 || !strings.Contains(h.logText(), "reason=fresh_verdict") {
		t.Fatalf("fresh verdict: %+v", v)
	}
}

func TestAlertsQuietHoursCapsAndJobOff(t *testing.T) {
	h := newTBA(t, "2026-11-05T10:00:00+07:00", nil)
	ae := h.svc.alerts
	d := func(code, subject string) alertDraft {
		return alertDraft{kind: AlertNews, code: code, subject: subject, title: code}
	}
	for i := range 4 {
		ae.emit(h.clock.Now(), d("BBCA", fmt.Sprint(i)))
	}
	if !ae.emit(h.clock.Now(), d("TLKM", "x")) || ae.emit(h.clock.Now(), d("TLKM", "x")) {
		t.Fatal("dedupe")
	}
	if got := alertKeys(h.alerts()); got != "news:BBCA news:BBCA news:BBCA news:BBCA(silent) news:TLKM" {
		t.Fatalf("per-code cap = %s", got)
	}
	for i := range maxAlertsPerDay {
		ae.emit(h.clock.Now(), d(fmt.Sprintf("C%03d", i), ""))
	}
	n, summaries := 0, 0
	for _, a := range h.alerts() {
		if !a.Silent && a.Kind != AlertSummary {
			n++
		}
		if a.Kind == AlertSummary {
			summaries++
		}
	}
	if n != maxAlertsPerDay || summaries != 1 {
		t.Fatalf("day cap: %d notified, %d summaries", n, summaries)
	}
	// 17:00: silent, except the brief.
	h.setNow("2026-11-05T17:00:00+07:00")
	ae.emit(h.clock.Now(), d("ANTM", "late"))
	ae.emit(h.clock.Now(), alertDraft{kind: AlertBrief, subject: "2026-11-05", title: "Brief", loud: true})
	items := h.alerts()
	if a, b := items[len(items)-2], items[len(items)-1]; !a.Silent || b.Silent {
		t.Fatalf("quiet hours: %+v %+v", a, b)
	}
	// The alerts job off: no alert at all.
	h.expect("PUT", "/v1/stocks/jobs/alerts", `{"on":false}`, 200, "")
	h.setNow("2026-11-05T10:00:00+07:00")
	before := len(h.alerts())
	h.svc.alerts.afterScan(h.clock.Now(), map[string]Quote{})
	h.svc.alerts.afterNews(h.clock.Now())
	if len(h.alerts()) != before || h.expect("GET", "/v1/stocks/alerts/status", "", 200, "")["alerts_on"] != false {
		t.Fatal("alerts while off")
	}
}

func TestAlertsLongPoll(t *testing.T) {
	h := newTBA(t, "2026-11-05T10:00:00+07:00", nil)
	ae := h.svc.alerts
	ae.emit(h.clock.Now(), alertDraft{kind: AlertNews, code: "BBCA", subject: "1", title: "BBCA"})
	got := h.expect("GET", "/v1/stocks/alerts", "", 200, "")
	if got["seq"] != 1.0 || len(got["items"].([]any)) != 1 || got["reset"] != false {
		t.Fatalf("list = %v", got)
	}
	for _, q := range []string{"?after=-1", "?wait=51", "?after=x", "?lang=fr"} {
		h.expect("GET", "/v1/stocks/alerts"+q, "", 400, "bad_request")
	}
	if got := h.expect("GET", "/v1/stocks/alerts?after=9", "", 200, ""); got["reset"] != true || len(got["items"].([]any)) != 1 {
		t.Fatalf("cursor ahead = %v", got)
	}
	// A held request answers when the next alert lands.
	done := make(chan map[string]any)
	go func() {
		_, body, _ := h.do("GET", "/v1/stocks/alerts?after=1&wait=5&lang=en", "")
		done <- body
	}()
	waitFor(t, func() bool { return ae.lang() == analysisLangEN })
	ae.emit(h.clock.Now(), alertDraft{kind: AlertNews, code: "TLKM", subject: "2", title: "TLKM"})
	select {
	case body := <-done:
		items := body["items"].([]any)
		if len(items) != 1 || items[0].(map[string]any)["seq"] != 2.0 || items[0].(map[string]any)["code"] != "TLKM" {
			t.Fatalf("long-poll = %v", body)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("long-poll did not wake")
	}
	// Nothing new: the wait ends empty.
	if got := h.expect("GET", "/v1/stocks/alerts?after=2&wait=1", "", 200, ""); len(got["items"].([]any)) != 0 {
		t.Fatalf("empty wait = %v", got)
	}
}

func TestNewsAlerts(t *testing.T) {
	h := newTBA(t, "2026-11-05T09:58:00+07:00", nil)
	h.expect("PUT", "/v1/stocks/holdings/BBCA", `{"lots":1,"avg_price":4800}`, 200, "")
	h.setFeed(
		feedItem("Laba saham BBCA melonjak", "https://x.test/1", wib("2026-11-05 09:50")),    // strong, fresh
		feedItem("BBCA dan bank lain", "https://x.test/2", wib("2026-11-05 09:51")),          // weak: never pushes
		feedItem("Emiten (ANTM) rilis kinerja", "https://x.test/3", wib("2026-11-05 07:30")), // before 08:00: waits for the brief
		feedItem("Saham BBCA kemarin", "https://x.test/4", wib("2026-11-05 08:10")),          // older than an hour
		feedItem("Saham TLKM naik", "https://x.test/5", wib("2026-11-05 09:55")),             // not watched or held
	)
	h.tickAt("2026-11-05T10:00:10+07:00")
	if got := alertKeys(h.alerts()); got != "news:BBCA" {
		t.Fatalf("news alerts = %s", got)
	}
	a := h.alerts()[0]
	if a.Text != "Laba saham BBCA melonjak" || a.Detail["link"] != "https://x.test/1" || *a.DataAsOf != "2026-11-05T09:50:00+07:00" {
		t.Fatalf("news alert = %+v", a)
	}
	if h.svc.news.Polling() {
		t.Fatal("the news job must not keep the on-demand poller alive")
	}
	// Evening: news waits for the brief.
	h.setFeed(feedItem("Saham BBCA malam", "https://x.test/6", wib("2026-11-05 19:00")))
	h.setNow("2026-11-05T19:05:00+07:00")
	h.svc.news.Poll(context.Background())
	h.svc.alerts.afterNews(h.clock.Now())
	if len(h.alerts()) != 1 {
		t.Fatalf("evening news alerted: %s", alertKeys(h.alerts()))
	}
}

// --- the 08:30 brief ---

func briefReply(fields map[string]any) string {
	m := map[string]any{"schema": briefSchema, "session_date": "2026-11-05", "headline": "Pasar menunggu, ANTM menarik",
		"summary": "ANTM di atas MA20 dengan RSI 55; BBCA datar.", "focus": []map[string]any{{"code": "ANTM", "stance": "watch_buy",
			"note": "Dekat level lvl:ANTM:pivot", "sources": []string{"lvl:ANTM:pivot", "ind:ANTM:rsi14"}}},
		"news":  []map[string]any{{"text": "Kinerja ANTM naik 12,5 %", "sources": []string{"news:1"}}},
		"risks": []map[string]any{{"text": "Saham BBCA turun 2,4 % kemarin", "sources": []string{"ind:BBCA:change_pct"}}}}
	for k, v := range fields {
		m[k] = v
	}
	b, _ := json.Marshal(m)
	return string(b)
}

func (h *tbaHarness) briefJob() JobView {
	for _, v := range h.svc.sched.views() {
		if v.ID == BriefJobID {
			return v
		}
	}
	h.t.Fatal("no brief job")
	return JobView{}
}

func newBriefHarness(t *testing.T, now string, fa *fakeAnalyst) *tbaHarness {
	h := newTBA(t, now, fa)
	h.storeBars("ANTM", 3100, 100_000)
	h.storeBars("BBCA", 4930, 10_000_000)
	h.storeBars("TLKM", 3000, 10_000_000) // every quoted code fresh: no bars.daily catch-up beside the brief
	h.expect("PUT", "/v1/stocks/holdings/BBCA", `{"lots":3,"avg_price":4777.5}`, 200, "")
	h.setFeed(feedItem("Emiten (ANTM) rilis kinerja", "https://x.test/1", wib("2026-11-05 07:30")),
		feedItem("Saham (ANTM) lama", "https://x.test/0", wib("2026-11-04 15:00"))) // before the last close: not in the brief
	return h
}

func TestBriefAt0830(t *testing.T) {
	bad := briefReply(map[string]any{"headline": "ANTM ke 3.250 hari ini"})
	fa := &fakeAnalyst{replies: []any{bad, briefReply(nil)}}
	h := newBriefHarness(t, "2026-11-05T08:00:00+07:00", fa)
	if got := h.expect("GET", "/v1/stocks/brief", "", 200, ""); got["state"] != BriefPending || got["date"] != "2026-11-05" {
		t.Fatalf("before = %v", got)
	}
	h.tickAt("2026-11-05T08:30:05+07:00")
	if v := h.briefJob(); v.LastStatus == nil || *v.LastStatus != JobStatusOK || v.Late {
		t.Fatalf("job = %+v", v)
	}
	got := h.expect("GET", "/v1/stocks/brief", "", 200, "")
	res := got["result"].(map[string]any)
	refs := got["refs"].(map[string]any)
	if got["state"] != BriefDone || got["repaired"] != true || got["lang"] != "id" || res["headline"] != "Pasar menunggu, ANTM menarik" ||
		refs["ind:ANTM:close"] != 3100.0 || refs["hold:BBCA:pl_pct"] == nil || len(got["news"].([]any)) != 1 {
		t.Fatalf("brief = %v", got)
	}
	prompts, deleted, _, _ := fa.snapshot()
	if len(prompts) != 2 || !strings.Contains(prompts[1], "holds a price or number above 99 (3.250)") || len(deleted) != 1 {
		t.Fatalf("prompts = %q, deleted %v", prompts, deleted)
	}
	p := prompts[0]
	if !strings.Contains(p, `"lvl:ANTM:pivot"`) || !strings.Contains(p, "Bahasa Indonesia") || strings.Contains(p, "4777") ||
		strings.Contains(p, "lama") || !strings.Contains(p, "untrusted") {
		t.Fatalf("prompt = %s", p)
	}
	if o := fa.sessionOpts(); len(o) != 1 || o[0].Model != "" || o[0].RunBudget != briefRunBudget {
		t.Fatalf("session options = %+v (the brief runs on the profile default: Opus)", o)
	}
	if got := alertKeys(h.alerts()); got != "brief:" {
		t.Fatalf("alerts = %s", got)
	}
	if strings.Contains(h.logText(), "Pasar menunggu") {
		t.Fatal("the brief text is in the log")
	}
	list := h.expect("GET", "/v1/stocks/briefs", "", 200, "")["briefs"].([]any)
	if len(list) != 1 || list[0].(map[string]any)["headline"] != "Pasar menunggu, ANTM menarik" {
		t.Fatalf("briefs = %v", list)
	}
	h.expect("GET", "/v1/stocks/brief?date=2026-11-04", "", 404, "no_brief")
	h.expect("GET", "/v1/stocks/brief?date=11-04", "", 400, "bad_request")
	// On demand: a rerun (409 while it runs), then Saturday: no brief.
	fa.mu.Lock()
	fa.replies = []any{briefReply(map[string]any{"headline": "Rerun"})}
	fa.gate = make(chan struct{})
	fa.mu.Unlock()
	h.expect("POST", "/v1/stocks/brief", `{"lang":"en"}`, 202, "")
	h.expect("POST", "/v1/stocks/brief", "", 409, "brief_running")
	close(fa.gate)
	waitFor(t, func() bool {
		return h.expect("GET", "/v1/stocks/brief", "", 200, "")["state"] == BriefDone
	})
	if got := h.expect("GET", "/v1/stocks/brief", "", 200, ""); got["trigger"] != "manual" || got["lang"] != "en" {
		t.Fatalf("rerun = %v", got)
	}
	h.setNow("2026-11-07T08:30:00+07:00")
	h.expect("POST", "/v1/stocks/brief", "", 422, "not_trading_day")
}

func TestBriefRetriesWhileHermesDownUntil0900(t *testing.T) {
	fa := &fakeAnalyst{replies: []any{briefReply(nil)}, checkErr: hermes.ErrHermesNotRunning}
	h := newBriefHarness(t, "2026-11-05T08:00:00+07:00", fa)
	h.svc.sched.tick(h.setNow("2026-11-05T08:30:05+07:00"))
	waitFor(t, func() bool { return strings.Contains(h.logText(), "brief waits for Hermes") })
	if got := h.expect("GET", "/v1/stocks/brief", "", 200, ""); got["state"] != BriefRunning {
		t.Fatalf("while waiting = %v", got)
	}
	fa.mu.Lock()
	fa.checkErr = nil
	fa.mu.Unlock()
	h.setNow("2026-11-05T08:35:05+07:00")
	h.retry <- time.Time{}
	h.svc.sched.wait()
	if got := h.expect("GET", "/v1/stocks/brief", "", 200, ""); got["state"] != BriefDone {
		t.Fatalf("after the retry = %v", got)
	}

	// Next day: Hermes stays down; at 08:55 the next retry would pass 09:00 → failed.
	fa.mu.Lock()
	fa.checkErr = hermes.ErrHermesNotRunning
	fa.mu.Unlock()
	h.svc.sched.tick(h.setNow("2026-11-06T08:30:05+07:00"))
	waitFor(t, func() bool { return strings.Count(h.logText(), "brief waits for Hermes") == 2 })
	h.setNow("2026-11-06T08:55:05+07:00")
	h.retry <- time.Time{}
	h.svc.sched.wait()
	got := h.expect("GET", "/v1/stocks/brief", "", 200, "")
	if got["state"] != BriefFailed || got["error"] != "hermes_unavailable" || got["date"] != "2026-11-06" {
		t.Fatalf("failed = %v", got)
	}
	if v := h.briefJob(); *v.LastStatus != JobStatusFailed || *v.LastError != "hermes_unavailable" {
		t.Fatalf("job = %+v", v)
	}
}

func TestBriefCatchUpBefore0900ElseMissed(t *testing.T) {
	fa := &fakeAnalyst{replies: []any{briefReply(nil)}}
	h := newBriefHarness(t, "2026-11-05T08:50:00+07:00", fa)
	h.tickAt("2026-11-05T08:50:00+07:00") // boot after the slot, before 09:00: late run
	if v := h.briefJob(); !v.Late || *v.LastStatus != JobStatusOK {
		t.Fatalf("late = %+v", v)
	}
	if got := h.expect("GET", "/v1/stocks/brief", "", 200, ""); got["late"] != true || got["state"] != BriefDone {
		t.Fatalf("late brief = %v", got)
	}
	h.tickAt("2026-11-06T09:10:00+07:00") // Friday: woke after 09:00
	if v := h.briefJob(); *v.LastStatus != JobStatusMissed {
		t.Fatalf("missed = %+v", v)
	}
	if got := h.expect("GET", "/v1/stocks/brief", "", 200, ""); got["state"] != BriefMissed || got["date"] != "2026-11-06" {
		t.Fatalf("missed brief = %v", got)
	}
	if _, _, _, opened := fa.snapshot(); opened != 1 {
		t.Fatalf("opened %d", opened)
	}
}

func TestParseBrief(t *testing.T) {
	in := briefInput{date: "2026-11-05", refs: map[string]any{"lvl:ANTM:pivot": 3100, "ind:ANTM:rsi14": 55.0, "news:1": "t",
		"ind:BBCA:change_pct": -2.4}, allowed: map[string]bool{"ANTM": true, "BBCA": true}}
	if res, errs := parseBrief("```json\n"+briefReply(nil)+"\n```", in); len(errs) > 0 || res.Focus[0].Code != "ANTM" {
		t.Fatalf("good = %+v %v", res, errs)
	}
	for name, c := range map[string]struct {
		fields map[string]any
		want   string
	}{
		"unknown code":  {map[string]any{"focus": []map[string]any{{"code": "XXXX", "stance": "watch", "note": "n", "sources": []string{"news:1"}}}}, `code "XXXX"`},
		"bad stance":    {map[string]any{"focus": []map[string]any{{"code": "ANTM", "stance": "buy", "note": "n", "sources": []string{"news:1"}}}}, "stance must be"},
		"no source":     {map[string]any{"risks": []map[string]any{{"text": "r", "sources": []string{}}}}, "sources must be"},
		"foreign id":    {map[string]any{"news": []map[string]any{{"text": "r", "sources": []string{"news:9"}}}}, `"news:9" is not an id`},
		"price":         {map[string]any{"summary": "Target Rp4.900"}, "(4.900)"},
		"bare price":    {map[string]any{"summary": "Naik ke 3150"}, "(3150)"},
		"wrong date":    {map[string]any{"session_date": "2026-11-06"}, "session_date must be"},
		"extra key":     {map[string]any{"prices": 1}, `unknown field "prices"`},
		"too long head": {map[string]any{"headline": strings.Repeat("a", maxBriefHead+1)}, "headline must be 1-160"},
		"too many news": {map[string]any{"news": []map[string]any{{"text": "a", "sources": []string{"news:1"}}, {"text": "a", "sources": []string{"news:1"}},
			{"text": "a", "sources": []string{"news:1"}}, {"text": "a", "sources": []string{"news:1"}}, {"text": "a", "sources": []string{"news:1"}},
			{"text": "a", "sources": []string{"news:1"}}}}, "news must be a list of at most 5"},
	} {
		if _, errs := parseBrief(briefReply(c.fields), in); !strings.Contains(strings.Join(errs, "; "), c.want) {
			t.Errorf("%s: errs = %v, want %q", name, errs, c.want)
		}
	}
	for s, want := range map[string]string{"RSI 55, naik 2,43 % pada tahun 2026": "", "1.250 dan 99": "1.250", "harga 120": "120",
		"12.345.678": "12.345.678", "1,5 juta": "", "1.234,5": "1.234,5"} {
		if got := strings.Join(priceLike(s), ","); got != want {
			t.Errorf("priceLike(%q) = %q, want %q", s, got, want)
		}
	}
}

// --- TBA1b: the fix round ---

// tickBounded ticks at now and waits for the jobs it started, failing (never hanging) after 5 s.
func (h *tbaHarness) tickBounded(now string) {
	h.t.Helper()
	h.svc.sched.tick(h.setNow(now))
	done := make(chan struct{})
	go func() { h.svc.sched.wait(); close(done) }()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		h.t.Fatalf("jobs started at %s did not finish in 5 s", now)
	}
}

func (h *tbaHarness) job(id string) JobView {
	h.t.Helper()
	for _, v := range h.svc.sched.views() {
		if v.ID == id {
			return v
		}
	}
	h.t.Fatalf("no %s job", id)
	return JobView{}
}

// Q108: nothing runs on a weekend or an exchange holiday: not the brief (no Hermes session, no record, no alert),
// not the news poll, not the alerts drain.
func TestBriefNeverOnWeekendOrHoliday(t *testing.T) {
	fa := &fakeAnalyst{replies: []any{briefReply(nil), briefReply(nil), briefReply(nil)}}
	h := newBriefHarness(t, "2026-11-06T17:00:00+07:00", fa)
	if v := h.briefJob(); v.NextRun == nil || *v.NextRun != "2026-11-09T08:30:00+07:00" {
		t.Fatalf("brief next_run = %v, want Monday 08:30", v.NextRun)
	}
	// A strong ANTM article made at 08:20 on the weekend days: only a news run could alert on it.
	h.setFeed(feedItem("Saham ANTM melonjak", "https://x.test/w", wib("2026-11-07 08:20")))
	fetches := h.fetches.Load()
	for _, now := range []string{"2026-11-07T08:30:05+07:00", "2026-11-08T08:30:05+07:00", "2026-12-25T08:30:05+07:00"} {
		h.tickBounded(now)
		day := now[:10]
		if _, _, _, opened := fa.snapshot(); opened != 0 {
			t.Fatalf("%s: the brief opened %d Hermes sessions", day, opened)
		}
		if due := h.briefJob().LastDue; due != nil && (strings.HasPrefix(*due, day) || strings.HasPrefix(*due, "2026-12-24")) {
			t.Fatalf("%s: brief last_due = %s", day, *due)
		}
		for _, id := range []string{BriefJobID, NewsJobID, AlertsJobID} {
			if v := h.job(id); v.LastRun != nil || v.Running {
				t.Fatalf("%s: job %s ran: %+v", day, id, v)
			}
		}
		if n := h.fetches.Load(); n != fetches {
			t.Fatalf("%s: %d feed fetches (the news job ran)", day, n-fetches)
		}
		if got := alertKeys(h.alerts()); got != "" {
			t.Fatalf("%s: alerts = %s", day, got)
		}
	}
	h.expect("GET", "/v1/stocks/brief?date=2026-11-07", "", 404, "no_brief")
	h.expect("GET", "/v1/stocks/brief?date=2026-11-08", "", 404, "no_brief")
	h.expect("GET", "/v1/stocks/brief?date=2026-12-25", "", 404, "no_brief")
}

func TestPriceLikeTable(t *testing.T) {
	for s, want := range map[string]string{
		"support di 2050": "2050", "harga 3150": "3150", "target Rp4.900": "4.900", "harga 120": "120", "1.250 dan 99": "1.250",
		"12.345.678": "12.345.678", "1.234,5": "1.234,5",
		"4,9 ribu": "4,9", "5k": "5", "12 rb": "12", "naik 3 kali": "", "1,5 juta": "",
		"volume 150%": "", "volume 150 %": "", "naik 120 persen": "", "RSI 55, naik 2,43 % pada tahun 2026": "",
		"MA200": "", "di atas MA200 dan EMA50": "", "RSI14 jenuh": "",
		"tahun 2026": "", "year 2025": "", "FY2026": "", "FY 2026": "", "kuartal Q3 2026": "", "Maret 2026": "", "5 November 2026": "",
		"Dec 2026": "", "2026 tahun ini": "", "2026-11-05": "", "rilis 2026": "2026", "level 2099": "2099", "pada 2050": "2050",
	} {
		if got := strings.Join(priceLike(s), ","); got != want {
			t.Errorf("priceLike(%q) = %q, want %q", s, got, want)
		}
	}
}

// MINOR-3: a hit first logged by a re-rank is upgraded by the next scan that fires it: one setup alert, and it
// counts toward Q107 once.
// setupKeys lists the setup and analysis alerts only (level alerts from the kept verdict come and go with timing).
func setupKeys(h *tbaHarness) string {
	var out []string
	for _, a := range h.alerts() {
		if a.Kind == AlertSetup || a.Kind == AlertAnalysis {
			out = append(out, a.Kind+":"+*a.Code)
		}
	}
	sort.Strings(out)
	return strings.Join(out, " ")
}

func TestRerankHitUpgradedByScan(t *testing.T) {
	fa := &fakeAnalyst{replies: []any{antmReply()}}
	h := newTBA(t, "2026-11-05T09:00:00+07:00", fa)
	quiet := DefaultScreenerSettings()
	quiet.Setups.VolumeSpike.On, quiet.Setups.Breakout.On = false, false
	b, _ := json.Marshal(quiet)
	h.expect("PUT", "/v1/stocks/screener/settings", string(b), 200, "")
	h.storeBars("BBCA", 4930, 10_000_000)
	h.storeBars("TLKM", 3000, 10_000_000)
	h.storeBars("ANTM", 3100, 100_000)
	h.setQuote("BBCA", 4930, 4930, 3_000_000, "2026-11-05T09:45:00+07:00")
	h.setQuote("TLKM", 3000, 3000, 1_000_000, "2026-11-05T09:45:00+07:00")
	h.setQuote("ANTM", 3150, 3100, 100_000, "2026-11-05T09:45:00+07:00")
	h.openChart("BBCA", 4930, 4930)
	h.openChart("TLKM", 3000, 3000)
	h.openChart("ANTM", 3100, 3100)
	h.tickBounded("2026-11-05T09:55:10+07:00")
	if got := setupKeys(h); got != "" {
		t.Fatalf("alerts after the quiet scan = %s", got)
	}
	// Mid-day settings change: the re-rank logs ANTM's setups with origin rerank; no alert.
	b, _ = json.Marshal(DefaultScreenerSettings())
	h.expect("PUT", "/v1/stocks/screener/settings", string(b), 200, "")
	rerank := 0
	for _, x := range h.screenerDoc().Hits {
		if x.Code == "ANTM" && x.Origin == HitRerank {
			rerank++
		}
	}
	h.svc.alerts.afterHits()
	if rerank < 2 || setupKeys(h) != "" {
		t.Fatalf("rerank hits for ANTM = %d, alerts = %s", rerank, setupKeys(h))
	}
	if _, _, _, opened := fa.snapshot(); opened != 0 {
		t.Fatal("a re-rank started an analysis")
	}
	// The next scan fires the same setups: the hits are upgraded, alerted once, and count for Q107.
	h.tickBounded("2026-11-05T10:05:10+07:00")
	waitFor(t, func() bool { return strings.Contains(setupKeys(h), "analysis:ANTM") })
	if got := setupKeys(h); got != "analysis:ANTM setup:ANTM" {
		t.Fatalf("alerts = %s", got)
	}
	for _, x := range h.screenerDoc().Hits {
		if x.Code == "ANTM" && x.Origin != HitScan {
			t.Fatalf("hit not upgraded: %+v", x)
		}
	}
	h.tickBounded("2026-11-05T10:15:10+07:00")
	if got := setupKeys(h); got != "analysis:ANTM setup:ANTM" || h.svc.alerts.autoRunsView().Used != 1 {
		t.Fatalf("after another scan: alerts = %s, runs = %+v", got, h.svc.alerts.autoRunsView())
	}
	if _, _, _, opened := fa.snapshot(); opened != 1 {
		t.Fatalf("opened %d sessions", opened)
	}
}

// MINOR-4: the Q87 cap must survive a restart, so with an alerts file that cannot be saved no automatic run starts.
func TestNoAutoRunsWhenAlertsStateUnusable(t *testing.T) {
	fa := &fakeAnalyst{replies: []any{antmReply()}}
	h := newTBA(t, "2026-11-05T10:00:00+07:00", fa)
	h.svc.Close()
	if err := os.WriteFile(filepath.Join(h.dir, alertsFile), []byte("not json"), 0o600); err != nil {
		t.Fatal(err)
	}
	h.openTBA()
	ae := h.svc.alerts
	for range 2 {
		ae.proc.Lock()
		ae.maybeAutoRun(h.clock.Now(), "ANTM")
		ae.proc.Unlock()
	}
	if _, _, _, opened := fa.snapshot(); opened != 0 || ae.autoRunsView().Used != 0 {
		t.Fatalf("opened %d, runs %+v", opened, ae.autoRunsView())
	}
	if n := strings.Count(h.logText(), "automatic analyses are off"); n != 1 {
		t.Fatalf("reason logged %d times: %s", n, h.logText())
	}
}

// MINOR-5: a try that failed before prompt.submit is retried; one that failed after it (an Opus turn spent) is final.
func TestBriefRetriesOnlyBeforeSubmit(t *testing.T) {
	// Before: Open fails (a dropped socket) → retried and done.
	fa := &fakeAnalyst{replies: []any{briefReply(nil)}, openErr: hermes.ErrConnLost}
	h := newBriefHarness(t, "2026-11-05T08:00:00+07:00", fa)
	h.svc.sched.tick(h.setNow("2026-11-05T08:30:05+07:00"))
	waitFor(t, func() bool { return strings.Contains(h.logText(), "brief waits for Hermes") })
	fa.mu.Lock()
	fa.openErr = nil
	fa.mu.Unlock()
	h.setNow("2026-11-05T08:35:05+07:00")
	h.retry <- time.Time{}
	waitFor(t, func() bool { return h.expect("GET", "/v1/stocks/brief", "", 200, "")["state"] == BriefDone })
	// After: the socket drops during the turn → failed at once, no second session.
	fa2 := &fakeAnalyst{replies: []any{hermes.ErrConnLost, briefReply(nil)}}
	h2 := newBriefHarness(t, "2026-11-05T08:00:00+07:00", fa2)
	h2.tickBounded("2026-11-05T08:30:05+07:00")
	got := h2.expect("GET", "/v1/stocks/brief", "", 200, "")
	if got["state"] != BriefFailed || got["error"] != "hermes_lost" {
		t.Fatalf("after submit = %v", got)
	}
	if _, _, _, opened := fa2.snapshot(); opened != 1 || strings.Contains(h2.logText(), "brief waits for Hermes") {
		t.Fatalf("opened %d: a lost turn was retried", opened)
	}
	if v := h2.briefJob(); *v.LastStatus != JobStatusFailed || *v.LastError != "hermes_lost" {
		t.Fatalf("job = %+v", v)
	}
	for _, c := range []struct {
		code      string
		submitted bool
		want      bool
	}{{"hermes_lost", false, true}, {"busy", false, true}, {"hermes_unavailable", false, true}, {"hermes_lost", true, false},
		{"busy", true, false}, {"hermes_unavailable", true, false}, {"invalid_brief", false, false}} {
		if got := briefRetryable(c.code, c.submitted); got != c.want {
			t.Errorf("briefRetryable(%s, %v) = %v", c.code, c.submitted, got)
		}
	}
}

// Nit 2: a retry timer that fires late (a suspend) must not try again past 09:00.
func TestBriefLateWakeKeepsCutoff(t *testing.T) {
	fa := &fakeAnalyst{replies: []any{briefReply(nil)}, checkErr: hermes.ErrHermesNotRunning}
	h := newBriefHarness(t, "2026-11-05T08:00:00+07:00", fa)
	h.svc.sched.tick(h.setNow("2026-11-05T08:30:05+07:00"))
	waitFor(t, func() bool { return strings.Contains(h.logText(), "brief waits for Hermes") })
	fa.mu.Lock()
	fa.checkErr = nil // Hermes is back, but the machine wakes at 09:20
	fa.mu.Unlock()
	h.setNow("2026-11-05T09:20:00+07:00")
	h.retry <- time.Time{}
	done := make(chan struct{})
	go func() { h.svc.sched.wait(); close(done) }()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("the brief job did not end")
	}
	got := h.expect("GET", "/v1/stocks/brief", "", 200, "")
	if got["state"] != BriefFailed || got["error"] != "hermes_unavailable" {
		t.Fatalf("late wake = %v", got)
	}
	if _, _, _, opened := fa.snapshot(); opened != 0 {
		t.Fatalf("opened %d sessions after 09:00", opened)
	}
}

// Nit 1: an automatic run whose session does not report the pinned model is aborted before the prompt goes out.
func TestAutoRunAbortsOnModelMismatch(t *testing.T) {
	fa := &fakeAnalyst{replies: []any{antmReply()}, forceModel: "claude-opus-5-5"}
	h := newTBA(t, "2026-11-05T10:00:00+07:00", fa)
	h.storeBars("ANTM", 3100, 100_000)
	h.setQuote("ANTM", 3150, 3100, 100_000, "2026-11-05T09:45:00+07:00")
	ae := h.svc.alerts
	ae.proc.Lock()
	ae.maybeAutoRun(h.clock.Now(), "ANTM")
	ae.proc.Unlock()
	waitFor(t, func() bool { return strings.Contains(h.logText(), "model_mismatch") })
	waitFor(t, func() bool { _, deleted, _, _ := fa.snapshot(); return len(deleted) == 1 })
	prompts, _, _, opened := fa.snapshot()
	if len(prompts) != 0 || opened != 1 {
		t.Fatalf("prompts %d, opened %d: the run went out on the wrong model", len(prompts), opened)
	}
	if strings.Contains(h.logText(), "claude-opus") {
		t.Fatal("the log names a model")
	}
	if len(h.svc.analyzer.kept()) != 0 {
		t.Fatal("a result was kept")
	}
}
