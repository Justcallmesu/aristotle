package stocks

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// BrowserUA is sent on every outbound request: Yahoo answers 429 to Go's and curl's default
// User-Agents (fingerprint, not volume; TS0 §0).
const BrowserUA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"

const (
	yahooBase     = "https://query1.finance.yahoo.com"
	sparkBatch    = 20 // v7/finance/spark refuses more symbols per call (400)
	maxBodyBytes  = 8 << 20
	clientTimeout = 15 * time.Second
)

// newHTTPClient is the outbound client for Yahoo and the RSS feeds.
func newHTTPClient() *http.Client { return &http.Client{Timeout: clientTimeout} }

// Yahoo is the Provider over query1.finance.yahoo.com: quotes from v7/finance/spark (no crumb),
// bars from v8/finance/chart, search from v1/finance/search filtered to IDX.
type Yahoo struct {
	Client *http.Client // nil: a 15 s client
	Base   string       // "": query1; tests fake the transport
}

func (y *Yahoo) base() string {
	if y.Base != "" {
		return y.Base
	}
	return yahooBase
}

func (y *Yahoo) client() *http.Client {
	if y.Client != nil {
		return y.Client
	}
	return newHTTPClient()
}

// get fetches base+path?q. 429 → BlockedError; 404 → ErrUnknownSymbol; other non-200 → UpstreamError.
func (y *Yahoo) get(ctx context.Context, path string, q url.Values) ([]byte, error) {
	return fetch(ctx, y.client(), y.base()+path+"?"+q.Encode(), "application/json")
}

func fetch(ctx context.Context, c *http.Client, u, accept string) ([]byte, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return nil, &UpstreamError{Reason: "bad request"}
	}
	req.Header.Set("User-Agent", BrowserUA)
	req.Header.Set("Accept", accept)
	resp, err := c.Do(req)
	if err != nil {
		return nil, &UpstreamError{Reason: "network"}
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, maxBodyBytes))
	switch {
	case resp.StatusCode == http.StatusTooManyRequests:
		return nil, &BlockedError{}
	case resp.StatusCode == http.StatusNotFound:
		return nil, ErrUnknownSymbol
	case resp.StatusCode != http.StatusOK:
		return nil, &UpstreamError{Reason: fmt.Sprintf("http %d", resp.StatusCode), Status: resp.StatusCode}
	case err != nil:
		return nil, &UpstreamError{Reason: "read body"}
	}
	return body, nil
}

type yMeta struct {
	Symbol               string   `json:"symbol"`
	RegularMarketPrice   *float64 `json:"regularMarketPrice"`
	PreviousClose        *float64 `json:"previousClose"`
	ChartPreviousClose   *float64 `json:"chartPreviousClose"`
	RegularMarketDayHigh *float64 `json:"regularMarketDayHigh"`
	RegularMarketDayLow  *float64 `json:"regularMarketDayLow"`
	RegularMarketVolume  *float64 `json:"regularMarketVolume"`
	RegularMarketTime    *int64   `json:"regularMarketTime"`
	FiftyTwoWeekHigh     *float64 `json:"fiftyTwoWeekHigh"`
	FiftyTwoWeekLow      *float64 `json:"fiftyTwoWeekLow"`
	LongName             string   `json:"longName"`
	ShortName            string   `json:"shortName"`
}

type ySeries struct {
	Open   []*float64 `json:"open"`
	High   []*float64 `json:"high"`
	Low    []*float64 `json:"low"`
	Close  []*float64 `json:"close"`
	Volume []*float64 `json:"volume"`
}

type yResult struct {
	Meta       yMeta   `json:"meta"`
	Timestamp  []int64 `json:"timestamp"`
	Indicators struct {
		Quote []ySeries `json:"quote"`
	} `json:"indicators"`
}

func (r yResult) series() ySeries {
	if len(r.Indicators.Quote) == 0 {
		return ySeries{}
	}
	return r.Indicators.Quote[0]
}

type ySpark struct {
	Spark struct {
		Result []struct {
			Symbol   string    `json:"symbol"`
			Response []yResult `json:"response"`
		} `json:"result"`
	} `json:"spark"`
}

type yChart struct {
	Chart struct {
		Result []yResult `json:"result"`
	} `json:"chart"`
}

type ySearch struct {
	Quotes []struct {
		Symbol    string `json:"symbol"`
		Exchange  string `json:"exchange"`
		QuoteType string `json:"quoteType"`
		ShortName string `json:"shortname"`
		LongName  string `json:"longname"`
	} `json:"quotes"`
}

func symbol(code string) string { return code + ".JK" }

// Quotes calls spark in batches of 20 and keys the answers by symbol (spark answers in its own order).
func (y *Yahoo) Quotes(ctx context.Context, codes []string) (map[string]Quote, error) {
	out := map[string]Quote{}
	for start := 0; start < len(codes); start += sparkBatch {
		batch := codes[start:min(start+sparkBatch, len(codes))]
		syms := make([]string, len(batch))
		for i, c := range batch {
			syms[i] = symbol(c)
		}
		body, err := y.get(ctx, "/v7/finance/spark", url.Values{"symbols": {strings.Join(syms, ",")}, "range": {"1d"}, "interval": {"5m"}})
		if errors.Is(err, ErrUnknownSymbol) {
			continue
		}
		if err != nil {
			return out, err
		}
		if err := parseSpark(body, out); err != nil {
			return out, err
		}
	}
	return out, nil
}

func parseSpark(body []byte, out map[string]Quote) error {
	var doc ySpark
	if err := json.Unmarshal(body, &doc); err != nil {
		return &UpstreamError{Reason: "bad spark body"}
	}
	for _, r := range doc.Spark.Result {
		code, ok := strings.CutSuffix(r.Symbol, ".JK")
		if !ok || len(r.Response) == 0 {
			continue
		}
		if q, ok := quoteFrom(code, r.Response[0]); ok {
			out[code] = q
		}
	}
	return nil
}

func quoteFrom(code string, r yResult) (Quote, bool) {
	m := r.Meta
	if m.RegularMarketPrice == nil || *m.RegularMarketPrice <= 0 {
		return Quote{}, false
	}
	q := Quote{Code: code, Name: metaName(m), Price: idr(*m.RegularMarketPrice), PrevClose: prevClose(m, "1d"),
		High: idrPtr(m.RegularMarketDayHigh), Low: idrPtr(m.RegularMarketDayLow), Volume: idrPtr(m.RegularMarketVolume),
		Week52High: idrPtr(m.FiftyTwoWeekHigh), Week52Low: idrPtr(m.FiftyTwoWeekLow), Spark: []int64{}}
	if q.PrevClose != nil && *q.PrevClose > 0 {
		ch := q.Price - *q.PrevClose
		pct := Pct(ch, *q.PrevClose)
		q.Change, q.ChangePct = &ch, &pct
	}
	if m.RegularMarketTime != nil {
		q.MarketTime = rfc(time.Unix(*m.RegularMarketTime, 0))
	}
	for _, c := range r.series().Close {
		if c != nil {
			q.Spark = append(q.Spark, idr(*c))
		}
	}
	return q, true
}

// prevClose: meta.previousClose; chartPreviousClose (the close before the range) only for range 1d.
func prevClose(m yMeta, rng string) *int64 {
	if m.PreviousClose != nil {
		return idrPtr(m.PreviousClose)
	}
	if rng == "1d" {
		return idrPtr(m.ChartPreviousClose)
	}
	return nil
}

func metaName(m yMeta) string {
	n := m.ShortName
	if n == "" {
		n = m.LongName
	}
	return strings.TrimPrefix(n, "PT ")
}

func idr(v float64) int64 { return int64(math.Round(v)) }

func idrPtr(v *float64) *int64 {
	if v == nil {
		return nil
	}
	n := idr(*v)
	return &n
}

// openRange is an internal chart range (never served by GET /v1/stocks/chart): Yahoo range 5d at a daily
// interval. Its last bar's open is the IDX open of the day (the pre-opening auction price), which the 5-minute
// series and spark do not carry (TS4 §0).
const openRange = "5d:1d"

// chartParams maps a range to Yahoo's range and interval.
func chartParams(rng string) (yRange, interval string, ok bool) {
	if rng == openRange {
		return "5d", "1d", true
	}
	interval, ok = ChartRanges[rng]
	return rng, interval, ok
}

// Chart calls v8/finance/chart for one range.
func (y *Yahoo) Chart(ctx context.Context, code, rng string) (Chart, error) {
	yr, interval, ok := chartParams(rng)
	if !ok {
		return Chart{}, &UpstreamError{Reason: "bad range"}
	}
	body, err := y.get(ctx, "/v8/finance/chart/"+url.PathEscape(symbol(code)), url.Values{"interval": {interval}, "range": {yr}})
	if err != nil {
		return Chart{}, err
	}
	return parseChart(body, code, rng)
}

func parseChart(body []byte, code, rng string) (Chart, error) {
	var doc yChart
	if err := json.Unmarshal(body, &doc); err != nil {
		return Chart{}, &UpstreamError{Reason: "bad chart body"}
	}
	if len(doc.Chart.Result) == 0 {
		return Chart{}, ErrUnknownSymbol
	}
	r := doc.Chart.Result[0]
	_, interval, _ := chartParams(rng)
	ch := Chart{Code: code, Range: rng, Interval: interval, TZ: "Asia/Jakarta", PrevClose: prevClose(r.Meta, rng),
		Bars: bars(r, interval == "1d"), Name: metaName(r.Meta)}
	return ch, nil
}

// bars drops bars missing a price (the all-null lunch-break bars among them); on daily bars the
// last bar's null close (today, after the session) is first filled from regularMarketPrice.
func bars(r yResult, daily bool) []Bar {
	s := r.series()
	at := func(xs []*float64, i int) *float64 {
		if i < len(xs) {
			return xs[i]
		}
		return nil
	}
	out := []Bar{}
	for i, t := range r.Timestamp {
		o, h, l, c, v := at(s.Open, i), at(s.High, i), at(s.Low, i), at(s.Close, i), at(s.Volume, i)
		if c == nil && daily && i == len(r.Timestamp)-1 {
			c = r.Meta.RegularMarketPrice
		}
		if o == nil || h == nil || l == nil || c == nil {
			continue
		}
		b := Bar{T: t, O: idr(*o), H: idr(*h), L: idr(*l), C: idr(*c)}
		if v != nil {
			b.V = idr(*v)
		}
		out = append(out, b)
	}
	return out
}

// Search calls v1/finance/search and keeps IDX equities (exchange JKT, CODE.JK).
func (y *Yahoo) Search(ctx context.Context, q string) ([]SearchResult, error) {
	body, err := y.get(ctx, "/v1/finance/search", url.Values{"q": {q}, "quotesCount": {"10"}, "newsCount": {"0"}})
	if errors.Is(err, ErrUnknownSymbol) {
		return []SearchResult{}, nil
	}
	if err != nil {
		return nil, err
	}
	var doc ySearch
	if err := json.Unmarshal(body, &doc); err != nil {
		return nil, &UpstreamError{Reason: "bad search body"}
	}
	out := []SearchResult{}
	for _, h := range doc.Quotes {
		code, ok := strings.CutSuffix(h.Symbol, ".JK")
		if !ok || h.Exchange != "JKT" || !codeRe.MatchString(code) || (h.QuoteType != "" && h.QuoteType != "EQUITY") {
			continue
		}
		name := h.LongName
		if name == "" {
			name = h.ShortName
		}
		out = append(out, SearchResult{Code: code, Name: strings.TrimPrefix(name, "PT ")})
	}
	return out, nil
}
