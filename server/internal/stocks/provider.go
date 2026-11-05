package stocks

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"slices"
	"strings"
	"sync"
	"time"
)

// Provider is the price source seam (Yahoo in production, a fake in tests). It does no caching.
type Provider interface {
	// Quotes returns the latest quote of each code it knows; unknown codes are left out of the map.
	// On an error the map may still hold the codes answered before it.
	Quotes(ctx context.Context, codes []string) (map[string]Quote, error)
	// Chart returns the bars of code for a range (ChartRanges); ErrUnknownSymbol when the symbol does not exist.
	Chart(ctx context.Context, code, rng string) (Chart, error)
	// Search returns IDX stocks for a free-text query.
	Search(ctx context.Context, q string) ([]SearchResult, error)
}

// ErrUnknownSymbol: the source has no such stock (Yahoo chart 404).
var ErrUnknownSymbol = errors.New("unknown symbol")

// BlockedError: the source refused us (Yahoo 429). RetryAfter is set by Market, not the Provider.
type BlockedError struct{ RetryAfter time.Duration }

func (e *BlockedError) Error() string {
	return fmt.Sprintf("upstream blocked, retry in %s", e.RetryAfter)
}

// UpstreamError is any other failure of the source (HTTP status, network, bad body).
type UpstreamError struct {
	Reason string
	Status int // HTTP status when there was one
}

func (e *UpstreamError) Error() string { return "upstream error: " + e.Reason }

// Quote is one row of GET /v1/stocks/quotes. Prices are integer IDR.
type Quote struct {
	Code       string   `json:"code"`
	Name       string   `json:"name"`
	Price      int64    `json:"price"`
	PrevClose  *int64   `json:"prev_close"`
	Change     *int64   `json:"change"`
	ChangePct  *float64 `json:"change_pct"`
	High       *int64   `json:"high"`
	Low        *int64   `json:"low"`
	Volume     *int64   `json:"volume"`
	Week52High *int64   `json:"week52_high"`
	Week52Low  *int64   `json:"week52_low"`
	MarketTime *string  `json:"market_time"`
	Spark      []int64  `json:"spark"` // today's 5-minute closes, oldest first
	Tick       int64    `json:"tick"`
	ARA        *int64   `json:"ara"`
	ARB        *int64   `json:"arb"`
	Stale      bool     `json:"stale"`
}

// Bar is one OHLCV bar; T is unix seconds.
type Bar struct {
	T int64 `json:"t"`
	O int64 `json:"o"`
	H int64 `json:"h"`
	L int64 `json:"l"`
	C int64 `json:"c"`
	V int64 `json:"v"`
}

// Chart is GET /v1/stocks/chart/{code}.
type Chart struct {
	Code      string `json:"code"`
	Range     string `json:"range"`
	Interval  string `json:"interval"`
	TZ        string `json:"tz"`
	PrevClose *int64 `json:"prev_close"`
	Stale     bool   `json:"stale"`
	Bars      []Bar  `json:"bars"`
	Name      string `json:"-"` // from the source's meta; used to name a newly added code
}

// ChartRanges maps each range to its bar interval (Yahoo limits: 5m/15m 60 days, 1h 730 days).
var ChartRanges = map[string]string{"1d": "5m", "5d": "15m", "1mo": "1h", "6mo": "1d", "1y": "1d", "5y": "1d"}

// Cache lifetimes.
const (
	QuoteTTL        = 60 * time.Second
	IntradayTTL     = 60 * time.Second
	DailyTTL        = 15 * time.Minute
	SearchTTL       = 24 * time.Hour
	firstBackoff    = time.Minute
	maxBackoff      = 15 * time.Minute
	maxCachedSrch   = 500
	maxCachedCharts = 200 // least recently used chart goes first
)

type stamped[T any] struct {
	v  T
	at time.Time
}

// Market wraps a Provider with TTL caches and a shared backoff: after a BlockedError no call
// goes out until the backoff ends (1, 2, 4, 8, then 15 minutes); meanwhile, and on any other
// failure, cached data is served with Stale set.
type Market struct {
	Src Provider
	Now func() time.Time
	Log *slog.Logger

	mu           sync.Mutex
	quotes       map[string]stamped[Quote]
	charts       map[string]stamped[Chart]
	chartUse     []string // chart keys, least recently used first
	searches     map[string]stamped[[]SearchResult]
	blockedUntil time.Time
	backoff      time.Duration
}

func (m *Market) now() time.Time {
	if m.Now != nil {
		return m.Now()
	}
	return time.Now()
}

// blocked returns the remaining backoff, 0 when calls may go out.
func (m *Market) blocked() time.Duration {
	m.mu.Lock()
	defer m.mu.Unlock()
	if d := m.blockedUntil.Sub(m.now()); d > 0 {
		return d
	}
	return 0
}

// note records the outcome of a source call and turns a BlockedError into one with RetryAfter.
func (m *Market) note(err error) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	var be *BlockedError
	var ue *UpstreamError
	switch {
	case errors.As(err, &be):
	case errors.As(err, &ue) && ue.Status >= 500: // an outage backs off like a 429
	default:
		if err == nil {
			m.backoff = 0
		}
		return err
	}
	m.backoff = min(max(m.backoff*2, firstBackoff), maxBackoff)
	m.blockedUntil = m.now().Add(m.backoff)
	if m.Log != nil {
		m.Log.Warn("stocks: price source blocked us; backing off", "for", m.backoff)
	}
	return &BlockedError{RetryAfter: m.backoff}
}

func (m *Market) blockedErr() error {
	if d := m.blocked(); d > 0 {
		return &BlockedError{RetryAfter: d}
	}
	return nil
}

// QuoteResult holds the quotes found and an error per code that has none.
type QuoteResult struct {
	Quotes map[string]Quote
	Errors map[string]error
}

// Quotes answers each code from a fresh cache entry, else from the source, else from a stale entry.
func (m *Market) Quotes(ctx context.Context, codes []string) QuoteResult {
	res, _, _ := m.quotesReport(ctx, codes)
	return res
}

// quotesReport is Quotes that also says whether the source was asked and what it (or the backoff) answered:
// the screener stops a scan on a *BlockedError and logs its call budget.
func (m *Market) quotesReport(ctx context.Context, codes []string) (res QuoteResult, asked bool, srcErr error) {
	res = QuoteResult{Quotes: map[string]Quote{}, Errors: map[string]error{}}
	var missing []string
	m.mu.Lock()
	if m.quotes == nil {
		m.quotes = map[string]stamped[Quote]{}
	}
	for _, c := range codes {
		if e, ok := m.quotes[c]; ok && m.now().Sub(e.at) < QuoteTTL {
			res.Quotes[c] = e.v
		} else {
			missing = append(missing, c)
		}
	}
	m.mu.Unlock()
	if len(missing) == 0 {
		return res, false, nil
	}
	got, err := map[string]Quote(nil), m.blockedErr()
	if err == nil {
		asked = true
		got, err = m.Src.Quotes(ctx, missing)
		err = m.note(err)
	}
	srcErr = err
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, c := range missing {
		if q, ok := got[c]; ok {
			m.quotes[c] = stamped[Quote]{q, m.now()}
			res.Quotes[c] = q
			continue
		}
		if err == nil {
			res.Errors[c] = ErrUnknownSymbol
		} else if e, ok := m.quotes[c]; ok {
			q := e.v
			q.Stale = true
			res.Quotes[c] = q
		} else {
			res.Errors[c] = err
		}
	}
	return res, asked, srcErr
}

func chartTTL(rng string) time.Duration {
	if ChartRanges[rng] == "1d" {
		return DailyTTL
	}
	return IntradayTTL
}

// Chart answers from a fresh cache entry, else the source, else a stale entry.
func (m *Market) Chart(ctx context.Context, code, rng string) (Chart, error) {
	key := code + "|" + rng
	m.mu.Lock()
	if m.charts == nil {
		m.charts = map[string]stamped[Chart]{}
	}
	e, cached := m.charts[key]
	if cached {
		m.touchChart(key)
	}
	m.mu.Unlock()
	if cached && m.now().Sub(e.at) < chartTTL(rng) {
		return e.v, nil
	}
	err := m.blockedErr()
	if err == nil {
		var ch Chart
		ch, err = m.Src.Chart(ctx, code, rng)
		if err = m.note(err); err == nil {
			m.mu.Lock()
			m.keepChartLocked(key, ch)
			m.mu.Unlock()
			return ch, nil
		}
	}
	if cached && !errors.Is(err, ErrUnknownSymbol) {
		ch := e.v
		ch.Stale = true
		return ch, nil
	}
	return Chart{}, err
}

// ChartFresh always asks the source (never a cached or stale answer) and caches what it gets. It shares the
// backoff: while blocked, and when the source refuses, it returns a *BlockedError. For the bars job, which must
// tell a block (stop the run) from a cached answer.
func (m *Market) ChartFresh(ctx context.Context, code, rng string) (Chart, error) {
	if err := m.blockedErr(); err != nil {
		return Chart{}, err
	}
	ch, err := m.Src.Chart(ctx, code, rng)
	if err = m.note(err); err != nil {
		return Chart{}, err
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	m.keepChartLocked(code+"|"+rng, ch)
	return ch, nil
}

// keepChartLocked caches ch under key, dropping the least recently used chart past the cap. Callers hold m.mu.
func (m *Market) keepChartLocked(key string, ch Chart) {
	if m.charts == nil {
		m.charts = map[string]stamped[Chart]{}
	}
	m.charts[key] = stamped[Chart]{ch, m.now()}
	m.touchChart(key)
	for len(m.charts) > maxCachedCharts && len(m.chartUse) > 0 {
		delete(m.charts, m.chartUse[0])
		m.chartUse = m.chartUse[1:]
	}
}

// Search asks the source (cached 24 h); while blocked or failing it returns nothing and the error.
func (m *Market) Search(ctx context.Context, q string) ([]SearchResult, error) {
	key := strings.ToLower(q)
	m.mu.Lock()
	if m.searches == nil || len(m.searches) > maxCachedSrch {
		m.searches = map[string]stamped[[]SearchResult]{}
	}
	e, ok := m.searches[key]
	m.mu.Unlock()
	if ok && m.now().Sub(e.at) < SearchTTL {
		return e.v, nil
	}
	if err := m.blockedErr(); err != nil {
		return nil, err
	}
	res, err := m.Src.Search(ctx, q)
	if err = m.note(err); err != nil {
		return nil, err
	}
	m.mu.Lock()
	m.searches[key] = stamped[[]SearchResult]{res, m.now()}
	m.mu.Unlock()
	return res, nil
}

// touchChart marks key as the most recently used chart. Callers hold m.mu.
func (m *Market) touchChart(key string) {
	if i := slices.Index(m.chartUse, key); i >= 0 {
		m.chartUse = slices.Delete(m.chartUse, i, i+1)
	}
	m.chartUse = append(m.chartUse, key)
}
