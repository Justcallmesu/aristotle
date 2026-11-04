package stocks

import (
	"bytes"
	"context"
	"errors"
	"io"
	"net/http"
	"strings"
	"sync"
	"testing"
)

// fakeTransport answers Yahoo URLs from fixtures; no socket is opened.
type fakeTransport struct {
	mu     sync.Mutex
	status int
	body   func(*http.Request) []byte
	reqs   []*http.Request
}

func (f *fakeTransport) RoundTrip(r *http.Request) (*http.Response, error) {
	f.mu.Lock()
	f.reqs = append(f.reqs, r)
	f.mu.Unlock()
	status := f.status
	if status == 0 {
		status = http.StatusOK
	}
	return &http.Response{StatusCode: status, Body: io.NopCloser(bytes.NewReader(f.body(r))), Header: http.Header{}, Request: r}, nil
}

func yahooWith(status int, body func(*http.Request) []byte) (*Yahoo, *fakeTransport) {
	ft := &fakeTransport{status: status, body: body}
	return &Yahoo{Client: &http.Client{Transport: ft}}, ft
}

func fixed(b []byte) func(*http.Request) []byte { return func(*http.Request) []byte { return b } }

func TestYahooQuotesFromSpark(t *testing.T) {
	y, ft := yahooWith(200, fixed(readFixture(t, "spark.json")))
	got, err := y.Quotes(context.Background(), []string{"BBCA", "BBRI", "TLKM", "ADRO"})
	if err != nil || len(got) != 4 {
		t.Fatalf("quotes = %d, %v", len(got), err)
	}
	q := got["BBCA"]
	if q.Price != 4930 || !ptrEq(q.PrevClose, 4840) || !ptrEq(q.Change, 90) || *q.ChangePct != 1.86 ||
		!ptrEq(q.High, 4980) || !ptrEq(q.Low, 4830) || !ptrEq(q.Volume, 269099800) || len(q.Spark) != 46 ||
		str(q.MarketTime) != "2026-11-04T16:14:44+07:00" || q.Name != "Bank Central Asia Tbk" {
		t.Fatalf("BBCA = %+v (market_time %s)", q, str(q.MarketTime))
	}
	if r := got["BBRI"]; r.Price != 2690 || !ptrEq(r.PrevClose, 2660) {
		t.Fatalf("BBRI keyed by symbol = %+v", r)
	}
	req := ft.reqs[0]
	if req.Header.Get("User-Agent") != BrowserUA || req.URL.Path != "/v7/finance/spark" || req.URL.Host != "query1.finance.yahoo.com" ||
		req.URL.Query().Get("symbols") != "BBCA.JK,BBRI.JK,TLKM.JK,ADRO.JK" || req.URL.Query().Get("range") != "1d" {
		t.Fatalf("request = %s UA %q", req.URL, req.Header.Get("User-Agent"))
	}
}

func TestYahooQuotesBatchesOf20(t *testing.T) {
	y, ft := yahooWith(200, fixed([]byte(`{"spark":{"result":[]}}`)))
	codes := make([]string, 45)
	for i := range codes {
		codes[i] = code4(i)
	}
	if _, err := y.Quotes(context.Background(), codes); err != nil {
		t.Fatal(err)
	}
	var sizes []int
	for _, r := range ft.reqs {
		sizes = append(sizes, len(strings.Split(r.URL.Query().Get("symbols"), ",")))
	}
	if len(sizes) != 3 || sizes[0] != 20 || sizes[1] != 20 || sizes[2] != 5 {
		t.Fatalf("batches = %v", sizes)
	}
}

func TestYahooChartDropsNullBars(t *testing.T) {
	y, ft := yahooWith(200, fixed(readFixture(t, "chart_15m.json")))
	ch, err := y.Chart(context.Background(), "BBCA", "5d")
	if err != nil {
		t.Fatal(err)
	}
	// 40 bars in the fixture, 6 all-null lunch-break bars (12:00-13:15).
	if len(ch.Bars) != 34 || ch.Interval != "15m" || ch.TZ != "Asia/Jakarta" || !ptrEq(ch.PrevClose, 4840) {
		t.Fatalf("chart = %d bars, %s, prev %v", len(ch.Bars), ch.Interval, deref(ch.PrevClose))
	}
	last := ch.Bars[len(ch.Bars)-1]
	if last != (Bar{T: 1793782800, O: 4930, H: 4930, L: 4930, C: 4930, V: 25673700}) {
		t.Fatalf("last bar = %+v", last)
	}
	if q := ft.reqs[0].URL; q.Path != "/v8/finance/chart/BBCA.JK" || q.Query().Get("interval") != "15m" || q.Query().Get("range") != "5d" {
		t.Fatalf("url = %s", q)
	}
}

func TestYahooDailyChartFillsTodaysClose(t *testing.T) {
	y, _ := yahooWith(200, fixed(readFixture(t, "chart_1d.json")))
	ch, err := y.Chart(context.Background(), "BBCA", "5y")
	if err != nil {
		t.Fatal(err)
	}
	last := ch.Bars[len(ch.Bars)-1]
	if len(ch.Bars) != 8 || last.C != 4930 || last.O != 4840 || last.H != 4980 || last.L != 4830 {
		t.Fatalf("bars = %d, last %+v", len(ch.Bars), last)
	}
	// previousClose is null on 1d/5y and chartPreviousClose (7375) is the close before the range: not used.
	if ch.PrevClose != nil {
		t.Fatalf("prev_close = %d, want null", *ch.PrevClose)
	}
}

func TestPrevCloseRule(t *testing.T) {
	m := yMeta{ChartPreviousClose: f64(6100)}
	if prevClose(m, "1d") == nil || *prevClose(m, "1d") != 6100 || prevClose(m, "1mo") != nil {
		t.Fatal("chartPreviousClose must count only for range 1d")
	}
	m.PreviousClose = f64(4840)
	if !ptrEq(prevClose(m, "1mo"), 4840) {
		t.Fatal("previousClose wins")
	}
}

func TestYahooErrors(t *testing.T) {
	tests := []struct {
		name   string
		status int
		body   string
		check  func(error) bool
	}{
		{"404 unknown", 404, string(readFixture(t, "chart_404.json")), func(err error) bool { return errors.Is(err, ErrUnknownSymbol) }},
		{"429 blocked", 429, "Edge: Too Many Requests", func(err error) bool { var b *BlockedError; return errors.As(err, &b) }},
		{"500 upstream", 500, "", func(err error) bool { var u *UpstreamError; return errors.As(err, &u) && u.Reason == "http 500" }},
		{"bad body", 200, "<html>", func(err error) bool { var u *UpstreamError; return errors.As(err, &u) }},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			y, _ := yahooWith(tt.status, fixed([]byte(tt.body)))
			if _, err := y.Chart(context.Background(), "ZZZQ", "1d"); !tt.check(err) {
				t.Fatalf("err = %v", err)
			}
		})
	}
}

func TestYahooSearchKeepsIDXOnly(t *testing.T) {
	y, ft := yahooWith(200, fixed(readFixture(t, "search_bca.json")))
	got, err := y.Search(context.Background(), "bca")
	if err != nil || len(got) != 1 || got[0].Code != "BCAP" || got[0].InUniverse {
		t.Fatalf("search = %+v, %v", got, err)
	}
	if q := ft.reqs[0].URL.Query(); q.Get("q") != "bca" || q.Get("newsCount") != "0" {
		t.Fatalf("query = %v", q)
	}
}
