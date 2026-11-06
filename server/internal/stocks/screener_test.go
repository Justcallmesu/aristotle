package stocks

import (
	"bytes"
	"context"
	"encoding/json"
	"log/slog"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

// screenHarness is a Service whose quoted set is BBCA, TLKM (universe override) and ANTM (watchlist), with a
// counted pace instead of the 1.5 s sleep.
type screenHarness struct {
	*harness
	paceMu sync.Mutex
	paces  int
}

func newScreenHarness(t *testing.T, now string) *screenHarness {
	t.Helper()
	h := &screenHarness{harness: &harness{t: t, prov: newFakeProvider(), clock: newClock(now), dir: t.TempDir(), logs: &bytes.Buffer{}}}
	h.open()
	h.expect("PUT", "/v1/stocks/universe", `{"lq45":[],"idx80":["TLKM","BBCA"],"valid_to":"2027-01-29"}`, 200, "")
	h.expect("PUT", "/v1/stocks/watchlist/ANTM", "", 200, "")
	return h
}

func (h *screenHarness) open() {
	h.svc = New(Options{StateDir: h.dir, Provider: h.prov, Now: h.clock.Now, FetchFeed: fixtureFetch(h.t, "", nil, nil),
		Log: slog.New(slog.NewTextHandler(h.logs, nil)), NewsAfter: func(time.Duration) <-chan time.Time { return nil },
		SweepAfter: func(time.Duration) <-chan time.Time { return nil },
		BarsPace: func(ctx context.Context) error {
			h.paceMu.Lock()
			h.paces++
			h.paceMu.Unlock()
			return ctx.Err()
		}})
	h.t.Cleanup(h.svc.Close)
}

func (h *screenHarness) setNow(now string) time.Time {
	h.t.Helper()
	t, err := time.Parse(time.RFC3339, now)
	if err != nil {
		h.t.Fatal(err)
	}
	h.clock.mu.Lock()
	h.clock.t = t
	h.clock.mu.Unlock()
	return t
}

// tickAt sets the clock, ticks the scheduler and waits for the jobs it started.
func (h *screenHarness) tickAt(now string) {
	h.svc.sched.tick(h.setNow(now))
	h.svc.sched.wait()
}

func (h *screenHarness) scanJob() JobView {
	for _, v := range h.svc.sched.views() {
		if v.ID == ScanJobID {
			return v
		}
	}
	h.t.Fatal("no scan job")
	return JobView{}
}

// storeBars puts 60 flat sessions ending Wed 2026-11-04 at close c, volume v, fetched Wed 16:20.
func (h *screenHarness) storeBars(code string, c, v int64) {
	h.svc.bars.put(code, "", series("2026-11-04", flat(60, c), 10, v), wib("2026-11-04 16:20"))
	if err := h.svc.bars.save(); err != nil {
		h.t.Fatal(err)
	}
}

func (h *screenHarness) setQuote(code string, p, pc, vol int64, tm string) {
	h.prov.mu.Lock()
	defer h.prov.mu.Unlock()
	h.prov.quotes[code] = Quote{Code: code, Price: p, PrevClose: i64(pc), Volume: i64(vol), MarketTime: strp(tm)}
}

// openChart answers the daily 5d chart with Wed's and Thu's bars (Thu's open = open).
func (h *screenHarness) openChart(code string, wedClose, open int64) {
	h.prov.mu.Lock()
	defer h.prov.mu.Unlock()
	if h.prov.rangeCharts == nil {
		h.prov.rangeCharts = map[string]Chart{}
	}
	h.prov.rangeCharts[code+"|"+openRange] = Chart{Code: code, Bars: []Bar{
		{T: wib("2026-11-04 09:00").Unix(), O: wedClose, H: wedClose, L: wedClose, C: wedClose, V: 1},
		{T: wib("2026-11-05 09:00").Unix(), O: open, H: open + 50, L: open - 50, C: open, V: 1}}}
}

func (h *screenHarness) screenerDoc() screenerDoc {
	h.t.Helper()
	data, err := os.ReadFile(filepath.Join(h.dir, screenerFile))
	if err != nil {
		h.t.Fatal(err)
	}
	var doc screenerDoc
	if err := json.Unmarshal(data, &doc); err != nil {
		h.t.Fatal(err)
	}
	return doc
}

func candCodes(got map[string]any, key string) string {
	var out []string
	for _, c := range got[key].([]any) {
		m := c.(map[string]any)
		var ids []string
		for _, s := range m["setups"].([]any) {
			sm := s.(map[string]any)
			ids = append(ids, sm["id"].(string)+":"+sm["dir"].(string))
		}
		out = append(out, m["code"].(string)+"["+strings.Join(ids, ",")+"]")
	}
	return strings.Join(out, " ")
}

func TestScreenerNoBarsBeforeFirstBarsRun(t *testing.T) {
	h := newScreenHarness(t, "2026-11-05T09:55:10+07:00")
	h.tickAt("2026-11-05T09:55:10+07:00")
	if q, _, _ := h.prov.calls(); q != 0 {
		t.Fatalf("quote calls = %d: no Yahoo call without stored bars", q)
	}
	if v := h.scanJob(); v.LastStatus == nil || *v.LastStatus != JobStatusFailed || *v.LastError != "no_bars" || *v.LastDue != "2026-11-05T09:55:00+07:00" {
		t.Fatalf("job = %+v", v)
	}
	got := h.expect("GET", "/v1/stocks/screener", "", 200, "")
	scan := got["scan"].(map[string]any)
	if got["mode"] != ModeEOD || got["session_date"] != nil || scan["error"] != "no_bars" || len(got["candidates"].([]any)) != 0 ||
		scan["next_run"] != "2026-11-05T16:20:00+07:00" {
		t.Fatalf("screener = %v", got)
	}
	h.expect("GET", "/v1/stocks/screener?mode=intra", "", 400, "bad_request")
}

func TestScreenerScanJob(t *testing.T) {
	h := newScreenHarness(t, "2026-11-05T09:00:00+07:00")
	h.storeBars("BBCA", 4930, 10_000_000) // Rp 49 bn/day
	h.storeBars("TLKM", 3000, 10_000_000)
	h.storeBars("ANTM", 3100, 100_000) // Rp 0.31 bn/day: illiquid, but watched
	// Data time 09:45 (45 of 330 min): BBCA vr = 3 M / (10 M × 45/330) = 2.2 → breakout (4980 > 4940) and spike;
	// daily-bar open 5050 vs 4930 → gap +2.43 %. ANTM opens 3000 vs 3100 → gap −3.23 %: a flag.
	h.setQuote("BBCA", 4980, 4930, 3_000_000, "2026-11-05T09:45:00+07:00")
	h.setQuote("TLKM", 3000, 3000, 1_000_000, "2026-11-05T09:45:00+07:00")
	h.setQuote("ANTM", 3010, 3100, 1, "2026-11-05T09:45:00+07:00")
	h.openChart("BBCA", 4930, 5050)
	h.openChart("TLKM", 3000, 3000)
	h.openChart("ANTM", 3100, 3000)

	h.tickAt("2026-11-05T09:55:10+07:00")
	if q, c, _ := h.prov.calls(); q != 1 || c != 3 || h.paces != 3 {
		t.Fatalf("calls: spark %d chart %d paces %d, want 1, 3 (one open per code), 3", q, c, h.paces)
	}
	if v := h.scanJob(); *v.LastStatus != JobStatusOK || v.LastError != nil {
		t.Fatalf("job = %+v", v)
	}
	if !strings.Contains(h.logs.String(), "stocks: screener scan") || !strings.Contains(h.logs.String(), "spark_calls=1 chart_calls=3") {
		t.Fatalf("no budget log: %s", h.logs.String())
	}
	got := h.expect("GET", "/v1/stocks/screener", "", 200, "")
	if got["mode"] != ModeIntraday || got["session_date"] != "2026-11-05" || got["data_as_of"] != "2026-11-05T09:45:00+07:00" || got["stale"] != false {
		t.Fatalf("head = %v", got)
	}
	if s := candCodes(got, "candidates"); s != "BBCA[breakout:up,volume_spike:up,gap:up]" {
		t.Fatalf("candidates = %s", s)
	}
	if s := candCodes(got, "flags"); s != "ANTM[gap:down]" {
		t.Fatalf("flags = %s", s)
	}
	c := got["candidates"].([]any)[0].(map[string]any)
	// Score 3 + 2 + 1 + 0.1 × 2.2 = 6.2; val20 4930 × 10 M = Rp 49.3 bn.
	if c["score"] != 6.2 || c["val20"] != 49.3 || c["name"] != "Bank Central Asia Tbk" || c["in_watchlist"] != false || c["price"] != 4980.0 ||
		c["change_pct"] != 1.01 || c["at_ara"] != false {
		t.Fatalf("BBCA = %v", c)
	}
	gap := c["setups"].([]any)[2].(map[string]any)
	if v := gap["values"].(map[string]any); v["gap_pct"] != 2.43 || v["open"] != 5050.0 || v["filled"] != false || gap["first_at"] != "2026-11-05T09:45:00+07:00" {
		t.Fatalf("gap = %v", gap)
	}
	scan := got["scan"].(map[string]any)
	sk := got["skipped"].(map[string]any)
	if scan["status"] != "ok" || scan["codes_ok"] != 3.0 || scan["codes_failed"] != 0.0 || scan["next_run"] != "2026-11-05T10:05:00+07:00" ||
		sk["illiquid"] != 1.0 || sk["no_bars"] != 0.0 || got["universe"].(map[string]any)["count"] != 2.0 {
		t.Fatalf("scan = %v skipped = %v", scan, sk)
	}
	doc := h.screenerDoc()
	if len(doc.Hits) != 4 || doc.Seq != 4 || doc.Hits[0] != (ScreenHit{Seq: 1, Date: "2026-11-05", Code: "BBCA", Setup: SetupBreakout, Dir: DirUp,
		Mode: ModeIntraday, Origin: HitScan, At: "2026-11-05T09:55:10+07:00", DataAsOf: "2026-11-05T09:45:00+07:00", Score: 6.2}) ||
		doc.Hits[3].Code != "ANTM" || doc.Hits[3].Dir != DirDown || !doc.Hits[3].Watch {
		t.Fatalf("hits = %+v", doc.Hits)
	}

	// Next slot: spark again (the 60 s cache has expired), no open refetch, no new hit, first_at kept.
	h.setQuote("BBCA", 4990, 4930, 4_000_000, "2026-11-05T09:55:00+07:00")
	h.tickAt("2026-11-05T10:05:10+07:00")
	if q, c, _ := h.prov.calls(); q != 2 || c != 3 || h.paces != 3 {
		t.Fatalf("second scan calls: spark %d chart %d paces %d", q, c, h.paces)
	}
	got = h.expect("GET", "/v1/stocks/screener", "", 200, "")
	bb := got["candidates"].([]any)[0].(map[string]any)
	if got["data_as_of"] != "2026-11-05T09:55:00+07:00" || bb["setups"].([]any)[0].(map[string]any)["first_at"] != "2026-11-05T09:45:00+07:00" ||
		len(h.screenerDoc().Hits) != 4 {
		t.Fatalf("second scan = %v", got)
	}

	// Yahoo blocks: the scan stops, the last good result stays and is marked stale.
	h.prov.setErr(&BlockedError{})
	h.tickAt("2026-11-05T10:15:10+07:00")
	if v := h.scanJob(); *v.LastStatus != JobStatusFailed || *v.LastError != "upstream_blocked" {
		t.Fatalf("blocked job = %+v", v)
	}
	got = h.expect("GET", "/v1/stocks/screener", "", 200, "")
	scan = got["scan"].(map[string]any)
	if got["stale"] != true || scan["status"] != "failed" || scan["error"] != "upstream_blocked" || got["data_as_of"] != "2026-11-05T09:55:00+07:00" ||
		candCodes(got, "candidates") != "BBCA[breakout:up,volume_spike:up,gap:up]" {
		t.Fatalf("blocked = %v", got)
	}
	h.prov.setErr(nil)

	// A settings change re-ranks the kept quotes at once, without a Yahoo call.
	q0, c0, _ := h.prov.calls()
	set := DefaultScreenerSettings()
	set.Setups.Breakout.On = false
	body, _ := json.Marshal(set)
	h.expect("PUT", "/v1/stocks/screener/settings", string(body), 200, "")
	if q, c, _ := h.prov.calls(); q != q0 || c != c0 {
		t.Fatal("re-rank called Yahoo")
	}
	got = h.expect("GET", "/v1/stocks/screener", "", 200, "")
	if s := candCodes(got, "candidates"); s != "BBCA[volume_spike:up,gap:up]" {
		t.Fatalf("re-ranked = %s", s)
	}
	// vr at 09:55 = 4 M / (10 M × 55/330) = 2.4: 2 + 1 + 0.24 = 3.2.
	if got["candidates"].([]any)[0].(map[string]any)["score"] != 3.2 {
		t.Fatalf("re-ranked score = %v", got["candidates"])
	}

	// Restart: the result, settings and hits come back from stocks-screener.json; the open is fetched once again.
	h.svc.Close()
	h.open()
	got = h.expect("GET", "/v1/stocks/screener", "", 200, "")
	if got["mode"] != ModeIntraday || candCodes(got, "candidates") != "BBCA[volume_spike:up,gap:up]" {
		t.Fatalf("after restart = %v", got)
	}
	h.tickAt("2026-11-05T10:25:10+07:00")
	if _, c, _ := h.prov.calls(); c != c0+3 {
		t.Fatalf("chart calls after restart = %d, want %d", c, c0+3)
	}
	if doc := h.screenerDoc(); len(doc.Hits) != 4 || doc.Seq != 4 {
		t.Fatalf("hits after restart = %+v", doc.Hits)
	}
}

func TestScreenerManualScan(t *testing.T) {
	h := newScreenHarness(t, "2026-11-05T10:00:00+07:00")
	h.storeBars("BBCA", 4930, 10_000_000)
	h.setQuote("BBCA", 4980, 4930, 3_000_000, "2026-11-05T09:50:00+07:00")
	h.openChart("BBCA", 4930, 5050)

	got := h.expect("POST", "/v1/stocks/screener/scan", "", 202, "")
	if got["mode"] != ModeIntraday || got["scan"].(map[string]any)["running"] != true {
		t.Fatalf("post = %v", got)
	}
	h.svc.screen.wg.Wait()
	if q, _, _ := h.prov.calls(); q != 1 {
		t.Fatalf("quote calls = %d", q)
	}
	_, rec := h.expectRec("POST", "/v1/stocks/screener/scan", 429)
	if rec.Header().Get("Retry-After") != "120" || !strings.Contains(rec.Body.String(), `"error":"too_soon","retry_after_s":120`) {
		t.Fatalf("too soon = %s %v", rec.Body.String(), rec.Header())
	}
	h.clock.Add(90 * time.Second)
	if _, rec := h.expectRec("POST", "/v1/stocks/screener/scan", 429); rec.Header().Get("Retry-After") != "30" {
		t.Fatalf("retry after = %v", rec.Header())
	}
	h.clock.Add(30 * time.Second)
	// A scan in progress (scheduled or manual) refuses a second one.
	if err := h.svc.screen.begin(ModeIntraday, h.clock.Now(), false); err != nil {
		t.Fatal(err)
	}
	h.expect("POST", "/v1/stocks/screener/scan", "", 409, "scan_running")
	// ... and the scheduled slot is skipped while it runs (no second Yahoo round).
	if err := h.svc.runScanJob(context.Background(), JobRun{}); err != nil {
		t.Fatal(err)
	}
	h.svc.screen.end()
	if q, _, _ := h.prov.calls(); q != 1 {
		t.Fatalf("quote calls = %d", q)
	}

	// In the lunch break (outside the scan windows) a manual scan is the EOD screen from the store: no Yahoo call.
	h.setNow("2026-11-05T12:30:00+07:00")
	got = h.expect("POST", "/v1/stocks/screener/scan", "", 202, "")
	h.svc.screen.wg.Wait()
	if q, c, _ := h.prov.calls(); got["mode"] != ModeEOD || q != 1 || c != 1 {
		t.Fatalf("break scan: %v calls %d/%d", got, q, c)
	}
	got = h.expect("GET", "/v1/stocks/screener?mode=eod", "", 200, "")
	if got["session_date"] != "2026-11-04" || got["data_as_of"] != "2026-11-04T16:00:00+07:00" || got["scan"].(map[string]any)["status"] != "ok" {
		t.Fatalf("eod = %v", got)
	}
	// The default view is still today's intraday scan.
	if got := h.expect("GET", "/v1/stocks/screener", "", 200, ""); got["mode"] != ModeIntraday {
		t.Fatalf("default = %v", got["mode"])
	}
}

func TestScreenerEODAfterBarsJob(t *testing.T) {
	h := newScreenHarness(t, "2026-11-05T16:00:00+07:00")
	// Thursday closes at 1050 on 2 × volume after 60 sessions at 1000: breakout, spike and MA20/50 cross up.
	bars := series("2026-11-05", append(flat(60, 1000), 1050), 10, 10_000_000)
	bars[60].H, bars[60].V = 1060, 20_000_000
	h.prov.mu.Lock()
	h.prov.charts["BBCA"] = Chart{Code: "BBCA", Name: "Bank Central Asia Tbk", Bars: bars}
	h.prov.charts["TLKM"] = Chart{Code: "TLKM", Bars: series("2026-11-05", flat(61, 3000), 10, 10_000_000)}
	h.prov.charts["ANTM"] = Chart{Code: "ANTM", Bars: series("2026-11-05", append(flat(60, 3000), 2900), 10, 10_000_000)}
	h.prov.mu.Unlock()
	h.tickAt("2026-11-05T16:20:05+07:00")
	if q, c, _ := h.prov.calls(); q != 0 || c != 3 {
		t.Fatalf("calls = %d/%d: the EOD screen must not call Yahoo", q, c)
	}
	got := h.expect("GET", "/v1/stocks/screener", "", 200, "")
	if got["mode"] != ModeEOD || got["session_date"] != "2026-11-05" || got["data_as_of"] != "2026-11-05T16:00:00+07:00" {
		t.Fatalf("eod = %v", got)
	}
	if s := candCodes(got, "candidates"); s != "BBCA[breakout:up,volume_spike:up,ma_cross:up]" {
		t.Fatalf("candidates = %s", s)
	}
	// ANTM (watched) closes at 2900: MA20 2995 < MA50 2998, crossed down.
	if s := candCodes(got, "flags"); s != "ANTM[ma_cross:down]" {
		t.Fatalf("flags = %s", s)
	}
	if c := got["candidates"].([]any)[0].(map[string]any); c["score"] != 7.2 || c["change_pct"] != 5.0 {
		t.Fatalf("BBCA = %v", c)
	}
	doc := h.screenerDoc()
	if len(doc.EOD) != 1 || doc.Runs[ModeEOD].Status != JobStatusOK || doc.Runs[ModeEOD].CodesOK != 3 || len(doc.Hits) != 4 ||
		doc.Hits[0].Mode != ModeEOD || doc.Hits[0].Origin != HitEOD || doc.Hits[0].DataAsOf != "2026-11-05T16:00:00+07:00" {
		t.Fatalf("doc = %+v", doc)
	}
	// Friday morning: the default view is Thursday's close until Friday's first scan; the ma_cross shows intraday with its date.
	h.setNow("2026-11-06T09:05:00+07:00")
	if got := h.expect("GET", "/v1/stocks/screener", "", 200, ""); got["mode"] != ModeEOD || got["session_date"] != "2026-11-05" {
		t.Fatalf("friday = %v", got)
	}
}

func TestScreenerFileUnusable(t *testing.T) {
	h := &harness{t: t, prov: newFakeProvider(), clock: newClock("2026-11-05T10:00:00+07:00"), dir: t.TempDir(), logs: &bytes.Buffer{}}
	bad := []byte(`{"version":2}`)
	if err := os.WriteFile(filepath.Join(h.dir, screenerFile), bad, 0o600); err != nil {
		t.Fatal(err)
	}
	h.start()
	got := h.expect("GET", "/v1/stocks/screener/settings", "", 200, "")
	if got["min_value_bn"] != 5.0 {
		t.Fatalf("defaults = %v", got)
	}
	b, _ := json.Marshal(DefaultScreenerSettings())
	h.expect("PUT", "/v1/stocks/screener/settings", string(b), 500, "state_error")
	if data, _ := os.ReadFile(filepath.Join(h.dir, screenerFile)); !bytes.Equal(data, bad) {
		t.Fatalf("file touched: %s", data)
	}
	if !strings.Contains(h.logs.String(), "screener file unusable") {
		t.Fatalf("not logged: %s", h.logs.String())
	}
}

// expectRec checks only the status and returns the body and the recorder (headers).
func (h *harness) expectRec(method, path string, status int) (map[string]any, *httptest.ResponseRecorder) {
	h.t.Helper()
	code, got, rec := h.do(method, path, "")
	if code != status {
		h.t.Fatalf("%s %s = %d %s, want %d", method, path, code, rec.Body.String(), status)
	}
	return got, rec
}
