// Package stocks is the IDX stocks module (spec v2 slice 1, protocol §20): the watchlist, holdings
// and fee settings in stocks.json, quotes and charts from a price Provider (Yahoo) behind a TTL
// cache, RSS news with ticker matching, and the IDX calendar. It serves /v1/stocks/* behind the
// device token. Logs never carry holdings values.
package stocks

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"sync"
	"time"
)

// apiError is an error the handlers answer as {"error":code}.
type apiError struct {
	status     int
	code       string
	retryAfter time.Duration
}

func (e *apiError) Error() string { return e.code }

var (
	errBadRequest     = &apiError{status: http.StatusBadRequest, code: "bad_request"}
	errBadCode        = &apiError{status: http.StatusBadRequest, code: "bad_code"}
	errNotFound       = &apiError{status: http.StatusNotFound, code: "not_found"}
	errNotInWatchlist = &apiError{status: http.StatusNotFound, code: "not_in_watchlist"}
	errNotHeld        = &apiError{status: http.StatusNotFound, code: "not_held"}
	errLimit          = &apiError{status: http.StatusConflict, code: "limit_reached"}
	errUnknownSymbol  = &apiError{status: http.StatusUnprocessableEntity, code: "unknown_symbol"}
	errUpstream       = &apiError{status: http.StatusBadGateway, code: "upstream_error"}
	errState          = &apiError{status: http.StatusInternalServerError, code: "state_error"}
)

// asAPIError maps a Market/Provider error to its answer.
func asAPIError(err error) *apiError {
	var ae *apiError
	var be *BlockedError
	switch {
	case errors.As(err, &ae):
		return ae
	case errors.As(err, &be):
		return &apiError{status: http.StatusServiceUnavailable, code: "upstream_blocked", retryAfter: be.RetryAfter}
	case errors.Is(err, ErrUnknownSymbol):
		return errUnknownSymbol
	default:
		return errUpstream
	}
}

// Options configure a Service. Zero values pick production defaults.
type Options struct {
	StateDir  string
	Provider  Provider                                              // nil: Yahoo
	Feeds     []Feed                                                // nil: DefaultFeeds
	FetchFeed func(ctx context.Context, url string) ([]byte, error) // nil: HTTPS with a browser UA
	Now       func() time.Time
	Log       *slog.Logger
	NewsAfter func(time.Duration) <-chan time.Time // tests: drive the news poll timer
}

// Service owns the stocks state and serves /v1/stocks/*.
type Service struct {
	dir      string
	now      func() time.Time
	log      *slog.Logger
	market   *Market
	news     *News
	universe *Universe
	mux      *http.ServeMux

	mu       sync.Mutex // guards st; held across a save so writes stay ordered
	st       State
	stateErr error
}

// New loads stocks.json. A file that cannot be used is reported and left alone: the state
// routes then answer 500 state_error, quotes/charts/news/search still work.
func New(o Options) *Service {
	s := &Service{dir: o.StateDir, now: o.Now, log: o.Log, universe: bundledUniverse()}
	if s.now == nil {
		s.now = time.Now
	}
	if s.log == nil {
		s.log = slog.New(slog.DiscardHandler)
	}
	client := newHTTPClient()
	if o.Provider == nil {
		o.Provider = &Yahoo{Client: client}
	}
	if o.Feeds == nil {
		o.Feeds = DefaultFeeds
	}
	if o.FetchFeed == nil {
		o.FetchFeed = func(ctx context.Context, u string) ([]byte, error) {
			return fetch(ctx, client, u, "application/rss+xml, application/xml, text/xml")
		}
	}
	s.market = &Market{Src: o.Provider, Now: s.now, Log: s.log}
	s.news = &News{Feeds: o.Feeds, Fetch: o.FetchFeed, Now: s.now, Log: s.log, After: o.NewsAfter}
	s.st, s.stateErr = loadState(s.dir)
	if s.stateErr != nil {
		s.log.Error("stocks: state unusable; watchlist and holdings are off until it is fixed or removed",
			"file", stateFile, "err", s.stateErr)
	}
	if s.universe.Expired(s.now()) {
		s.log.Warn("stocks: bundled LQ45/IDX80 list is past its period; edit universe.json", "valid_to", s.universe.ValidTo)
	}
	s.mux = s.routes()
	return s
}

// ServeHTTP serves /v1/stocks/*; the caller has checked the device token.
func (s *Service) ServeHTTP(w http.ResponseWriter, r *http.Request) { s.mux.ServeHTTP(w, r) }

// Close stops the news poller.
func (s *Service) Close() { s.news.Close() }

func (s *Service) routes() *http.ServeMux {
	mux := http.NewServeMux()
	handle := func(pattern string, h func(*http.Request) (int, any, error)) {
		mux.HandleFunc(pattern, func(w http.ResponseWriter, r *http.Request) {
			status, body, err := h(r)
			if err != nil {
				writeError(w, asAPIError(err))
				return
			}
			writeJSON(w, status, body)
		})
	}
	handle("GET /v1/stocks/watchlist", s.getWatchlist)
	handle("PUT /v1/stocks/watchlist", s.reorderWatchlist)
	handle("PUT /v1/stocks/watchlist/{code}", s.addToWatchlist)
	handle("DELETE /v1/stocks/watchlist/{code}", s.removeFromWatchlist)
	handle("GET /v1/stocks/holdings", s.getHoldings)
	handle("PUT /v1/stocks/holdings/{code}", s.putHolding)
	handle("DELETE /v1/stocks/holdings/{code}", s.deleteHolding)
	handle("GET /v1/stocks/settings", s.getSettings)
	handle("PUT /v1/stocks/settings", s.putSettings)
	handle("GET /v1/stocks/quotes", s.getQuotes)
	handle("GET /v1/stocks/chart/{code}", s.getChart)
	handle("GET /v1/stocks/news", s.getNews)
	handle("GET /v1/stocks/search", s.getSearch)
	handle("/v1/stocks/", func(*http.Request) (int, any, error) { return 0, nil, errNotFound })
	return mux
}

// snapshot returns the current state (a copy).
func (s *Service) snapshot() (State, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.stateErr != nil {
		return State{}, errState
	}
	return s.st.clone(), nil
}

// update applies f and saves the result; the state is unchanged when f or the save fails.
func (s *Service) update(f func(State) (State, error)) (State, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.stateErr != nil {
		return State{}, errState
	}
	n, err := f(s.st)
	if err != nil {
		return State{}, err
	}
	if err := saveState(s.dir, n); err != nil {
		s.log.Error("stocks: saving state failed", "err", err)
		return State{}, errState
	}
	s.st = n
	return n.clone(), nil
}

// resolveName names a code: the name stored on the other list, the bundled list, else the price
// source (which also proves the code exists: unknown there → unknown_symbol).
func (s *Service) resolveName(ctx context.Context, st State, code string) (string, error) {
	if i := st.watchIndex(code); i >= 0 {
		return st.Watchlist[i].Name, nil
	}
	if i := st.holdingIndex(code); i >= 0 {
		return st.Holdings[i].Name, nil
	}
	if b, ok := s.universe.Lookup(code); ok {
		return b.Name, nil
	}
	ch, err := s.market.Chart(ctx, code, "1d")
	if err != nil {
		return "", err
	}
	if ch.Name == "" {
		return code, nil
	}
	return ch.Name, nil
}
