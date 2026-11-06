package stocks

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

// fakeClock is a settable clock.
type fakeClock struct {
	mu sync.Mutex
	t  time.Time
}

func newClock(rfc3339 string) *fakeClock {
	t, err := time.Parse(time.RFC3339, rfc3339)
	if err != nil {
		panic(err)
	}
	return &fakeClock{t: t}
}

func (c *fakeClock) Now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.t
}

func (c *fakeClock) Add(d time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.t = c.t.Add(d)
}

// fakeProvider answers from maps; err, when set, fails every call.
type fakeProvider struct {
	mu          sync.Mutex
	quotes      map[string]Quote
	charts      map[string]Chart
	rangeCharts map[string]Chart // "CODE|range", checked before charts
	search      []SearchResult
	err         error
	quoteCalls  int
	chartCalls  int
	searchCalls int
	periods     map[string][]Bar // ChartPeriod: "CODE|interval" → bars, served filtered to [from, to)
	periodErrs  map[string]error // ChartPeriod: "CODE|interval" → its error
	periodCalls []string         // ChartPeriod: "CODE|interval|from|to" (WIB dates)
	// period5mFloor, when set: a 5-minute window starting before it answers 422, like Yahoo past its ~60-day range.
	period5mFloor time.Time
}

func (f *fakeProvider) ChartPeriod(_ context.Context, code string, from, to time.Time, interval string) (Chart, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	key := code + "|" + interval
	f.periodCalls = append(f.periodCalls, key+"|"+from.In(WIB).Format(time.DateOnly)+"|"+to.In(WIB).Format(time.DateOnly))
	if f.err != nil {
		return Chart{}, f.err
	}
	if err := f.periodErrs[key]; err != nil {
		return Chart{}, err
	}
	if interval == Interval5m && from.Before(f.period5mFloor) {
		return Chart{}, &UpstreamError{Reason: "http 422", Status: 422}
	}
	bars, ok := f.periods[key]
	if !ok {
		return Chart{}, ErrUnknownSymbol
	}
	out := []Bar{}
	for _, b := range bars {
		if b.T >= from.Unix() && b.T < to.Unix() {
			out = append(out, b)
		}
	}
	return Chart{Code: code, Range: PeriodRange, Interval: interval, TZ: "Asia/Jakarta", Bars: out}, nil
}

func (f *fakeProvider) takePeriodCalls() []string {
	f.mu.Lock()
	defer f.mu.Unlock()
	out := f.periodCalls
	f.periodCalls = nil
	return out
}

func (f *fakeProvider) setErr(err error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.err = err
}

func (f *fakeProvider) calls() (q, c, s int) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.quoteCalls, f.chartCalls, f.searchCalls
}

func (f *fakeProvider) Quotes(_ context.Context, codes []string) (map[string]Quote, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.quoteCalls++
	if f.err != nil {
		return nil, f.err
	}
	out := map[string]Quote{}
	for _, c := range codes {
		if q, ok := f.quotes[c]; ok {
			out[c] = q
		}
	}
	return out, nil
}

func (f *fakeProvider) Chart(_ context.Context, code, rng string) (Chart, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.chartCalls++
	if f.err != nil {
		return Chart{}, f.err
	}
	ch, ok := f.rangeCharts[code+"|"+rng]
	if !ok {
		ch, ok = f.charts[code]
	}
	if !ok {
		return Chart{}, ErrUnknownSymbol
	}
	ch.Range, ch.Interval = rng, ChartRanges[rng]
	return ch, nil
}

func (f *fakeProvider) Search(context.Context, string) ([]SearchResult, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.searchCalls++
	if f.err != nil {
		return nil, f.err
	}
	return f.search, nil
}

func i64(v int64) *int64           { return &v }
func f64(v float64) *float64       { return &v }
func strp(v string) *string        { return &v }
func ptrEq(p *int64, v int64) bool { return p != nil && *p == v }

func bbcaQuote() Quote {
	return Quote{Code: "BBCA", Name: "Bank Central Asia Tbk", Price: 4930, PrevClose: i64(4840), Change: i64(90),
		ChangePct: f64(1.86), High: i64(4980), Low: i64(4830), Volume: i64(269099800), Spark: []int64{4860, 4930}}
}

func newFakeProvider() *fakeProvider {
	return &fakeProvider{
		quotes: map[string]Quote{"BBCA": bbcaQuote(),
			"ANTM": {Code: "ANTM", Price: 3000, PrevClose: i64(3100), Change: i64(-100), ChangePct: f64(-3.23)}},
		charts: map[string]Chart{
			"BBCA": {Code: "BBCA", TZ: "Asia/Jakarta", PrevClose: i64(4840), Bars: []Bar{{T: 1, O: 1, H: 2, L: 1, C: 2, V: 3}}, Name: "Bank Central Asia Tbk"},
			"XYZA": {Code: "XYZA", TZ: "Asia/Jakarta", Bars: []Bar{}, Name: "Xyz Abadi Tbk"},
		},
		search: []SearchResult{{Code: "BCAP", Name: "MNC Kapital Indonesia Tbk"}, {Code: "BBCA", Name: "yahoo name"}},
	}
}

func readFixture(t *testing.T, name string) []byte {
	t.Helper()
	b, err := os.ReadFile(filepath.Join("testdata", name))
	if err != nil {
		t.Fatal(err)
	}
	return b
}

// fixtureFeeds serves the three trimmed TS0 feeds; failing names a source that answers 503.
func fixtureFetch(t *testing.T, failing string, count *int, mu *sync.Mutex) func(context.Context, string) ([]byte, error) {
	files := map[string]string{DefaultFeeds[0].URL: "rss_cnbc.xml", DefaultFeeds[1].URL: "rss_kontan.xml", DefaultFeeds[2].URL: "rss_idxchannel.xml"}
	return func(_ context.Context, u string) ([]byte, error) {
		if mu != nil {
			mu.Lock()
			*count++
			mu.Unlock()
		}
		if failing != "" && strings.Contains(u, failing) {
			return nil, &UpstreamError{Reason: "http 503"}
		}
		return readFixture(t, files[u]), nil
	}
}

type harness struct {
	t       *testing.T
	svc     *Service
	prov    *fakeProvider
	clock   *fakeClock
	dir     string
	logs    *bytes.Buffer
	analyst Analyst // nil: analyzer off
}

// newHarness builds a Service on a temp state dir at Thu 2026-11-05 10:00 WIB (session 1).
func newHarness(t *testing.T) *harness {
	t.Helper()
	h := &harness{t: t, prov: newFakeProvider(), clock: newClock("2026-11-05T10:00:00+07:00"), dir: t.TempDir(), logs: &bytes.Buffer{}}
	h.start()
	return h
}

func (h *harness) start() {
	h.svc = New(Options{StateDir: h.dir, Provider: h.prov, Now: h.clock.Now, FetchFeed: fixtureFetch(h.t, "", nil, nil),
		Log: slog.New(slog.NewTextHandler(h.logs, &slog.HandlerOptions{Level: slog.LevelDebug,
			ReplaceAttr: func(_ []string, a slog.Attr) slog.Attr {
				if a.Key == slog.TimeKey {
					return slog.Attr{} // no wall-clock digits in the log under test
				}
				return a
			}})),
		NewsAfter: func(time.Duration) <-chan time.Time { return nil }, Analyst: h.analyst,
		SweepAfter: func(time.Duration) <-chan time.Time { return nil }})
	h.t.Cleanup(h.svc.Close)
}

func (h *harness) do(method, path, body string) (int, map[string]any, *httptest.ResponseRecorder) {
	h.t.Helper()
	var r io.Reader
	if body != "" {
		r = strings.NewReader(body)
	}
	rec := httptest.NewRecorder()
	h.svc.ServeHTTP(rec, httptest.NewRequest(method, path, r))
	if rec.Header().Get("Cache-Control") != "no-store" || rec.Header().Get("Content-Type") != "application/json" {
		h.t.Errorf("%s %s: headers %v", method, path, rec.Header())
	}
	var got map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		h.t.Fatalf("%s %s: body %q: %v", method, path, rec.Body.String(), err)
	}
	return rec.Code, got, rec
}

// expect checks the status and, for an error, the error code.
func (h *harness) expect(method, path, body string, status int, errCode string) map[string]any {
	h.t.Helper()
	code, got, rec := h.do(method, path, body)
	if code != status {
		h.t.Fatalf("%s %s = %d %s, want %d", method, path, code, rec.Body.String(), status)
	}
	if errCode != "" && got["error"] != errCode {
		h.t.Fatalf("%s %s error = %v, want %s", method, path, got["error"], errCode)
	}
	return got
}

func codesOf(t *testing.T, list any) []string {
	t.Helper()
	var out []string
	for _, it := range list.([]any) {
		out = append(out, it.(map[string]any)["code"].(string))
	}
	return out
}
