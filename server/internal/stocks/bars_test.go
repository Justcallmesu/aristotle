package stocks

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

// scriptedProvider is fakeProvider with a call log; the blockAt-th chart call (1-based) answers a block.
type scriptedProvider struct {
	*fakeProvider
	mu      sync.Mutex
	calls   []string // since the last takeCalls
	total   int
	blockAt int // over all calls
}

func (p *scriptedProvider) Chart(ctx context.Context, code, rng string) (Chart, error) {
	p.mu.Lock()
	p.calls = append(p.calls, code+"|"+rng)
	p.total++
	n := p.total
	p.mu.Unlock()
	if n == p.blockAt {
		return Chart{}, &BlockedError{}
	}
	return p.fakeProvider.Chart(ctx, code, rng)
}

func (p *scriptedProvider) takeCalls() []string {
	p.mu.Lock()
	defer p.mu.Unlock()
	out := p.calls
	p.calls = nil
	return out
}

func dayBar(day string, c int64) Bar {
	return Bar{T: wib(day + " 09:00").Unix(), O: c, H: c + 10, L: c - 10, C: c, V: 1000}
}

type barsHarness struct {
	t      *testing.T
	dir    string
	clock  *fakeClock
	prov   *scriptedProvider
	svc    *Service
	logs   *bytes.Buffer
	paces  int
	paceMu sync.Mutex
}

// newBarsHarness: a Service whose quoted set is BBCA, TLKM (universe override) and ANTM (watchlist).
func newBarsHarness(t *testing.T, now string) *barsHarness {
	t.Helper()
	fp := newFakeProvider()
	bars := []Bar{dayBar("2025-09-01", 100), dayBar("2026-11-03", 4800), dayBar("2026-11-04", 4840), dayBar("2026-11-05", 4930),
		dayBar("2026-11-06", 4950), dayBar("2026-11-09", 4990)}
	for _, c := range []string{"BBCA", "TLKM", "ANTM"} {
		fp.charts[c] = Chart{Code: c, Name: c + " name", Bars: bars}
	}
	h := &barsHarness{t: t, dir: t.TempDir(), clock: newClock(now), prov: &scriptedProvider{fakeProvider: fp}, logs: &bytes.Buffer{}}
	h.open()
	h.put("/v1/stocks/universe", `{"lq45":[],"idx80":["TLKM","BBCA"],"valid_to":"2027-01-29"}`)
	h.put("/v1/stocks/watchlist/ANTM", "")
	return h
}

func (h *barsHarness) open() {
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
}

func (h *barsHarness) put(path, body string) {
	h.t.Helper()
	hh := &harness{t: h.t, svc: h.svc}
	hh.expect("PUT", path, body, 200, "")
}

// runAt sets the clock and ticks the scheduler (bars.daily is due when now is its slot).
func (h *barsHarness) runAt(now string) {
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

func (h *barsHarness) job() JobView {
	for _, v := range h.svc.sched.views() {
		if v.ID == BarsJobID {
			return v
		}
	}
	h.t.Fatal("no bars job")
	return JobView{}
}

func barDays(bars []Bar) string {
	var out []string
	for _, b := range bars {
		out = append(out, time.Unix(b.T, 0).In(WIB).Format("01-02"))
	}
	return strings.Join(out, ",")
}

func TestBarsJobFillsStoreAndPrunes(t *testing.T) {
	h := newBarsHarness(t, "2026-11-05T16:00:00+07:00")
	h.svc.bars.put("ZZZZ", "gone", []Bar{dayBar("2026-11-04", 50)}, h.clock.Now()) // no longer in the set
	h.runAt("2026-11-05T16:20:05+07:00")
	if got := strings.Join(h.prov.takeCalls(), " "); got != "ANTM|1y BBCA|1y TLKM|1y" {
		t.Fatalf("calls = %s", got)
	}
	if h.paces != 2 {
		t.Fatalf("paces = %d, want one between each two calls", h.paces)
	}
	if v := h.job(); *v.LastStatus != JobStatusOK || v.LastError != nil || v.Late || v.Running {
		t.Fatalf("job = %+v", v)
	}
	sb, ok := h.svc.bars.get("BBCA")
	// Thu 16:20: Friday is the session date; the >400-day bar and the future bars are dropped, today's kept.
	if !ok || barDays(sb.bars) != "11-03,11-04,11-05" || sb.name != "BBCA name" || !sb.fetchedAt.Equal(h.clock.Now()) {
		t.Fatalf("BBCA = %+v", sb)
	}
	if _, ok := h.svc.bars.get("ZZZZ"); ok {
		t.Fatal("ZZZZ not pruned")
	}
	data, err := os.ReadFile(filepath.Join(h.dir, barsFile))
	if err != nil {
		t.Fatal(err)
	}
	var doc barsDoc
	if err := json.Unmarshal(data, &doc); err != nil || doc.Version != 1 || len(doc.Codes) != 3 ||
		doc.Codes["TLKM"].FetchedAt != "2026-11-05T16:20:05+07:00" || doc.Codes["TLKM"].Bars[0] != [6]int64{dayBar("2026-11-03", 0).T, 4800, 4810, 4790, 4800, 1000} {
		t.Fatalf("file = %s (%v)", data, err)
	}
	if fi, _ := os.Stat(filepath.Join(h.dir, barsFile)); fi.Mode().Perm() != 0o600 {
		t.Fatalf("mode = %v", fi.Mode())
	}

	// Loaded at the next start.
	h.svc.Close()
	h.open()
	if sb, ok := h.svc.bars.get("BBCA"); !ok || barDays(sb.bars) != "11-03,11-04,11-05" || h.svc.bars.count() != 3 {
		t.Fatalf("reloaded = %+v %d", sb, h.svc.bars.count())
	}

	// A catch-up during Monday's session keeps only completed sessions (Monday's partial bar is dropped).
	h.runAt("2026-11-09T10:00:00+07:00")
	if v := h.job(); !v.Late || *v.LastDue != "2026-11-06T16:20:00+07:00" {
		t.Fatalf("job = %+v", v)
	}
	if sb, _ := h.svc.bars.get("BBCA"); barDays(sb.bars) != "11-03,11-04,11-05,11-06" {
		t.Fatalf("BBCA after Monday catch-up = %s", barDays(sb.bars))
	}
}

func TestBarsJobStopsOnBlockAndResumesNextDay(t *testing.T) {
	h := newBarsHarness(t, "2026-11-05T16:00:00+07:00")
	h.svc.bars.put("ZZZZ", "", []Bar{dayBar("2026-11-04", 50)}, h.clock.Now())
	h.prov.blockAt = 2
	h.runAt("2026-11-05T16:20:05+07:00")
	if got := strings.Join(h.prov.takeCalls(), " "); got != "ANTM|1y BBCA|1y" {
		t.Fatalf("calls = %s (the run must stop at the block)", got)
	}
	if v := h.job(); *v.LastStatus != JobStatusPartial || *v.LastError != "upstream_blocked" {
		t.Fatalf("job = %+v", v)
	}
	if _, ok := h.svc.bars.get("ANTM"); !ok {
		t.Fatal("ANTM fetched before the block was not kept")
	}
	if _, ok := h.svc.bars.get("ZZZZ"); !ok {
		t.Fatal("a stopped run must not prune")
	}
	if _, err := os.Stat(filepath.Join(h.dir, barsFile)); err != nil {
		t.Fatalf("partial run not saved: %v", err)
	}
	// Next day: the codes the block left come first.
	h.runAt("2026-11-06T16:20:05+07:00")
	if got := strings.Join(h.prov.takeCalls(), " "); got != "BBCA|1y TLKM|1y ANTM|1y" {
		t.Fatalf("calls = %s", got)
	}
	if v := h.job(); *v.LastStatus != JobStatusOK {
		t.Fatalf("job = %+v", v)
	}
	if _, ok := h.svc.bars.get("ZZZZ"); ok {
		t.Fatal("ZZZZ not pruned by a full run")
	}
}

func TestBarsJobBlockedAtFirstCallAndFailedCodes(t *testing.T) {
	h := newBarsHarness(t, "2026-11-05T16:00:00+07:00")
	h.prov.blockAt = 1
	h.runAt("2026-11-05T16:20:05+07:00")
	if v := h.job(); *v.LastStatus != JobStatusFailed || *v.LastError != "upstream_blocked" {
		t.Fatalf("job = %+v", v)
	}
	// While the backoff lasts, nothing goes out.
	h.prov.takeCalls()
	h.runAt("2026-11-06T16:20:05+07:00")
	if calls := h.prov.takeCalls(); len(calls) != 3 {
		t.Fatalf("calls after the backoff = %v", calls)
	}
	h.prov.mu.Lock()
	delete(h.prov.charts, "TLKM") // unknown at the source
	h.prov.mu.Unlock()
	h.runAt("2026-11-09T16:20:05+07:00")
	if v := h.job(); *v.LastStatus != JobStatusPartial || *v.LastError != "codes_failed" {
		t.Fatalf("job = %+v", v)
	}
}

func TestBarStoreCorruptStartsEmpty(t *testing.T) {
	for name, body := range map[string]string{
		"json":    `{"version":1,"codes":{`,
		"version": `{"version":2,"codes":{}}`,
		"code":    `{"version":1,"codes":{"bbca":{"fetched_at":"2026-11-05T16:20:00+07:00","bars":[]}}}`,
		"order":   `{"version":1,"codes":{"BBCA":{"fetched_at":"2026-11-05T16:20:00+07:00","bars":[[2,1,1,1,1,1],[1,1,1,1,1,1]]}}}`,
	} {
		t.Run(name, func(t *testing.T) {
			dir := t.TempDir()
			if err := os.WriteFile(filepath.Join(dir, barsFile), []byte(body), 0o600); err != nil {
				t.Fatal(err)
			}
			logs := &bytes.Buffer{}
			b := loadBarStore(dir, slog.New(slog.NewTextHandler(logs, nil)))
			if b.count() != 0 || !strings.Contains(logs.String(), "bar store unusable") {
				t.Fatalf("count %d, logs %s", b.count(), logs)
			}
		})
	}
}

func TestBarStoreCaps(t *testing.T) {
	b := &barStore{codes: map[string]storedBars{}}
	now := wib("2026-11-05 16:20")
	var bars []Bar
	for i := range 400 {
		bars = append(bars, Bar{T: now.Add(time.Duration(i-399) * 24 * time.Hour).Unix(), C: 10})
	}
	bars = append(bars, Bar{T: now.Unix() + 1, C: 0}) // no price: dropped
	b.put("BBCA", "", bars, now)
	sb, _ := b.get("BBCA")
	if len(sb.bars) != maxBarsPerCode || sb.bars[len(sb.bars)-1].T != now.Unix() {
		t.Fatalf("kept %d bars", len(sb.bars))
	}
}

func TestBarsFresh(t *testing.T) {
	for _, tt := range []struct {
		fetched, now string
		want         bool
	}{
		{"2026-11-05 16:20", "2026-11-05 17:00", true},  // Thu evening: Friday's session needs Thursday's bar
		{"2026-11-05 16:20", "2026-11-06 10:00", true},  // during Friday
		{"2026-11-05 16:20", "2026-11-06 16:30", false}, // Friday closed: Friday's bar is missing
		{"2026-11-04 23:00", "2026-11-05 10:00", true},
		{"2026-11-05 16:10", "2026-11-05 17:00", false}, // too soon after the close
		{"2026-12-23 16:20", "2026-12-26 12:00", true},  // over the holidays and weekend
		{"2026-11-06 16:20", "2026-11-09 08:00", true},  // Monday before the open
	} {
		if got := barsFresh(wib(tt.fetched), wib(tt.now)); got != tt.want {
			t.Errorf("barsFresh(%s, %s) = %v", tt.fetched, tt.now, got)
		}
	}
	if barsFresh(time.Time{}, wib("2026-11-05 17:00")) {
		t.Error("never fetched is not fresh")
	}
}

// The analyzer's bars come from the store while it is fresh, from Yahoo otherwise.
func TestDailyBarsPrefersFreshStore(t *testing.T) {
	h := newBarsHarness(t, "2026-11-05T16:00:00+07:00")
	h.runAt("2026-11-05T16:20:05+07:00")
	h.prov.takeCalls()
	h.clock.Add(18 * time.Hour) // Fri 10:20
	bars, name, err := h.svc.dailyBars(context.Background(), "BBCA")
	if err != nil || barDays(bars) != "11-03,11-04,11-05" || name != "BBCA name" || len(h.prov.takeCalls()) != 0 {
		t.Fatalf("store read = %s %q %v", barDays(bars), name, err)
	}
	h.clock.Add(72 * time.Hour) // Mon 10:20: Friday's bar is not in the store
	if bars, _, err = h.svc.dailyBars(context.Background(), "BBCA"); err != nil || len(bars) != 6 ||
		strings.Join(h.prov.takeCalls(), " ") != "BBCA|1y" {
		t.Fatalf("stale store must go to Yahoo: %d bars, %v", len(bars), err)
	}
	if _, _, err := h.svc.dailyBars(context.Background(), "XXXX"); !errors.Is(err, ErrUnknownSymbol) {
		t.Fatalf("unknown = %v", err)
	}
}

func TestChartFreshSkipsCacheAndReportsBlock(t *testing.T) {
	fp := newFakeProvider()
	clock := newClock("2026-11-05T10:00:00+07:00")
	m := &Market{Src: fp, Now: clock.Now}
	if _, err := m.Chart(context.Background(), "BBCA", "1y"); err != nil {
		t.Fatal(err)
	}
	if _, err := m.ChartFresh(context.Background(), "BBCA", "1y"); err != nil {
		t.Fatal(err)
	}
	if _, c, _ := fp.calls(); c != 2 {
		t.Fatalf("chart calls = %d, want 2 (no cache read)", c)
	}
	fp.setErr(&BlockedError{})
	var be *BlockedError
	if _, err := m.ChartFresh(context.Background(), "BBCA", "1y"); !errors.As(err, &be) {
		t.Fatalf("blocked = %v", err)
	}
	if ch, err := m.Chart(context.Background(), "BBCA", "1y"); err != nil || ch.Stale {
		t.Fatalf("the cache still answers Chart: %+v %v", ch, err) // fresh entry from ChartFresh, within the TTL
	}
}
