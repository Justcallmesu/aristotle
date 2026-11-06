package stocks

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

// --- Yahoo period method ---

func TestYahooChartPeriodSendsWindow(t *testing.T) {
	y, ft := yahooWith(200, fixed(readFixture(t, "chart_15m.json")))
	from, to := wibs("2026-11-03 00:00:00"), wibs("2026-11-05 00:00:00")
	ch, err := y.ChartPeriod(context.Background(), "BBCA", from, to, Interval5m)
	if err != nil || len(ch.Bars) != 34 || ch.Interval != Interval5m || ch.Range != PeriodRange {
		t.Fatalf("chart = %d bars %s %s, %v", len(ch.Bars), ch.Interval, ch.Range, err)
	}
	req := ft.reqs[0]
	q := req.URL.Query()
	if req.URL.Path != "/v8/finance/chart/BBCA.JK" || q.Get("interval") != "5m" || q.Get("period1") != fmt.Sprint(from.Unix()) ||
		q.Get("period2") != fmt.Sprint(to.Unix()) || q.Has("range") || req.Header.Get("User-Agent") != BrowserUA {
		t.Fatalf("request = %s UA %q", req.URL, req.Header.Get("User-Agent"))
	}
	if _, err := y.ChartPeriod(context.Background(), "BBCA", from, to, "15m"); err == nil {
		t.Fatal("an interval other than 5m/1d must be refused")
	}
	y422, _ := yahooWith(422, fixed([]byte(`{}`)))
	var ue *UpstreamError
	if _, err := y422.ChartPeriod(context.Background(), "BBCA", from, to, Interval5m); !asUpstream(err, &ue) || ue.Status != 422 {
		t.Fatalf("422 = %v", err)
	}
}

func asUpstream(err error, ue **UpstreamError) bool {
	e, ok := err.(*UpstreamError)
	*ue = e
	return ok
}

// --- history ---

// trackHarness is a Service on a temp dir whose job pace is counted (no real wait, no real Yahoo).
type trackHarness struct {
	*harness
	paces  int
	paceMu sync.Mutex
}

func newTrackHarness(t *testing.T, now string, dir string) *trackHarness {
	t.Helper()
	if dir == "" {
		dir = t.TempDir()
	}
	h := &trackHarness{harness: &harness{t: t, prov: newFakeProvider(), clock: newClock(now), dir: dir, logs: &bytes.Buffer{}}}
	h.open()
	return h
}

func (h *trackHarness) open() {
	h.svc = New(Options{StateDir: h.dir, Provider: h.prov, Now: h.clock.Now, FetchFeed: fixtureFetch(h.t, "", nil, nil),
		Log: slog.New(slog.NewTextHandler(h.logs, nil)), NewsAfter: func(time.Duration) <-chan time.Time { return nil },
		SweepAfter: func(time.Duration) <-chan time.Time { return nil },
		BarsPace: func(context.Context) error {
			h.paceMu.Lock()
			h.paces++
			h.paceMu.Unlock()
			return nil
		}})
	h.t.Cleanup(h.svc.Close)
	for _, id := range []string{BarsJobID, ScanJobID, NewsJobID, AlertsJobID, BriefJobID} { // only track.score runs here
		h.expect("PUT", "/v1/stocks/jobs/"+id, `{"on":false}`, 200, "")
	}
}

// runAt sets the clock and ticks the scheduler.
func (h *trackHarness) runAt(now string) {
	h.t.Helper()
	t, err := time.Parse(time.RFC3339, now)
	if err != nil {
		h.t.Fatal(err)
	}
	h.clock.mu.Lock()
	h.clock.t = t
	h.clock.mu.Unlock()
	h.svc.sched.tick(t)
	h.svc.sched.wait()
}

func (h *trackHarness) job() JobView {
	for _, v := range h.svc.sched.views() {
		if v.ID == TrackJobID {
			return v
		}
	}
	h.t.Fatal("no track job")
	return JobView{}
}

func (h *trackHarness) records() map[string]VerdictRecord {
	out := map[string]VerdictRecord{}
	for _, r := range h.svc.analyzer.history.snapshot() {
		out[r.Code+"@"+r.SessionDate] = r
	}
	return out
}

func buyResult(code, analyzedAt, session string) AnalysisResult {
	return AnalysisResult{Code: code, Name: code + " Tbk", Lang: "id", AnalyzedAt: analyzedAt, SessionDate: session, MarketState: StateClosed,
		Model: "claude-opus-5-5", Trigger: TriggerManual, Verdict: "buy_today", Confidence: "med", EntryLow: i64(4900), EntryHigh: i64(4950),
		Target: i64(5100), Stop: i64(4800), Price: 4930, Summary: "secret summary", Reasons: []Reason{{Text: "secret reason", Sources: []string{"ind:rsi14"}}},
		Flags: []string{FlagMarketClosed}, Indicators: map[string]any{"ind:rsi14": 55.0}, News: []NewsRef{}}
}

func readVerdictsFile(t *testing.T, dir string) (string, verdictsDoc) {
	t.Helper()
	b, err := os.ReadFile(filepath.Join(dir, verdictsFile))
	if err != nil {
		t.Fatal(err)
	}
	var doc verdictsDoc
	if err := json.Unmarshal(b, &doc); err != nil {
		t.Fatal(err)
	}
	return string(b), doc
}

func TestHistoryAppendsOnKeep(t *testing.T) {
	h := newTrackHarness(t, "2026-11-05T10:00:00+07:00", "")
	h.svc.analyzer.keep("BBCA", buyResult("BBCA", "2026-11-04T20:00:00+07:00", thuDay))
	h.svc.analyzer.keep("BBCA", buyResult("BBCA", "2026-11-05T09:30:00+07:00", thuDay)) // the same code again: a second record
	raw, doc := readVerdictsFile(t, h.dir)
	if doc.Version != 1 || len(doc.Records) != 2 {
		t.Fatalf("file = %s", raw)
	}
	r := doc.Records[0]
	if r.Code != "BBCA" || r.AnalyzedAt != "2026-11-04T20:00:00+07:00" || r.SessionDate != thuDay || r.Model != "claude-opus-5-5" ||
		r.Trigger != TriggerManual || r.Verdict != "buy_today" || r.Confidence != "med" || !ptrEq(r.EntryHigh, 4950) || r.Price != 4930 ||
		r.MarketState != StateClosed || fmt.Sprint(r.Flags) != "[market_closed]" || r.Score != nil || !strings.HasPrefix(r.ID, "vr_") {
		t.Fatalf("record = %+v", r)
	}
	for _, banned := range []string{"secret", "reasons", "summary", "indicators", "news", "lang"} {
		if strings.Contains(raw, banned) {
			t.Fatalf("history holds %q: %s", banned, raw)
		}
	}
	if st, _ := loadAnalyses(h.dir); len(st.Analyses) != 1 {
		t.Fatalf("Q99's last-result file changed shape: %d results", len(st.Analyses))
	}
}

func TestHistorySeededOnce(t *testing.T) {
	dir := t.TempDir()
	st := emptyAnalyses()
	st.Analyses["BBCA"] = buyResult("BBCA", "2026-11-04T10:00:00+07:00", "2026-11-04")
	st.Analyses["TLKM"] = buyResult("TLKM", "2026-11-03T10:00:00+07:00", "2026-11-03")
	writeAnalyses(t, dir, st)
	h := newTrackHarness(t, "2026-11-05T10:00:00+07:00", dir)
	_, doc := readVerdictsFile(t, dir)
	if len(doc.Records) != 2 || doc.Records[0].Code != "TLKM" || doc.Records[1].Code != "BBCA" {
		t.Fatalf("seeded = %+v", doc.Records)
	}
	ids := doc.Records[0].ID + doc.Records[1].ID
	h.open() // a second start: the history exists, nothing is seeded again
	h.svc.analyzer.keep("ANTM", buyResult("ANTM", "2026-11-05T09:00:00+07:00", thuDay))
	if _, doc = readVerdictsFile(t, dir); len(doc.Records) != 3 || doc.Records[2].Code != "ANTM" || doc.Records[0].ID+doc.Records[1].ID != ids {
		t.Fatalf("after a restart = %+v", doc.Records)
	}
	if !strings.Contains(h.logs.String(), "verdict history created\" file=stocks-verdicts.json seeded=2") {
		t.Fatalf("logs = %s", h.logs)
	}
}

func TestHistoryNewerVersionLeftUntouched(t *testing.T) {
	dir := t.TempDir()
	newer := `{"version":2,"records":[{"id":"vr_old","code":"BBCA","verdict":"avoid","analyzed_at":"2026-11-04T10:00:00+07:00",` +
		`"session_date":"2026-11-04","price":4930,"extra":1}],"more":{}}`
	if err := os.WriteFile(filepath.Join(dir, verdictsFile), []byte(newer), 0o600); err != nil {
		t.Fatal(err)
	}
	h := newTrackHarness(t, "2026-11-05T10:00:00+07:00", dir)
	h.svc.analyzer.keep("TLKM", buyResult("TLKM", "2026-11-05T09:00:00+07:00", thuDay))
	if b, _ := os.ReadFile(filepath.Join(dir, verdictsFile)); string(b) != newer {
		t.Fatalf("file overwritten: %s", b)
	}
	got := h.expect("GET", "/v1/stocks/track-record", "", 200, "")
	if calls := got["calls"].([]any); len(calls) != 2 || calls[1].(map[string]any)["id"] != "vr_old" {
		t.Fatalf("calls = %v", calls)
	}
	if logs := h.logs.String(); !strings.Contains(logs, "verdict history was written by a newer server") || !strings.Contains(logs, "version=2") {
		t.Fatalf("logs = %s", logs)
	}
}

func TestHistoryUnusableFileKeptInMemory(t *testing.T) {
	dir := t.TempDir()
	bad := `{"version":1,"records":[{"id":"vr_1","code":"bbca!"}]}`
	if err := os.WriteFile(filepath.Join(dir, verdictsFile), []byte(bad), 0o600); err != nil {
		t.Fatal(err)
	}
	h := newTrackHarness(t, "2026-11-05T10:00:00+07:00", dir)
	h.svc.analyzer.keep("TLKM", buyResult("TLKM", "2026-11-05T09:00:00+07:00", thuDay))
	if b, _ := os.ReadFile(filepath.Join(dir, verdictsFile)); string(b) != bad {
		t.Fatalf("file overwritten: %s", b)
	}
	got := h.expect("GET", "/v1/stocks/track-record", "", 200, "")
	if calls := got["calls"].([]any); len(calls) != 1 || calls[0].(map[string]any)["code"] != "TLKM" {
		t.Fatalf("calls = %v", calls)
	}
	if !strings.Contains(h.logs.String(), "verdict history unusable") {
		t.Fatalf("logs = %s", h.logs)
	}
}

// --- job ---

// sessionBars are a buy that fills at 09:00 and reaches the 5100 target at 09:05 on day.
func sessionBars(day string) []Bar {
	return []Bar{m5(day, "09:00", 4960, 4920, 4940), m5(day, "09:05", 5110, 4940, 5100), m5(day, "16:10", 5100, 5050, 5080)}
}

func TestTrackJobSkipsWeekendAndHoliday(t *testing.T) {
	h := newTrackHarness(t, "2026-11-06T16:00:00+07:00", "")
	h.runAt("2026-11-06T17:00:10+07:00") // Friday
	if v := h.job(); v.LastDue == nil || *v.LastDue != "2026-11-06T17:00:00+07:00" || v.Late || *v.NextRun != "2026-11-09T17:00:00+07:00" {
		t.Fatalf("friday = %+v", v)
	}
	h.runAt("2026-11-07T17:00:10+07:00") // Saturday
	h.runAt("2026-11-08T17:00:10+07:00") // Sunday
	if v := h.job(); *v.LastDue != "2026-11-06T17:00:00+07:00" {
		t.Fatalf("weekend ran: %+v", v)
	}
	h.runAt("2026-12-23T17:00:10+07:00") // Wednesday before the Christmas holidays
	h.runAt("2026-12-24T17:00:10+07:00") // holiday
	h.runAt("2026-12-25T17:00:10+07:00") // holiday
	if v := h.job(); *v.LastDue != "2026-12-23T17:00:00+07:00" || *v.NextRun != "2026-12-28T17:00:00+07:00" {
		t.Fatalf("holiday ran: %+v", v)
	}
}

// TestTrackJobCatchUpScoresMissedSessions: the PC was off Tue and Wed evening; Thursday morning's catch-up scores both
// sessions of BBCA and TLKM's with one 5-minute request per code (daily bars from the fresh store: no other call).
func TestTrackJobCatchUpScoresMissedSessions(t *testing.T) {
	h := newTrackHarness(t, "2026-11-03T08:00:00+07:00", "")
	an := h.svc.analyzer
	an.keep("BBCA", buyResult("BBCA", "2026-11-03T08:30:00+07:00", "2026-11-03"))
	an.keep("BBCA", buyResult("BBCA", "2026-11-04T08:30:00+07:00", "2026-11-04"))
	an.keep("TLKM", buyResult("TLKM", "2026-11-04T08:30:00+07:00", "2026-11-04"))
	an.keep("ANTM", buyResult("ANTM", "2026-11-05T08:30:00+07:00", thuDay)) // its session has not closed yet
	h.prov.periods = map[string][]Bar{
		"BBCA|5m": append(sessionBars("2026-11-03"), m5("2026-11-04", "09:00", 4960, 4790, 4800)),
		"TLKM|5m": {m5("2026-11-04", "09:00", 5000, 4960, 4990)},
	}
	stored := []Bar{dly("2026-11-03", 5110, 4920, 5080), dly("2026-11-04", 4960, 4790, 4800)}
	h.svc.bars.put("BBCA", "", stored, wibs("2026-11-04 16:20:00"))
	h.svc.bars.put("TLKM", "", []Bar{dly("2026-11-04", 5000, 4960, 4990)}, wibs("2026-11-04 16:20:00"))
	h.runAt("2026-11-05T09:10:00+07:00")
	if got := strings.Join(h.prov.takePeriodCalls(), " "); got != "BBCA|5m|2026-11-03|2026-11-05 TLKM|5m|2026-11-04|2026-11-05" {
		t.Fatalf("calls = %s", got)
	}
	if h.paces != 1 {
		t.Fatalf("paces = %d, want one between the two calls", h.paces)
	}
	recs := h.records()
	for key, want := range map[string]string{"BBCA@2026-11-03": OutcomeWin, "BBCA@2026-11-04": OutcomeLoss, "TLKM@2026-11-04": OutcomeNotTriggered} {
		if r := recs[key]; r.Score == nil || r.Score.Outcome != want || r.Score.Source != SourceIntraday {
			t.Fatalf("%s = %+v, want %s", key, r.Score, want)
		}
	}
	if recs["ANTM@"+thuDay].Score != nil {
		t.Fatal("a session that has not closed was scored")
	}
	if v := h.job(); !v.Late || *v.LastStatus != JobStatusOK || *v.LastDue != "2026-11-04T17:00:00+07:00" {
		t.Fatalf("job = %+v", v)
	}
	if logs := h.logs.String(); !strings.Contains(logs, "track-record run\" codes=2 scored=3 info_filled=0 failed=0 left=0 requests=2") {
		t.Fatalf("logs = %s", logs)
	}
	// Scores are never recomputed: a run over the same data scores nothing again.
	before := recs["BBCA@2026-11-03"].Score.ScoredAt
	h.runAt("2026-11-05T17:00:10+07:00")
	if r := h.records()["BBCA@2026-11-03"]; r.Score.ScoredAt != before {
		t.Fatalf("rescored: %+v", r.Score)
	}
}

// TestTrackJobBlockStopsRun: a Yahoo block on the second code stops the run; the first code's score is kept, the
// third is not asked; the next day's run scores the rest.
func TestTrackJobBlockStopsRun(t *testing.T) {
	h := newTrackHarness(t, "2026-11-05T08:00:00+07:00", "")
	for _, c := range []string{"AAAA", "BBCA", "CCCC"} {
		h.svc.analyzer.keep(c, buyResult(c, "2026-11-05T08:30:00+07:00", thuDay))
		h.svc.bars.put(c, "", []Bar{dly(thuDay, 5110, 4920, 5080)}, wibs("2026-11-05 16:20:00"))
	}
	h.prov.periods = map[string][]Bar{"AAAA|5m": sessionBars(thuDay), "BBCA|5m": sessionBars(thuDay), "CCCC|5m": sessionBars(thuDay)}
	h.prov.periodErrs = map[string]error{"BBCA|5m": &BlockedError{}}
	h.runAt("2026-11-05T17:00:10+07:00")
	if got := strings.Join(h.prov.takePeriodCalls(), " "); got != "AAAA|5m|2026-11-05|2026-11-06 BBCA|5m|2026-11-05|2026-11-06" {
		t.Fatalf("calls = %s", got)
	}
	recs := h.records()
	if recs["AAAA@"+thuDay].Score == nil || recs["BBCA@"+thuDay].Score != nil || recs["CCCC@"+thuDay].Score != nil {
		t.Fatalf("records = %+v", recs)
	}
	if v := h.job(); *v.LastStatus != JobStatusPartial || *v.LastError != "upstream_blocked" {
		t.Fatalf("job = %+v", v)
	}
	if !strings.Contains(h.logs.String(), "scored=1 info_filled=0 failed=0 left=2") {
		t.Fatalf("logs = %s", h.logs)
	}
	h.prov.periodErrs = nil
	h.runAt("2026-11-06T17:00:10+07:00") // the backoff (1 min) is long over
	recs = h.records()
	if recs["BBCA@"+thuDay].Score == nil || recs["CCCC@"+thuDay].Score == nil || recs["BBCA@"+thuDay].Score.Outcome != OutcomeWin {
		t.Fatalf("next day = %+v", recs)
	}
}

// TestTrackJobFallbacks: a 5-minute 422 for a window of sessions older than ~50 days (too old for Yahoo, P16a) and a
// session past the ~60-day range score from daily bars (one 1d window for a code outside the store); an unknown code
// (no 5-minute and no daily bars) is no_session.
func TestTrackJobFallbacks(t *testing.T) {
	h := newTrackHarness(t, "2026-11-05T08:00:00+07:00", "")
	an := h.svc.analyzer
	an.keep("OLDA", buyResult("OLDA", "2026-08-20T08:30:00+07:00", "2026-08-20"))
	an.keep("REJA", buyResult("REJA", "2026-09-14T08:30:00+07:00", "2026-09-14"))
	an.keep("NONE", buyResult("NONE", "2026-11-05T08:30:00+07:00", thuDay))
	h.prov.periods = map[string][]Bar{
		"OLDA|1d": {dly("2026-08-20", 5120, 4790, 5000), dly("2026-08-21", 5000, 4900, 4950)},
		"REJA|1d": {dly("2026-09-14", 5000, 4920, 4990)},
	}
	h.prov.periodErrs = map[string]error{"REJA|5m": &UpstreamError{Reason: "http 422", Status: 422}}
	h.runAt("2026-11-05T17:00:10+07:00")
	want := "NONE|5m|2026-11-05|2026-11-06 NONE|1d|2026-11-05|2026-11-05 OLDA|1d|2026-08-20|2026-11-05 " +
		"REJA|5m|2026-09-14|2026-09-15 REJA|1d|2026-09-14|2026-11-05"
	if got := strings.Join(h.prov.takePeriodCalls(), " "); got != want {
		t.Fatalf("calls = %s\nwant    %s", got, want)
	}
	recs := h.records()
	if s := recs["OLDA@2026-08-20"].Score; s == nil || s.Outcome != OutcomeLoss || s.Source != SourceDaily {
		t.Fatalf("old session = %+v", s)
	}
	if s := recs["REJA@2026-09-14"].Score; s == nil || s.Outcome != OutcomeClosed || s.Source != SourceDaily {
		t.Fatalf("422 = %+v", s)
	}
	if s := recs["NONE@"+thuDay].Score; s == nil || s.Outcome != OutcomeNoSession || s.ResultPct != nil {
		t.Fatalf("unknown code = %+v", s)
	}
	if v := h.job(); *v.LastStatus != JobStatusOK {
		t.Fatalf("job = %+v", v)
	}
}

// TestTrackJobFillsInfo3dLater: scored on its session day, the info column waits for the third session after it and
// is filled by a later run without touching the score.
func TestTrackJobFillsInfo3dLater(t *testing.T) {
	h := newTrackHarness(t, "2026-11-05T08:00:00+07:00", "")
	h.svc.analyzer.keep("BBCA", buyResult("BBCA", "2026-11-05T08:30:00+07:00", thuDay))
	h.prov.periods = map[string][]Bar{"BBCA|5m": sessionBars(thuDay)}
	h.svc.bars.put("BBCA", "", []Bar{dly(thuDay, 5110, 4920, 5080)}, wibs("2026-11-05 16:20:00"))
	h.runAt("2026-11-05T17:00:10+07:00")
	first := *h.records()["BBCA@"+thuDay].Score
	if first.Outcome != OutcomeWin || first.Info3dPct != nil {
		t.Fatalf("first = %+v", first)
	}
	h.svc.bars.put("BBCA", "", []Bar{dly(thuDay, 5110, 4920, 5080), dly("2026-11-06", 5100, 5000, 5050), dly("2026-11-09", 5100, 5000, 5050),
		dly("2026-11-10", 5200, 5000, 5148)}, wibs("2026-11-10 16:20:00"))
	h.prov.takePeriodCalls()
	h.runAt("2026-11-10T17:00:10+07:00")
	got := *h.records()["BBCA@"+thuDay].Score
	if pctStr(got.Info3dPct) != "4.00" || got.ScoredAt != first.ScoredAt || got.Outcome != first.Outcome || pctStr(got.ResultPct) != pctStr(first.ResultPct) {
		t.Fatalf("later = %+v, first %+v", got, first)
	}
	if calls := h.prov.takePeriodCalls(); len(calls) != 0 {
		t.Fatalf("info from the store needs no call: %v", calls)
	}
}

// --- endpoint ---

func scoreOf(outcome string, result, info *float64) *Score {
	return &Score{Rule: ScoreRule, Source: SourceIntraday, Outcome: outcome, ResultPct: result, Info3dPct: info, ScoredAt: "2026-11-05T17:00:00+07:00"}
}

func TestTrackRecordEndpoint(t *testing.T) {
	h := newTrackHarness(t, "2026-11-05T10:00:00+07:00", "")
	hist := h.svc.analyzer.history
	hist.mu.Lock()
	add := func(code, verdict, model, trigger, conf, session string, minute int, sc *Score) {
		hist.recs = append(hist.recs, VerdictRecord{ID: fmt.Sprintf("vr_%03d", len(hist.recs)), Code: code, Verdict: verdict, Model: model,
			Trigger: trigger, Confidence: conf, SessionDate: session, AnalyzedAt: fmt.Sprintf("%sT08:%02d:00+07:00", session, minute), Score: sc})
	}
	add("BBCA", "buy_today", "opus", "manual", "high", "2026-11-03", 0, scoreOf(OutcomeWin, f64(3.03), f64(4)))
	add("TLKM", "buy_today", "opus", "manual", "high", "2026-11-03", 1, scoreOf(OutcomeLoss, f64(-2.02), nil))
	add("ANTM", "buy_today", "opus", "manual", "high", "2026-11-04", 0, scoreOf(OutcomeNotTriggered, nil, f64(1)))
	add("BBCA", "buy_today", "sonnet", "auto", "med", "2026-11-04", 1, scoreOf(OutcomeClosed, f64(0.81), nil))
	add("BBCA", "avoid", "opus", "manual", "low", "2026-11-04", 2, scoreOf(OutcomeRight, f64(-0.61), nil))
	add("BBCA", "wait", "opus", "manual", "low", "2026-11-04", 3, scoreOf(OutcomeWouldHave, f64(1.03), nil))
	add("BBCA", "avoid", "opus", "manual", "low", "2026-11-04", 4, scoreOf(OutcomeNoSession, nil, nil))
	for i := range 55 { // unscored calls: pending, newest last
		add("XXXX", "wait", "opus", "manual", "low", "2026-11-05", i, nil)
	}
	hist.mu.Unlock()

	got := h.expect("GET", "/v1/stocks/track-record", "", 200, "")
	if got["pending"] != 55.0 || got["scored_through"] != "2026-11-04" {
		t.Fatalf("pending %v scored_through %v", got["pending"], got["scored_through"])
	}
	sum := got["summary"].([]any)
	var rows []string
	for _, g := range sum {
		m := g.(map[string]any)
		rows = append(rows, fmt.Sprintf("%v/%v/%v/%v n=%v w=%v l=%v c=%v nt=%v r=%v wr=%v wh=%v ns=%v res=%v/%v/%v info=%v/%v/%v",
			m["verdict"], m["model"], m["trigger"], m["confidence"], m["n"], m["win"], m["loss"], m["closed"], m["not_triggered"], m["right"],
			m["wrong"], m["would_have"], m["no_session"], m["result_n"], m["result_sum"], m["result_avg"], m["info_3d_n"], m["info_3d_sum"], m["info_3d_avg"]))
	}
	want := []string{
		"avoid/opus/manual/low n=2 w=0 l=0 c=0 nt=0 r=1 wr=0 wh=0 ns=1 res=1/-0.61/-0.61 info=0/0/<nil>",
		"buy_today/opus/manual/high n=3 w=1 l=1 c=0 nt=1 r=0 wr=0 wh=0 ns=0 res=2/1.01/0.51 info=2/5/2.5",
		"buy_today/sonnet/auto/med n=1 w=0 l=0 c=1 nt=0 r=0 wr=0 wh=0 ns=0 res=1/0.81/0.81 info=0/0/<nil>",
		"wait/opus/manual/low n=1 w=0 l=0 c=0 nt=0 r=0 wr=0 wh=1 ns=0 res=1/1.03/1.03 info=0/0/<nil>",
	}
	if strings.Join(rows, "\n") != strings.Join(want, "\n") {
		t.Fatalf("summary =\n%s\nwant\n%s", strings.Join(rows, "\n"), strings.Join(want, "\n"))
	}
	for _, g := range sum {
		for _, k := range []string{"win_rate", "rate", "hit_rate"} {
			if _, ok := g.(map[string]any)[k]; ok {
				t.Fatalf("the server computes no rate: %v", g)
			}
		}
	}
	calls := got["calls"].([]any)
	if len(calls) != 50 || calls[0].(map[string]any)["analyzed_at"] != "2026-11-05T08:54:00+07:00" {
		t.Fatalf("calls = %d, first %v", len(calls), calls[0])
	}
	got = h.expect("GET", "/v1/stocks/track-record?code=bbca", "", 200, "")
	calls = got["calls"].([]any)
	if len(calls) != 5 || calls[0].(map[string]any)["verdict"] != "avoid" || calls[4].(map[string]any)["verdict"] != "buy_today" {
		t.Fatalf("BBCA calls = %v", calls)
	}
	if c := calls[0].(map[string]any); c["score"].(map[string]any)["outcome"] != OutcomeNoSession || c["code"] != "BBCA" {
		t.Fatalf("call = %v", c)
	}
	if len(got["summary"].([]any)) != 4 || got["pending"] != 55.0 {
		t.Fatalf("summary and pending stay over every record: %v", got)
	}
	h.expect("GET", "/v1/stocks/track-record?code=BBCA.JK", "", http.StatusBadRequest, "bad_code")
}

func TestTrackRecordEmpty(t *testing.T) {
	h := newTrackHarness(t, "2026-11-05T10:00:00+07:00", "")
	got := h.expect("GET", "/v1/stocks/track-record", "", 200, "")
	if fmt.Sprint(got) != "map[calls:[] pending:0 scored_through:<nil> summary:[]]" {
		t.Fatalf("empty = %v", got)
	}
}

// --- TR1b: fallback, validation, give-up, info and ordering rules ---

// TestTrackJobFallsBackOnlyWhenTooOld (P15a, P16a): only a 200 without bars (or a 422 for old sessions) scores from daily
// bars; a 403, 401, 400, a bad body, a 422 for a recent session, or a 404 for a code with daily bars leaves the record
// for the next run and the run reports the failure.
func TestTrackJobFallsBackOnlyWhenTooOld(t *testing.T) {
	h := newTrackHarness(t, "2026-11-05T08:00:00+07:00", "")
	codes := []string{"EMPT", "F400", "F401", "F403", "F404", "FBOD", "REJA"}
	for _, c := range codes {
		h.svc.analyzer.keep(c, buyResult(c, "2026-11-05T08:30:00+07:00", thuDay))
		h.svc.bars.put(c, "", []Bar{dly(thuDay, 5000, 4920, 4990)}, wibs("2026-11-05 16:20:00"))
	}
	h.prov.periods = map[string][]Bar{"EMPT|5m": {}}
	h.prov.periodErrs = map[string]error{"F400|5m": &UpstreamError{Reason: "http 400", Status: 400},
		"F401|5m": &UpstreamError{Reason: "http 401", Status: 401}, "F403|5m": &UpstreamError{Reason: "http 403", Status: 403},
		"F404|5m": ErrUnknownSymbol, "FBOD|5m": &UpstreamError{Reason: "bad chart body"}, "REJA|5m": &UpstreamError{Reason: "http 422", Status: 422}}
	h.runAt("2026-11-05T17:00:10+07:00")
	if got := len(h.prov.takePeriodCalls()); got != len(codes) {
		t.Fatalf("calls = %d, want one 5m call per code (daily bars from the store)", got)
	}
	recs := h.records()
	if s := recs["EMPT@"+thuDay].Score; s == nil || s.Outcome != OutcomeClosed || s.Source != SourceDaily {
		t.Fatalf("EMPT = %+v, want a daily score", s)
	}
	for _, c := range []string{"F400", "F401", "F403", "F404", "FBOD", "REJA"} {
		if s := recs[c+"@"+thuDay].Score; s != nil {
			t.Fatalf("%s scored on a transient failure: %+v", c, s)
		}
	}
	if v := h.job(); *v.LastStatus != JobStatusPartial || *v.LastError != "codes_failed" {
		t.Fatalf("job = %+v", v)
	}
}

// TestTrackJob422RetriesWithoutOldSessions (P15a): a 422 on a window holding a session older than ~50 days is asked
// again without it; only that old session scores from its daily bar.
func TestTrackJob422RetriesWithoutOldSessions(t *testing.T) {
	h := newTrackHarness(t, "2026-09-14T08:00:00+07:00", "")
	h.svc.analyzer.keep("OLDB", buyResult("OLDB", "2026-09-14T08:30:00+07:00", "2026-09-14"))
	h.svc.analyzer.keep("OLDB", buyResult("OLDB", "2026-11-04T08:30:00+07:00", "2026-11-04"))
	h.svc.bars.put("OLDB", "", []Bar{dly("2026-09-14", 5120, 4790, 5000), dly("2026-11-04", 5110, 4920, 5080)}, wibs("2026-11-05 16:20:00"))
	h.prov.periods = map[string][]Bar{"OLDB|5m": sessionBars("2026-11-04")}
	h.prov.period5mFloor = wibs("2026-09-20 00:00:00")
	h.runAt("2026-11-05T17:00:10+07:00")
	if got := strings.Join(h.prov.takePeriodCalls(), " "); got != "OLDB|5m|2026-09-14|2026-11-05 OLDB|5m|2026-11-04|2026-11-05" {
		t.Fatalf("calls = %s", got)
	}
	recs := h.records()
	if s := recs["OLDB@2026-09-14"].Score; s == nil || s.Outcome != OutcomeLoss || s.Source != SourceDaily {
		t.Fatalf("old session = %+v", s)
	}
	if s := recs["OLDB@2026-11-04"].Score; s == nil || s.Outcome != OutcomeWin || s.Source != SourceIntraday {
		t.Fatalf("recent session = %+v", s)
	}
	if v := h.job(); *v.LastStatus != JobStatusOK {
		t.Fatalf("job = %+v", v)
	}
}

// TestHistoryUnscorableRecordsSkipped (MAJOR 3): a record without its levels, with a bad analyzed_at or session date is
// kept but never scored, in a version-1 and in a newer file; logged once with its code; the job never panics.
func TestHistoryUnscorableRecordsSkipped(t *testing.T) {
	recs := `{"id":"vr_good","code":"GOOD","verdict":"avoid","analyzed_at":"2026-11-05T08:30:00+07:00","session_date":"2026-11-05","price":4930},` +
		`{"id":"vr_nolv","code":"NOLV","verdict":"buy_today","analyzed_at":"2026-11-05T08:30:00+07:00","session_date":"2026-11-05","price":4930},` +
		`{"id":"vr_badt","code":"BADT","verdict":"avoid","analyzed_at":"2026-11-05 08:30","session_date":"2026-11-05","price":4930},` +
		`{"id":"vr_bads","code":"BADS","verdict":"avoid","analyzed_at":"2026-11-05T08:30:00+07:00","session_date":"2026-13-45","price":4930}`
	for _, version := range []int{1, 2} {
		t.Run(fmt.Sprint("version ", version), func(t *testing.T) {
			dir := t.TempDir()
			file := fmt.Sprintf(`{"version":%d,"records":[%s]}`, version, recs)
			if err := os.WriteFile(filepath.Join(dir, verdictsFile), []byte(file), 0o600); err != nil {
				t.Fatal(err)
			}
			h := newTrackHarness(t, "2026-11-05T08:00:00+07:00", dir)
			for _, c := range []string{"GOOD", "NOLV", "BADT"} {
				h.svc.bars.put(c, "", []Bar{dly(thuDay, 5000, 4700, 4990)}, wibs("2026-11-05 16:20:00"))
			}
			h.prov.periods = map[string][]Bar{"GOOD|5m": sessionBars(thuDay), "NOLV|5m": sessionBars(thuDay), "BADT|5m": sessionBars(thuDay)}
			h.runAt("2026-11-05T17:00:10+07:00")
			h.runAt("2026-11-06T17:00:10+07:00")
			got := map[string]VerdictRecord{}
			for _, r := range h.svc.analyzer.history.snapshot() {
				got[r.Code] = r
			}
			if len(got) != 4 || got["GOOD"].Score == nil || got["NOLV"].Score != nil || got["BADT"].Score != nil || got["BADS"].Score != nil {
				t.Fatalf("records = %+v", got)
			}
			if calls := strings.Join(h.prov.takePeriodCalls(), " "); calls != "GOOD|5m|2026-11-05|2026-11-06" {
				t.Fatalf("calls = %s", calls)
			}
			if v := h.job(); *v.LastStatus != JobStatusOK {
				t.Fatalf("job = %+v", v)
			}
			logs := h.logs.String()
			for _, c := range []string{"NOLV", "BADT", "BADS"} {
				if n := strings.Count(logs, "verdict record cannot be scored; it is skipped\" code="+c); n != 1 {
					t.Fatalf("%s logged %d times: %s", c, n, logs)
				}
			}
		})
	}
}

// TestTrackJobGivesUpAfterTenTradingDays (P16, P16a): a record still unscored 10 trading days after its session, its
// code failing again after failing on an earlier day, is scored unscorable (own count, no result); a younger record of
// the same code waits.
func TestTrackJobGivesUpAfterTenTradingDays(t *testing.T) {
	h := newTrackHarness(t, "2026-11-05T08:00:00+07:00", "")
	h.svc.analyzer.keep("FAIL", buyResult("FAIL", "2026-11-05T08:30:00+07:00", thuDay))
	h.svc.analyzer.keep("FAIL", buyResult("FAIL", "2026-11-16T08:30:00+07:00", "2026-11-16"))
	h.prov.periodErrs = map[string]error{"FAIL|5m": &UpstreamError{Reason: "http 403", Status: 403}}
	h.runAt("2026-11-18T17:00:10+07:00") // 9 trading days after 11-05
	if s := h.records()["FAIL@"+thuDay].Score; s != nil {
		t.Fatalf("given up too early: %+v", s)
	}
	h.runAt("2026-11-19T17:00:10+07:00") // the 10th
	recs := h.records()
	if s := recs["FAIL@"+thuDay].Score; s == nil || s.Outcome != OutcomeUnscorable || s.ResultPct != nil || s.Close != nil || s.Info3dPct != nil {
		t.Fatalf("old record = %+v", s)
	}
	if s := recs["FAIL@2026-11-16"].Score; s != nil {
		t.Fatalf("young record = %+v", s)
	}
	if !strings.Contains(h.logs.String(), "unscorable") {
		t.Fatalf("logs = %s", h.logs)
	}
	got := h.expect("GET", "/v1/stocks/track-record", "", 200, "")
	g := got["summary"].([]any)[0].(map[string]any)
	if g["unscorable"] != 1.0 || g["n"] != 1.0 || g["loss"] != 0.0 || g["result_n"] != 0.0 || got["pending"] != 1.0 {
		t.Fatalf("summary = %v pending %v", g, got["pending"])
	}
	h.prov.takePeriodCalls()
	h.prov.periodErrs = nil
	h.runAt("2026-11-24T17:00:10+07:00") // past info_3d's earliest day: an unscorable record never asks for it
	for _, c := range h.prov.takePeriodCalls() {
		if strings.HasSuffix(c, "|1d|2026-11-05|2026-11-24") {
			t.Fatalf("info_3d asked for an unscorable record: %s", c)
		}
	}
}

// TestTrackJobNoInfoForNoSession (P16): a no_session record never asks for info_3d.
func TestTrackJobNoInfoForNoSession(t *testing.T) {
	h := newTrackHarness(t, "2026-11-05T08:00:00+07:00", "")
	h.svc.analyzer.keep("NONE", buyResult("NONE", "2026-11-05T08:30:00+07:00", thuDay))
	h.runAt("2026-11-05T17:00:10+07:00")
	if s := h.records()["NONE@"+thuDay].Score; s == nil || s.Outcome != OutcomeNoSession {
		t.Fatalf("score = %+v", s)
	}
	h.prov.takePeriodCalls()
	h.runAt("2026-11-10T17:00:10+07:00")
	h.runAt("2026-11-11T17:00:10+07:00")
	if calls := h.prov.takePeriodCalls(); len(calls) != 0 {
		t.Fatalf("no_session asked again: %v", calls)
	}
}

// TestTrackJobInfo3dSkipsSplit (P16): when the refetched session close differs from the stored close by more than 1 %
// (a split adjusted Yahoo's history), info_3d stays empty and the code is logged.
func TestTrackJobInfo3dSkipsSplit(t *testing.T) {
	h := newTrackHarness(t, "2026-11-05T08:00:00+07:00", "")
	h.svc.analyzer.keep("BBCA", buyResult("BBCA", "2026-11-05T08:30:00+07:00", thuDay))
	h.prov.periods = map[string][]Bar{"BBCA|5m": sessionBars(thuDay)}
	h.svc.bars.put("BBCA", "", []Bar{dly(thuDay, 5110, 4920, 5080)}, wibs("2026-11-05 16:20:00"))
	h.runAt("2026-11-05T17:00:10+07:00")
	if s := h.records()["BBCA@"+thuDay].Score; s == nil || s.Close == nil || *s.Close != 5080 {
		t.Fatalf("score = %+v", s)
	}
	split := []Bar{dly(thuDay, 1022, 984, 1016), dly("2026-11-06", 1020, 1000, 1010), dly("2026-11-09", 1020, 1000, 1010),
		dly("2026-11-10", 1040, 1000, 1030)} // a 1:5 split: Yahoo divides the history
	h.svc.bars.put("BBCA", "", split, wibs("2026-11-10 16:20:00"))
	h.runAt("2026-11-10T17:00:10+07:00")
	if s := h.records()["BBCA@"+thuDay].Score; s.Info3dPct != nil {
		t.Fatalf("info_3d across a split = %s", pctStr(s.Info3dPct))
	}
	if !strings.Contains(h.logs.String(), "code=BBCA") || !strings.Contains(h.logs.String(), "split") {
		t.Fatalf("logs = %s", h.logs)
	}
	ok := []Bar{dly(thuDay, 5110, 4920, 5050), dly("2026-11-06", 5100, 5000, 5050), dly("2026-11-09", 5100, 5000, 5050),
		dly("2026-11-10", 5200, 5000, 5148)} // within 1 %: filled
	h.svc.bars.put("BBCA", "", ok, wibs("2026-11-11 16:20:00"))
	h.runAt("2026-11-11T17:00:10+07:00")
	if s := h.records()["BBCA@"+thuDay].Score; pctStr(s.Info3dPct) != "4.00" {
		t.Fatalf("info_3d = %s", pctStr(s.Info3dPct))
	}
}

// TestTrackJobInfo3dCap: info_3d is asked for up to 30 days after the session, never later.
func TestTrackJobInfo3dCap(t *testing.T) {
	h := newTrackHarness(t, "2026-10-01T08:00:00+07:00", "")
	h.svc.analyzer.keep("OLDI", buyResult("OLDI", "2026-10-01T08:30:00+07:00", "2026-10-01"))
	h.svc.analyzer.keep("NEWI", buyResult("NEWI", "2026-10-09T08:30:00+07:00", "2026-10-09"))
	ids := map[string]Score{}
	for _, r := range h.svc.analyzer.history.snapshot() {
		ids[r.ID] = *scoreOf(OutcomeClosed, f64(0.81), nil)
	}
	if err := h.svc.analyzer.history.apply(ids); err != nil {
		t.Fatal(err)
	}
	h.runAt("2026-11-05T17:00:10+07:00") // OLDI: 35 days, NEWI: 27 days
	if calls := strings.Join(h.prov.takePeriodCalls(), " "); calls != "NEWI|1d|2026-10-09|2026-11-05" {
		t.Fatalf("calls = %s", calls)
	}
}

// TestHistoryApplyNeverOverwritesScore (P13): apply attaches a score only to an unscored record; on a scored one it only
// fills a missing info_3d.
func TestHistoryApplyNeverOverwritesScore(t *testing.T) {
	h := newTrackHarness(t, "2026-11-05T08:00:00+07:00", "")
	hist := h.svc.analyzer.history
	h.svc.analyzer.keep("BBCA", buyResult("BBCA", "2026-11-05T08:30:00+07:00", thuDay))
	id := hist.snapshot()[0].ID
	if err := hist.apply(map[string]Score{id: *scoreOf(OutcomeWin, f64(3.03), nil)}); err != nil {
		t.Fatal(err)
	}
	if err := hist.apply(map[string]Score{id: *scoreOf(OutcomeLoss, f64(-3.03), f64(7))}); err != nil {
		t.Fatal(err)
	}
	s := hist.snapshot()[0].Score
	if s.Outcome != OutcomeWin || pctStr(s.ResultPct) != "3.03" || pctStr(s.Info3dPct) != "7.00" {
		t.Fatalf("score = %+v result %s info %s", s, pctStr(s.ResultPct), pctStr(s.Info3dPct))
	}
	if err := hist.apply(map[string]Score{id: *scoreOf(OutcomeLoss, nil, f64(-9))}); err != nil {
		t.Fatal(err)
	}
	if s = hist.snapshot()[0].Score; s.Outcome != OutcomeWin || pctStr(s.Info3dPct) != "7.00" {
		t.Fatalf("info_3d replaced: %+v", s)
	}
	if _, doc := readVerdictsFile(t, h.dir); doc.Records[0].Score.Outcome != OutcomeWin {
		t.Fatalf("file = %+v", doc.Records[0].Score)
	}
}

// TestHistoryKeepDedupes: the same result kept twice (same code and analyzed_at) is one record.
func TestHistoryKeepDedupes(t *testing.T) {
	h := newTrackHarness(t, "2026-11-05T08:00:00+07:00", "")
	res := buyResult("BBCA", "2026-11-05T08:30:00+07:00", thuDay)
	h.svc.analyzer.keep("BBCA", res)
	h.svc.analyzer.keep("BBCA", res)
	if _, doc := readVerdictsFile(t, h.dir); len(doc.Records) != 1 {
		t.Fatalf("records = %d", len(doc.Records))
	}
}

// TestTrackJobStaleStoreNotUsed: a bar store fetched before the session closed is not used; one 1d window is asked.
func TestTrackJobStaleStoreNotUsed(t *testing.T) {
	h := newTrackHarness(t, "2026-11-05T08:00:00+07:00", "")
	h.svc.analyzer.keep("BBCA", buyResult("BBCA", "2026-11-05T08:30:00+07:00", thuDay))
	h.svc.bars.put("BBCA", "", []Bar{dly(thuDay, 5110, 4790, 4800)}, wibs("2026-11-05 11:00:00")) // mid-session: stale
	h.prov.periods = map[string][]Bar{"BBCA|1d": {dly(thuDay, 5000, 4920, 4990)}, "BBCA|5m": {}}
	h.runAt("2026-11-05T17:00:10+07:00")
	if got := strings.Join(h.prov.takePeriodCalls(), " "); got != "BBCA|5m|2026-11-05|2026-11-06 BBCA|1d|2026-11-05|2026-11-05" {
		t.Fatalf("calls = %s", got)
	}
	if s := h.records()["BBCA@"+thuDay].Score; s == nil || s.Outcome != OutcomeClosed || *s.Close != 4990 {
		t.Fatalf("score = %+v", s)
	}
}

// TestTrackJobWaitsForBarsCatchUp: at boot both jobs are late; track.score does not start while bars.daily refreshes
// the store, and runs on a later tick from the fresh store (no 1d request of its own).
func TestTrackJobWaitsForBarsCatchUp(t *testing.T) {
	h := newTrackHarness(t, "2026-11-04T08:00:00+07:00", "")
	h.expect("PUT", "/v1/stocks/jobs/"+BarsJobID, `{"on":true}`, 200, "")
	h.expect("PUT", "/v1/stocks/universe", `{"lq45":[],"idx80":["BBCA"],"valid_to":"2027-01-29"}`, 200, "")
	h.prov.charts["BBCA"] = Chart{Code: "BBCA", Bars: []Bar{dly("2026-11-03", 5000, 4900, 4950), dly("2026-11-04", 5110, 4920, 5080)}}
	h.svc.analyzer.keep("BBCA", buyResult("BBCA", "2026-11-04T08:30:00+07:00", "2026-11-04"))
	h.prov.periods = map[string][]Bar{"BBCA|5m": sessionBars("2026-11-04")}
	h.runAt("2026-11-05T09:10:00+07:00") // boot catch-up: bars.daily starts, track.score waits
	if v := h.job(); v.LastRun != nil {
		t.Fatalf("track.score ran beside bars.daily: %+v", v)
	}
	if calls := h.prov.takePeriodCalls(); len(calls) != 0 {
		t.Fatalf("calls = %v", calls)
	}
	h.runAt("2026-11-05T09:10:30+07:00") // the next tick
	if got := strings.Join(h.prov.takePeriodCalls(), " "); got != "BBCA|5m|2026-11-04|2026-11-05" {
		t.Fatalf("calls = %s (daily bars must come from the fresh store)", got)
	}
	if v := h.job(); v.LastRun == nil || !v.Late || *v.LastStatus != JobStatusOK {
		t.Fatalf("job = %+v", v)
	}
	if s := h.records()["BBCA@2026-11-04"].Score; s == nil || s.Outcome != OutcomeWin || *s.Close != 5080 {
		t.Fatalf("score = %+v", s)
	}
}

// TestHistorySeedWaitsForUsableAnalyses: an unusable stocks-analyses.json at the first start creates no history file
// (the seed waits); once the file is fixed the next start seeds from it.
func TestHistorySeedWaitsForUsableAnalyses(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, analysesFile), []byte(`{"version":1,"analyses":`), 0o600); err != nil {
		t.Fatal(err)
	}
	h := newTrackHarness(t, "2026-11-05T10:00:00+07:00", dir)
	h.svc.analyzer.keep("TLKM", buyResult("TLKM", "2026-11-05T09:00:00+07:00", thuDay))
	if _, err := os.Stat(filepath.Join(dir, verdictsFile)); !os.IsNotExist(err) {
		t.Fatalf("history created while the analyses file is unusable: %v", err)
	}
	if !strings.Contains(h.logs.String(), "seed waits") {
		t.Fatalf("logs = %s", h.logs)
	}
	h.svc.Close()
	st := emptyAnalyses()
	st.Analyses["BBCA"] = buyResult("BBCA", "2026-11-04T10:00:00+07:00", "2026-11-04")
	writeAnalyses(t, dir, st)
	h.open()
	if _, doc := readVerdictsFile(t, dir); len(doc.Records) != 1 || doc.Records[0].Code != "BBCA" {
		t.Fatalf("seeded = %+v", doc.Records)
	}
}

// --- TR1c: network errors, recent 422s, give-up streaks, guards ---

func netErr() error { return &UpstreamError{Reason: "network"} }

// keepOld keeps one buy per code for 2026-10-13, more than 10 trading days before the runs below.
func (h *trackHarness) keepOld(codes ...string) {
	for _, c := range codes {
		h.svc.analyzer.keep(c, buyResult(c, "2026-10-13T08:30:00+07:00", "2026-10-13"))
	}
}

// TestTrackJobNetworkErrorAfterOfflineGapGivesUpNothing (P16a): back after three weeks away, the first run gets a network
// error per code: it stops at the first (one request), nothing is scored or given up; the next day's good data scores
// every record.
func TestTrackJobNetworkErrorAfterOfflineGapGivesUpNothing(t *testing.T) {
	h := newTrackHarness(t, "2026-10-13T08:00:00+07:00", "")
	codes := []string{"AAAA", "BBBB", "CCCC"}
	h.keepOld(codes...)
	h.prov.periodErrs = map[string]error{}
	for _, c := range codes {
		h.prov.periodErrs[c+"|5m"] = netErr()
	}
	h.runAt("2026-11-05T17:00:10+07:00")
	if got := strings.Join(h.prov.takePeriodCalls(), " "); got != "AAAA|5m|2026-10-13|2026-10-14" {
		t.Fatalf("calls = %s (a network error stops the run)", got)
	}
	for key, r := range h.records() {
		if r.Score != nil {
			t.Fatalf("%s scored after a network error: %+v", key, r.Score)
		}
	}
	if v := h.job(); *v.LastStatus != JobStatusFailed || *v.LastError != "network_error" {
		t.Fatalf("job = %+v", v)
	}
	if !strings.Contains(h.logs.String(), "unscorable=0") {
		t.Fatalf("logs = %s", h.logs)
	}
	h.prov.periodErrs = nil
	h.prov.periods = map[string][]Bar{}
	for _, c := range codes {
		h.prov.periods[c+"|5m"] = sessionBars("2026-10-13")
		h.prov.periods[c+"|1d"] = []Bar{dly("2026-10-13", 5110, 4920, 5080)}
	}
	h.runAt("2026-11-06T17:00:10+07:00")
	for key, r := range h.records() {
		if r.Score == nil || r.Score.Outcome != OutcomeWin || r.Score.Source != SourceIntraday {
			t.Fatalf("%s next day = %+v", key, r.Score)
		}
	}
}

// TestTrackJobNetworkDownAtBootGivesUpNothing (P16a): the network is down at the boot catch-up and on the next two runs:
// every run stops after one request, nothing is ever given up; once it is back the records score.
func TestTrackJobNetworkDownAtBootGivesUpNothing(t *testing.T) {
	h := newTrackHarness(t, "2026-10-13T08:00:00+07:00", "")
	h.keepOld("AAAA", "BBBB")
	h.prov.setErr(netErr())
	for _, at := range []string{"2026-11-05T09:10:00+07:00", "2026-11-05T17:00:10+07:00", "2026-11-06T17:00:10+07:00"} {
		h.runAt(at)
		if calls := h.prov.takePeriodCalls(); len(calls) != 1 {
			t.Fatalf("%s: calls = %v, want one", at, calls)
		}
		for key, r := range h.records() {
			if r.Score != nil {
				t.Fatalf("%s: %s scored with the network down: %+v", at, key, r.Score)
			}
		}
		if v := h.job(); *v.LastError != "network_error" {
			t.Fatalf("%s: job = %+v", at, v)
		}
	}
	h.prov.setErr(nil)
	h.prov.periods = map[string][]Bar{"AAAA|5m": sessionBars("2026-10-13"), "BBBB|5m": sessionBars("2026-10-13"),
		"AAAA|1d": {dly("2026-10-13", 5110, 4920, 5080)}, "BBBB|1d": {dly("2026-10-13", 5110, 4920, 5080)}}
	h.runAt("2026-11-09T17:00:10+07:00")
	for key, r := range h.records() {
		if r.Score == nil || r.Score.Outcome != OutcomeWin {
			t.Fatalf("%s = %+v", key, r.Score)
		}
	}
}

// TestTrackJobDailyNetworkErrorStopsRun (P16a): a network error on the 1d request stops the run like one on the 5-minute
// request: the code after it is not asked.
func TestTrackJobDailyNetworkErrorStopsRun(t *testing.T) {
	h := newTrackHarness(t, "2026-10-13T08:00:00+07:00", "")
	h.keepOld("AAAA", "BBBB")
	h.prov.periods = map[string][]Bar{"AAAA|5m": sessionBars("2026-10-13"), "BBBB|5m": sessionBars("2026-10-13")}
	h.prov.periodErrs = map[string]error{"AAAA|1d": netErr()}
	h.runAt("2026-11-05T17:00:10+07:00")
	if got := strings.Join(h.prov.takePeriodCalls(), " "); got != "AAAA|5m|2026-10-13|2026-10-14 AAAA|1d|2026-10-13|2026-11-05" {
		t.Fatalf("calls = %s", got)
	}
	for key, r := range h.records() {
		if r.Score != nil {
			t.Fatalf("%s = %+v", key, r.Score)
		}
	}
}

// TestTrackJobGivesUpOnlyAfterFailingOnTwoDays (P16a): a code failing (403) on its first run 10+ trading days after the
// session gives up nothing, nor on a second run the same day; failing again the next day it does.
func TestTrackJobGivesUpOnlyAfterFailingOnTwoDays(t *testing.T) {
	h := newTrackHarness(t, "2026-10-13T08:00:00+07:00", "")
	h.keepOld("FAIL")
	h.prov.periodErrs = map[string]error{"FAIL|5m": &UpstreamError{Reason: "http 403", Status: 403}}
	for _, at := range []string{"2026-11-05T09:10:00+07:00", "2026-11-05T17:00:10+07:00"} {
		h.runAt(at)
		if s := h.records()["FAIL@2026-10-13"].Score; s != nil {
			t.Fatalf("%s: given up on the first failing day: %+v", at, s)
		}
		if v := h.job(); *v.LastError != "codes_failed" {
			t.Fatalf("%s: job = %+v", at, v)
		}
	}
	h.runAt("2026-11-06T17:00:10+07:00")
	if s := h.records()["FAIL@2026-10-13"].Score; s == nil || s.Outcome != OutcomeUnscorable {
		t.Fatalf("failing on a second day = %+v", s)
	}
}

// TestTrackJobSuccessEndsFailStreak (P16a): a code that failed, then scored, starts a new streak: one later failure 10+
// trading days after a newer record's session gives up nothing.
func TestTrackJobSuccessEndsFailStreak(t *testing.T) {
	h := newTrackHarness(t, "2026-11-02T08:00:00+07:00", "")
	h.svc.analyzer.keep("ZZZZ", buyResult("ZZZZ", "2026-11-02T08:30:00+07:00", "2026-11-02"))
	h.prov.periodErrs = map[string]error{"ZZZZ|5m": &UpstreamError{Reason: "http 403", Status: 403}}
	h.runAt("2026-11-02T17:00:10+07:00") // fails
	h.prov.periodErrs = nil
	h.prov.periods = map[string][]Bar{"ZZZZ|5m": sessionBars("2026-11-02"), "ZZZZ|1d": {dly("2026-11-02", 5110, 4920, 5080)}}
	h.runAt("2026-11-03T17:00:10+07:00") // scores
	if s := h.records()["ZZZZ@2026-11-02"].Score; s == nil || s.Outcome != OutcomeWin {
		t.Fatalf("score = %+v", s)
	}
	h.svc.analyzer.keep("ZZZZ", buyResult("ZZZZ", "2026-11-04T08:30:00+07:00", "2026-11-04"))
	h.prov.periodErrs = map[string]error{"ZZZZ|5m": &UpstreamError{Reason: "http 403", Status: 403}}
	h.runAt("2026-11-20T17:00:10+07:00") // 12 trading days later: the first failure of a new streak
	if s := h.records()["ZZZZ@2026-11-04"].Score; s != nil {
		t.Fatalf("given up on the first failure after a success: %+v", s)
	}
}

// TestTrackJobRecent422Waits (P16a): a 422 for a window of recent sessions is not "too old": yesterday's call waits (no
// daily loss), also when the retry without the sessions older than ~50 days 422s too.
func TestTrackJobRecent422Waits(t *testing.T) {
	h := newTrackHarness(t, "2026-09-14T08:00:00+07:00", "")
	h.svc.analyzer.keep("RECA", buyResult("RECA", "2026-11-05T08:30:00+07:00", thuDay))
	h.svc.analyzer.keep("RECB", buyResult("RECB", "2026-09-14T08:30:00+07:00", "2026-09-14"))
	h.svc.analyzer.keep("RECB", buyResult("RECB", "2026-11-05T08:30:00+07:00", thuDay))
	stopDay := []Bar{dly("2026-09-14", 5120, 4790, 5000), dly(thuDay, 5120, 4790, 5000)} // a daily fallback would be a loss
	h.svc.bars.put("RECA", "", stopDay, wibs("2026-11-05 16:20:00"))
	h.svc.bars.put("RECB", "", stopDay, wibs("2026-11-05 16:20:00"))
	r422 := &UpstreamError{Reason: "http 422", Status: 422}
	h.prov.periodErrs = map[string]error{"RECA|5m": r422, "RECB|5m": r422}
	h.runAt("2026-11-06T17:00:10+07:00")
	want := "RECA|5m|2026-11-05|2026-11-06 RECB|5m|2026-09-14|2026-11-06 RECB|5m|2026-11-05|2026-11-06"
	if got := strings.Join(h.prov.takePeriodCalls(), " "); got != want {
		t.Fatalf("calls = %s\nwant    %s", got, want)
	}
	for key, r := range h.records() {
		if r.Score != nil { // one failing run gives nothing up, whatever the age (P16b)
			t.Fatalf("%s scored after a recent 422: %+v", key, r.Score)
		}
	}
	if v := h.job(); *v.LastStatus != JobStatusFailed || *v.LastError != "codes_failed" {
		t.Fatalf("job = %+v", v)
	}
	h.runAt("2026-11-09T17:00:10+07:00") // a second failing day: RECB's September record (40 trading days) is given up
	recs := h.records()
	if s := recs["RECB@2026-09-14"].Score; s == nil || s.Outcome != OutcomeUnscorable {
		t.Fatalf("RECB September = %+v", s)
	}
	if recs["RECA@"+thuDay].Score != nil || recs["RECB@"+thuDay].Score != nil {
		t.Fatal("a recent record was scored or given up")
	}
}

// TestTrackJobInfoOnlyStaleStoreAsks: an info-only run uses the bar store only when it is current (it holds every
// completed session); a store left behind (bars.daily off) is not used and one 1d window is asked.
func TestTrackJobInfoOnlyStaleStoreAsks(t *testing.T) {
	h := newTrackHarness(t, "2026-11-05T08:00:00+07:00", "")
	h.svc.analyzer.keep("BBCA", buyResult("BBCA", "2026-11-05T08:30:00+07:00", thuDay))
	h.prov.periods = map[string][]Bar{"BBCA|5m": sessionBars(thuDay), "BBCA|1d": {dly(thuDay, 5110, 4920, 5080),
		dly("2026-11-06", 5100, 5000, 5050), dly("2026-11-09", 5100, 5000, 5050), dly("2026-11-10", 5200, 5000, 5148)}}
	h.svc.bars.put("BBCA", "", []Bar{dly(thuDay, 5110, 4920, 5080)}, wibs("2026-11-05 16:20:00"))
	h.runAt("2026-11-05T17:00:10+07:00")
	h.prov.takePeriodCalls()
	h.runAt("2026-11-10T17:00:10+07:00") // the store still holds only 11-05
	if got := strings.Join(h.prov.takePeriodCalls(), " "); got != "BBCA|1d|2026-11-05|2026-11-10" {
		t.Fatalf("calls = %s", got)
	}
	if s := h.records()["BBCA@"+thuDay].Score; pctStr(s.Info3dPct) != "4.00" {
		t.Fatalf("info_3d = %s", pctStr(s.Info3dPct))
	}
}

// TestHistoryNeverAppendsUnscorable: a result that can never be scored (a buy without a stop, a price of 0) is not added
// to the history, neither by the seed nor by a keep; it is logged with its code.
func TestHistoryNeverAppendsUnscorable(t *testing.T) {
	dir := t.TempDir()
	noStop := buyResult("NOST", "2026-11-04T10:00:00+07:00", "2026-11-04")
	noStop.Stop = nil
	st := emptyAnalyses()
	st.Analyses["NOST"] = noStop
	st.Analyses["BBCA"] = buyResult("BBCA", "2026-11-04T10:00:00+07:00", "2026-11-04")
	writeAnalyses(t, dir, st)
	h := newTrackHarness(t, "2026-11-05T10:00:00+07:00", dir)
	noPrice := buyResult("NOPR", "2026-11-05T09:00:00+07:00", thuDay)
	noPrice.Price = 0
	h.svc.analyzer.history.add(noPrice)
	if _, doc := readVerdictsFile(t, dir); len(doc.Records) != 1 || doc.Records[0].Code != "BBCA" {
		t.Fatalf("records = %+v", doc.Records)
	}
	for _, c := range []string{"NOST", "NOPR"} {
		if !strings.Contains(h.logs.String(), "verdict not added to the history (no scorable verdict)\" code="+c) {
			t.Fatalf("%s not logged: %s", c, h.logs)
		}
	}
}

// --- TR1d: give-up across restarts, 403 gap, body-read error ---

var err403 = &UpstreamError{Reason: "http 403", Status: 403}

// reopen restarts the server on the same state dir (a daily restart of the laptop's service).
func (h *trackHarness) reopen() {
	h.svc.Close()
	h.open()
}

// TestTrackJobGiveUpSurvivesRestart (P16b): the first failing day, a restart, the next day's failure: given up. The
// failure dates are in stocks-track-failures.json; a missing file reads as "no failures".
func TestTrackJobGiveUpSurvivesRestart(t *testing.T) {
	h := newTrackHarness(t, "2026-10-13T08:00:00+07:00", "")
	h.keepOld("FAIL")
	h.prov.periodErrs = map[string]error{"FAIL|5m": err403}
	h.runAt("2026-11-05T17:00:10+07:00")
	if s := h.records()["FAIL@2026-10-13"].Score; s != nil {
		t.Fatalf("given up on the first failing day: %+v", s)
	}
	if f := readFailuresFile(t, h.dir)["FAIL"]; f.First != "2026-11-05" || f.Last != "2026-11-05" {
		t.Fatalf("failure dates not persisted: %+v", f)
	}
	h.reopen()
	h.runAt("2026-11-06T17:00:10+07:00")
	if s := h.records()["FAIL@2026-10-13"].Score; s == nil || s.Outcome != OutcomeUnscorable {
		t.Fatalf("after a restart the second failing day = %+v", s)
	}
}

// TestTrackJobDailyRestartDoesNotResetStreak (P16b): a restart before every run (a daily restart) never resets the
// streak: the second failing day gives up, the first never does.
func TestTrackJobDailyRestartDoesNotResetStreak(t *testing.T) {
	h := newTrackHarness(t, "2026-10-13T08:00:00+07:00", "")
	h.keepOld("FAIL")
	h.prov.periodErrs = map[string]error{"FAIL|5m": err403}
	for i, at := range []string{"2026-11-04T17:00:10+07:00", "2026-11-05T17:00:10+07:00"} {
		h.reopen()
		h.runAt(at)
		s := h.records()["FAIL@2026-10-13"].Score
		if (i == 0) != (s == nil) {
			t.Fatalf("%s: score = %+v", at, s)
		}
	}
}

// TestTrackJobIsolatedFailuresNeverGiveUp (P16b): two failures weeks apart are two streaks.
func TestTrackJobIsolatedFailuresNeverGiveUp(t *testing.T) {
	h := newTrackHarness(t, "2026-10-13T08:00:00+07:00", "")
	h.keepOld("FAIL")
	h.prov.periodErrs = map[string]error{"FAIL|5m": err403}
	for _, at := range []string{"2026-11-05T17:00:10+07:00", "2026-11-20T17:00:10+07:00"} {
		h.reopen()
		h.runAt(at)
		if s := h.records()["FAIL@2026-10-13"].Score; s != nil {
			t.Fatalf("%s: given up on an isolated failure: %+v", at, s)
		}
	}
	h.reopen()
	h.runAt("2026-11-23T17:00:10+07:00") // the next trading day: a run of two days now
	if s := h.records()["FAIL@2026-10-13"].Score; s == nil || s.Outcome != OutcomeUnscorable {
		t.Fatalf("failing on consecutive trading days = %+v", s)
	}
}

// TestTrackJobHardLimitThirtyTradingDays (P16b): a record unscored 30 trading days after its session is unscorable on
// the first failing run, logged.
func TestTrackJobHardLimitThirtyTradingDays(t *testing.T) {
	h := newTrackHarness(t, "2026-10-13T08:00:00+07:00", "")
	h.keepOld("FAIL")
	h.prov.periodErrs = map[string]error{"FAIL|5m": err403}
	h.runAt("2026-11-20T17:00:10+07:00") // 28 trading days
	if s := h.records()["FAIL@2026-10-13"].Score; s != nil {
		t.Fatalf("before the limit: %+v", s)
	}
	h.reopen()
	h.runAt("2026-12-02T17:00:10+07:00") // a new streak (a week later), but past 30 trading days
	if s := h.records()["FAIL@2026-10-13"].Score; s == nil || s.Outcome != OutcomeUnscorable {
		t.Fatalf("past the hard limit = %+v", s)
	}
	if !strings.Contains(h.logs.String(), "unscorable") {
		t.Fatalf("not logged: %s", h.logs)
	}
}

// TestTrackJob403OnOldWindowWaits (P16a): a 403 on a window whose first session is 52 days old is not "too old": no
// daily fallback (only a 422 is). Fails if tooOld accepts any status >= 400.
func TestTrackJob403OnOldWindowWaits(t *testing.T) {
	h := newTrackHarness(t, "2026-10-13T08:00:00+07:00", "")
	h.keepOld("OLDX")
	h.prov.periods = map[string][]Bar{"OLDX|1d": {dly("2026-10-13", 5110, 4920, 5080)}}
	h.prov.periodErrs = map[string]error{"OLDX|5m": err403}
	h.runAt("2026-12-04T17:00:10+07:00")
	if got := strings.Join(h.prov.takePeriodCalls(), " "); got != "OLDX|5m|2026-10-13|2026-10-14" {
		t.Fatalf("calls = %s (a 403 must neither retry nor fall back to 1d)", got)
	}
	if s := h.records()["OLDX@2026-10-13"].Score; s != nil && s.Source != SourceNone {
		t.Fatalf("scored from bars after a 403: %+v", s)
	}
}

// errBody is a response body that breaks after some bytes.
type errBody struct{ read bool }

func (b *errBody) Read(p []byte) (int, error) {
	if b.read {
		return 0, fmt.Errorf("connection reset")
	}
	b.read = true
	return copy(p, `{"chart":`), nil
}
func (b *errBody) Close() error { return nil }

type brokenBodyTransport struct{}

func (brokenBodyTransport) RoundTrip(r *http.Request) (*http.Response, error) {
	return &http.Response{StatusCode: 200, Body: &errBody{}, Header: http.Header{}, Request: r}, nil
}

// TestYahooBodyReadErrorIsNetwork: a body that breaks after a 200 is a network error (it stops a run), not a code failure.
func TestYahooBodyReadErrorIsNetwork(t *testing.T) {
	y := &Yahoo{Client: &http.Client{Transport: brokenBodyTransport{}}}
	_, err := y.ChartPeriod(context.Background(), "BBCA", wibs("2026-11-03 00:00:00"), wibs("2026-11-05 00:00:00"), Interval5m)
	if !isNetwork(err) || !isStop(context.Background(), err) {
		t.Fatalf("err = %v, want a network error", err)
	}
}
