package stocks

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"log/slog"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/justcallmesu/aristotle/server/internal/hermes"
)

// TM3s: server minors from the TS1 and TA2 reviews.

func TestNewsCloseKeepsPollerStopped(t *testing.T) {
	var mu sync.Mutex
	fetches := 0
	n := &News{Feeds: DefaultFeeds, Fetch: fixtureFetch(t, "", &fetches, &mu), Now: newClock("2026-11-05T10:00:00+07:00").Now,
		After: func(time.Duration) <-chan time.Time { return nil }}
	n.Items(context.Background())
	n.Close()
	waitFor(t, func() bool { return !n.Polling() })
	n.Items(context.Background()) // a request still in flight at shutdown
	if n.Polling() {
		t.Fatal("poller restarted after Close")
	}
}

func TestNewsImageURLNeedsHTTP(t *testing.T) {
	f := DefaultFeeds[0]
	for img, want := range map[string]string{
		"https://x/i.jpg": "https://x/i.jpg", "http://x/i.jpg": "http://x/i.jpg",
		"javascript:alert(1)": "", "data:image/png;base64,AAAA": "", "//x/i.jpg": "", "file:///etc/passwd": "",
	} {
		it := rssItem{Title: "T", Link: "https://x/a", PubDate: "Wed, 04 Nov 2026 10:00:00 +0700"}
		it.Enclosure.URL, it.Enclosure.Type = img, "image/jpeg"
		a, ok := articleFrom(f, it)
		if !ok || a.ImageURL != want {
			t.Errorf("enclosure %q -> %q (ok %v), want %q", img, a.ImageURL, ok, want)
		}
		it.Enclosure.URL = ""
		it.Description = `<img src="` + img + `"> text`
		if a, _ := articleFrom(f, it); a.ImageURL != want {
			t.Errorf("description img %q -> %q, want %q", img, a.ImageURL, want)
		}
	}
}

// A 5xx backs off like a 429; a 4xx other than 429 and a network failure do not.
func TestMarketBacksOffOnUpstream5xx(t *testing.T) {
	clk := newClock("2026-11-05T10:00:00+07:00")
	prov := newFakeProvider()
	m := &Market{Src: prov, Now: clk.Now}
	ctx := context.Background()
	prov.setErr(&UpstreamError{Reason: "http 503", Status: 503})
	res := m.Quotes(ctx, []string{"BBCA"})
	var be *BlockedError
	if !errors.As(res.Errors["BBCA"], &be) || be.RetryAfter != time.Minute || m.blocked() != time.Minute {
		t.Fatalf("after 503: %v, blocked %s", res.Errors, m.blocked())
	}
	m.Quotes(ctx, []string{"BBCA"})
	if q, _, _ := prov.calls(); q != 1 {
		t.Fatalf("calls during backoff = %d", q)
	}
	clk.Add(61 * time.Second)
	m.Quotes(ctx, []string{"BBCA"})
	if m.blocked() != 2*time.Minute {
		t.Fatalf("second outage backoff = %s", m.blocked())
	}
	for _, err := range []error{&UpstreamError{Reason: "http 403", Status: 403}, &UpstreamError{Reason: "network"}} {
		m2 := &Market{Src: prov, Now: clk.Now}
		prov.setErr(err)
		m2.Quotes(ctx, []string{"BBCA"})
		if m2.blocked() != 0 {
			t.Errorf("%v caused a backoff", err)
		}
	}
}

func TestMarketChartCacheEvictsLeastRecentlyUsed(t *testing.T) {
	prov := newFakeProvider()
	for i := range maxCachedCharts + 20 {
		c := code4(i)
		prov.charts[c] = Chart{Code: c, Bars: []Bar{}}
	}
	m := &Market{Src: prov, Now: newClock("2026-11-05T10:00:00+07:00").Now}
	ctx := context.Background()
	for i := range maxCachedCharts {
		if _, err := m.Chart(ctx, code4(i), "1d"); err != nil {
			t.Fatal(err)
		}
	}
	m.Chart(ctx, code4(0), "1d") // used again: code4(1) is now the oldest
	for i := maxCachedCharts; i < maxCachedCharts+20; i++ {
		m.Chart(ctx, code4(i), "1d")
	}
	if len(m.charts) != maxCachedCharts || len(m.chartUse) != maxCachedCharts {
		t.Fatalf("cache %d, order %d, cap %d", len(m.charts), len(m.chartUse), maxCachedCharts)
	}
	if _, ok := m.charts[code4(0)+"|1d"]; !ok {
		t.Error("recently used entry was evicted")
	}
	for _, i := range []int{1, 20} {
		if _, ok := m.charts[code4(i)+"|1d"]; ok {
			t.Errorf("%s survived", code4(i))
		}
	}
	if _, ok := m.charts[code4(21)+"|1d"]; !ok {
		t.Error("entry 21 evicted too early")
	}
}

// A name that fell back to the bare code must not match case-insensitively (CASH vs "cash").
func TestNameFallbackKeepsWordCodeRule(t *testing.T) {
	cash := Candidate{Code: "CASH", Name: "CASH"}
	if m := MatchArticle("Cash flow perusahaan naik", "uang cash tunai", []Candidate{cash}); len(m.Codes) != 0 {
		t.Fatalf("fallback name weak-matched: %+v", m)
	}
	if m := MatchArticle("Asing borong saham CASH", "", []Candidate{cash}); len(m.Codes) != 1 || m.Strength != MatchStrong {
		t.Fatalf("strong form lost: %+v", m)
	}
}

func TestEvaluateZeroCostDoesNotPanic(t *testing.T) {
	p := Evaluate(Holding{Lots: 0, AvgPrice: 0}, i64(100), Settings{})
	if p.Cost != 0 || p.PLPct == nil || *p.PLPct != 0 {
		t.Fatalf("zero cost = %+v", p)
	}
}

func TestCreateWithUnusableAnswerLogsHonestly(t *testing.T) {
	fa := &fakeAnalyst{openErr: fmt.Errorf("%w: session.create", hermes.ErrBadAnswer)}
	h := analyzerHarness(t, fa)
	h.expect("POST", "/v1/stocks/analyze/TSTA", `{"lang":"en"}`, 202, "")
	got := h.waitState("TSTA")
	if got["error"] != "hermes_refused" {
		t.Fatalf("GET = %v", got)
	}
	logs := h.logs.String()
	if !strings.Contains(logs, "unusable answer to the session create") || strings.Contains(logs, "stopped while") {
		t.Fatalf("log = %s", logs)
	}
}

// A delete that times out is skipped and the pass goes on (a timeout is not "Hermes unreachable").
func TestSweepSkipsTimedOutDelete(t *testing.T) {
	dir := t.TempDir()
	st := emptyAnalyses()
	st.Failed = []failedSession{
		{SessionID: "local-a", FailedAt: "2026-10-20T09:00:00+07:00"},
		{SessionID: "local-slow", FailedAt: "2026-10-21T09:00:00+07:00"},
		{SessionID: "local-b", FailedAt: "2026-10-22T09:00:00+07:00"},
	}
	writeAnalyses(t, dir, st)
	fa := &fakeAnalyst{slowSweep: "local-slow"}
	logs := &bytes.Buffer{}
	parked := make(chan struct{}, 2)
	sweepDeleteTimeout = 50 * time.Millisecond
	defer func() { sweepDeleteTimeout = analysisCleanup }()
	svc := New(Options{StateDir: dir, Provider: newFakeProvider(), Now: newClock("2026-11-05T10:00:00+07:00").Now, Analyst: fa,
		Log: slog.New(slog.NewTextHandler(logs, nil)), FetchFeed: fixtureFetch(t, "", nil, nil),
		NewsAfter:  func(time.Duration) <-chan time.Time { return nil },
		SweepAfter: func(time.Duration) <-chan time.Time { parked <- struct{}{}; return nil }})
	defer svc.Close()
	<-parked
	got, _ := loadAnalyses(dir)
	_, _, swept, _ := fa.snapshot()
	if fmt.Sprint(got.Failed) != "[{local-slow 2026-10-21T09:00:00+07:00}]" || fmt.Sprint(swept) != "[local-a local-slow local-b]" {
		t.Fatalf("failed %v swept %v", got.Failed, swept)
	}
	if !strings.Contains(logs.String(), "retried tomorrow\" count=1") {
		t.Fatalf("logs: %s", logs)
	}
}
