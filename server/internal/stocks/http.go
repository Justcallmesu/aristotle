package stocks

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"math"
	"net/http"
	"slices"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"
)

const (
	maxBodyBytesIn = 4 << 10
	maxQuoteCodes  = 50
	defaultNews    = 50
	maxSearchLen   = 40
	maxSearchRows  = 20
)

type errorBody struct {
	Error       string `json:"error"`
	RetryAfterS *int   `json:"retry_after_s,omitempty"`
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func writeError(w http.ResponseWriter, e *apiError) {
	body := errorBody{Error: e.code}
	if e.code == "upstream_blocked" || e.code == "too_soon" {
		secs := max(int(math.Ceil(e.retryAfter.Seconds())), 1)
		body.RetryAfterS = &secs
		w.Header().Set("Retry-After", strconv.Itoa(secs))
	}
	writeJSON(w, e.status, body)
}

// normCode uppercases a path or query code and checks ^[A-Z]{4}$ (no ".JK").
func normCode(raw string) (string, error) {
	c := strings.ToUpper(strings.TrimSpace(raw))
	if !codeRe.MatchString(c) {
		return "", errBadCode
	}
	return c, nil
}

// decodeBody reads one JSON object of at most 4 KiB with no unknown fields.
func decodeBody(r *http.Request, v any) error { return decodeBodyMax(r, v, maxBodyBytesIn) }

// decodeBodyMax is decodeBody with a size limit of limit bytes.
func decodeBodyMax(r *http.Request, v any, limit int) error {
	data, err := io.ReadAll(io.LimitReader(r.Body, int64(limit)+1))
	if err != nil || len(data) > limit {
		return errBadRequest
	}
	dec := json.NewDecoder(bytes.NewReader(data))
	dec.DisallowUnknownFields()
	if err := dec.Decode(v); err != nil {
		return errBadRequest
	}
	if _, err := dec.Token(); !errors.Is(err, io.EOF) {
		return errBadRequest
	}
	return nil
}

// --- watchlist ---

type watchlistBody struct {
	Items []Entry `json:"items"`
}

func (s *Service) getWatchlist(*http.Request) (int, any, error) {
	st, err := s.snapshot()
	if err != nil {
		return 0, nil, err
	}
	return http.StatusOK, watchlistBody{Items: st.Watchlist}, nil
}

// addToWatchlist appends a code (idempotent). A code outside the bundled list is checked at the
// price source first; the lock is not held during that call, so limits are checked again after it.
func (s *Service) addToWatchlist(r *http.Request) (int, any, error) {
	code, err := normCode(r.PathValue("code"))
	if err != nil {
		return 0, nil, err
	}
	st, err := s.snapshot()
	if err != nil {
		return 0, nil, err
	}
	if st.watchIndex(code) >= 0 {
		return http.StatusOK, watchlistBody{Items: st.Watchlist}, nil
	}
	if len(st.Watchlist) >= MaxWatchlist {
		return 0, nil, errLimit
	}
	name, err := s.resolveName(r.Context(), st, code)
	if err != nil {
		return 0, nil, err
	}
	st, err = s.update(func(st State) (State, error) { return st.addWatch(Entry{Code: code, Name: name}) })
	if err != nil {
		return 0, nil, err
	}
	return http.StatusOK, watchlistBody{Items: st.Watchlist}, nil
}

func (s *Service) removeFromWatchlist(r *http.Request) (int, any, error) {
	code, err := normCode(r.PathValue("code"))
	if err != nil {
		return 0, nil, err
	}
	st, err := s.update(func(st State) (State, error) { return st.removeWatch(code) })
	if err != nil {
		return 0, nil, err
	}
	return http.StatusOK, watchlistBody{Items: st.Watchlist}, nil
}

func (s *Service) reorderWatchlist(r *http.Request) (int, any, error) {
	var body struct {
		Codes []string `json:"codes"`
	}
	if err := decodeBody(r, &body); err != nil || body.Codes == nil {
		return 0, nil, errBadRequest
	}
	codes := make([]string, len(body.Codes))
	for i, raw := range body.Codes {
		c, err := normCode(raw)
		if err != nil {
			return 0, nil, err
		}
		codes[i] = c
	}
	st, err := s.update(func(st State) (State, error) { return st.reorderWatch(codes) })
	if err != nil {
		return 0, nil, err
	}
	return http.StatusOK, watchlistBody{Items: st.Watchlist}, nil
}

// --- holdings ---

type holdingRow struct {
	Code     string   `json:"code"`
	Name     string   `json:"name"`
	Lots     int64    `json:"lots"`
	AvgPrice float64  `json:"avg_price"`
	Price    *int64   `json:"price"`
	Cost     int64    `json:"cost"`
	Value    *int64   `json:"value"`
	PL       *int64   `json:"pl"`
	PLPct    *float64 `json:"pl_pct"`
	Today    *int64   `json:"today"` // (price − prev_close) × shares, no fees
	Stale    bool     `json:"stale"`
	Stop     *int64   `json:"stop"`   // hand-set stop for the price alerts (Q104), null when unset
	Target   *int64   `json:"target"` // hand-set target, null when unset
}

type holdingsTotal struct {
	Cost  int64    `json:"cost"`
	Value *int64   `json:"value"`
	PL    *int64   `json:"pl"`
	PLPct *float64 `json:"pl_pct"`
	Today *int64   `json:"today"`
}

type holdingsBody struct {
	At       string        `json:"at"`
	Holdings []holdingRow  `json:"holdings"`
	Total    holdingsTotal `json:"total"`
}

func (s *Service) getHoldings(r *http.Request) (int, any, error) {
	st, err := s.snapshot()
	if err != nil {
		return 0, nil, err
	}
	return http.StatusOK, s.holdings(r, st), nil
}

// holdings prices every holding; one without a quote gets null price-dependent fields.
func (s *Service) holdings(r *http.Request, st State) holdingsBody {
	codes := make([]string, len(st.Holdings))
	for i, h := range st.Holdings {
		codes[i] = h.Code
	}
	quotes := s.market.Quotes(r.Context(), codes).Quotes
	levels := s.holdingLevels()
	body := holdingsBody{At: s.now().In(WIB).Format(time.RFC3339), Holdings: []holdingRow{}}
	positions := make([]Position, 0, len(st.Holdings))
	var today int64
	todayKnown := true
	for _, h := range st.Holdings {
		row := holdingRow{Code: h.Code, Name: h.Name, Lots: h.Lots, AvgPrice: h.AvgPrice}
		if l, ok := levels[h.Code]; ok {
			row.Stop, row.Target = l.Stop, l.Target
		}
		if q, ok := quotes[h.Code]; ok {
			row.Price, row.Stale = &q.Price, q.Stale
			if q.Change != nil {
				t := *q.Change * h.Lots * LotSize
				row.Today = &t
			}
		}
		p := Evaluate(h, row.Price, st.Settings)
		row.Cost, row.Value, row.PL, row.PLPct = p.Cost, p.Value, p.PL, p.PLPct
		positions = append(positions, p)
		if row.Today == nil {
			todayKnown = false
		} else {
			today += *row.Today
		}
		body.Holdings = append(body.Holdings, row)
	}
	t := Total(positions)
	body.Total = holdingsTotal{Cost: t.Cost, Value: t.Value, PL: t.PL, PLPct: t.PLPct}
	if todayKnown {
		body.Total.Today = &today
	}
	return body
}

func (s *Service) putHolding(r *http.Request) (int, any, error) {
	code, err := normCode(r.PathValue("code"))
	if err != nil {
		return 0, nil, err
	}
	var body struct {
		Lots     *int64   `json:"lots"`
		AvgPrice *float64 `json:"avg_price"`
		Stop     *int64   `json:"stop"`   // optional (Q104); absent or null = none
		Target   *int64   `json:"target"` // optional
	}
	if err := decodeBody(r, &body); err != nil || body.Lots == nil || body.AvgPrice == nil {
		return 0, nil, errBadRequest
	}
	avg := math.Round(*body.AvgPrice*100) / 100
	if err := checkPosition(*body.Lots, avg); err != nil {
		return 0, nil, errBadRequest
	}
	if err := checkLevels(body.Stop, body.Target, avg); err != nil {
		return 0, nil, err
	}
	st, err := s.snapshot()
	if err != nil {
		return 0, nil, err
	}
	if st.holdingIndex(code) < 0 && len(st.Holdings) >= MaxHoldings {
		return 0, nil, errLimit
	}
	name, err := s.resolveName(r.Context(), st, code)
	if err != nil {
		return 0, nil, err
	}
	st, err = s.putHoldingLevels(Holding{Code: code, Name: name, Lots: *body.Lots, AvgPrice: avg}, body.Stop, body.Target)
	if err != nil {
		return 0, nil, err
	}
	return http.StatusOK, s.holdings(r, st), nil
}

func (s *Service) deleteHolding(r *http.Request) (int, any, error) {
	code, err := normCode(r.PathValue("code"))
	if err != nil {
		return 0, nil, err
	}
	st, err := s.update(func(st State) (State, error) { return st.removeHolding(code) })
	if err != nil {
		return 0, nil, err
	}
	s.dropLevels(code)
	return http.StatusOK, s.holdings(r, st), nil
}

// --- settings ---

func (s *Service) getSettings(*http.Request) (int, any, error) {
	st, err := s.snapshot()
	if err != nil {
		return 0, nil, err
	}
	return http.StatusOK, st.Settings, nil
}

func (s *Service) putSettings(r *http.Request) (int, any, error) {
	var body struct {
		Buy  *float64 `json:"buy_fee_pct"`
		Sell *float64 `json:"sell_fee_pct"`
	}
	if err := decodeBody(r, &body); err != nil || body.Buy == nil || body.Sell == nil {
		return 0, nil, errBadRequest
	}
	st, err := s.update(func(st State) (State, error) {
		return st.setSettings(Settings{BuyFeePct: *body.Buy, SellFeePct: *body.Sell})
	})
	if err != nil {
		return 0, nil, err
	}
	return http.StatusOK, st.Settings, nil
}

// --- quotes ---

type quoteError struct {
	Code  string `json:"code"`
	Error string `json:"error"`
}

type quotesBody struct {
	At     string       `json:"at"`
	Market MarketStatus `json:"market"`
	Quotes []Quote      `json:"quotes"`
	Errors []quoteError `json:"errors"`
}

// quoteCodes reads ?codes=A,B (≤ 50, deduplicated); without it, the watchlist then the other holdings.
func (s *Service) quoteCodes(r *http.Request) ([]string, error) {
	if raw, ok := r.URL.Query()["codes"]; ok {
		var codes []string
		for _, part := range strings.Split(strings.Join(raw, ","), ",") {
			c, err := normCode(part)
			if err != nil {
				return nil, err
			}
			if !slices.Contains(codes, c) {
				codes = append(codes, c)
			}
		}
		if len(codes) > maxQuoteCodes {
			return nil, errBadRequest
		}
		return codes, nil
	}
	st, err := s.snapshot()
	if err != nil {
		return nil, err
	}
	codes := []string{}
	for _, e := range st.Watchlist {
		codes = append(codes, e.Code)
	}
	for _, h := range st.Holdings {
		if !slices.Contains(codes, h.Code) {
			codes = append(codes, h.Code)
		}
	}
	return codes, nil
}

func (s *Service) getQuotes(r *http.Request) (int, any, error) {
	codes, err := s.quoteCodes(r)
	if err != nil {
		return 0, nil, err
	}
	now := s.now()
	res := s.market.Quotes(r.Context(), codes)
	names := s.knownNames()
	body := quotesBody{At: now.In(WIB).Format(time.RFC3339), Market: Status(now), Quotes: []Quote{}, Errors: []quoteError{}}
	var worst *apiError
	for _, c := range codes {
		q, ok := res.Quotes[c]
		if !ok {
			ae := asAPIError(res.Errors[c])
			body.Errors = append(body.Errors, quoteError{Code: c, Error: ae.code})
			if ae.status > http.StatusUnprocessableEntity && (worst == nil || ae.status > worst.status) {
				worst = ae
			}
			continue
		}
		body.Quotes = append(body.Quotes, s.enrich(q, names, now))
	}
	// Nothing to show and the source failed: the whole call fails (503 blocked before 502).
	if len(body.Quotes) == 0 && worst != nil {
		return 0, nil, worst
	}
	return http.StatusOK, body, nil
}

// knownNames maps the user's codes to their stored names.
func (s *Service) knownNames() map[string]string {
	st, err := s.snapshot()
	names := map[string]string{}
	if err != nil {
		return names
	}
	for _, e := range st.Watchlist {
		names[e.Code] = e.Name
	}
	for _, h := range st.Holdings {
		names[h.Code] = h.Name
	}
	return names
}

// enrich names a quote (stored, bundled, then the source's) and adds tick and auto-reject limits.
func (s *Service) enrich(q Quote, names map[string]string, now time.Time) Quote {
	if n := names[q.Code]; n != "" {
		q.Name = n
	} else if st, ok := s.uni().Lookup(q.Code); ok {
		q.Name = st.Name
	}
	ref := q.Price
	if q.PrevClose != nil {
		ref = *q.PrevClose
	}
	q.Tick = TickSize(ref)
	q.ARA, q.ARB = nil, nil
	if q.PrevClose != nil {
		q.ARA, q.ARB = AutoReject(*q.PrevClose, now)
	}
	return q
}

// --- chart ---

func (s *Service) getChart(r *http.Request) (int, any, error) {
	code, err := normCode(r.PathValue("code"))
	if err != nil {
		return 0, nil, err
	}
	rng := r.URL.Query().Get("range")
	if rng == "" {
		rng = "1d"
	}
	if _, ok := ChartRanges[rng]; !ok {
		return 0, nil, errBadRequest
	}
	ch, err := s.market.Chart(r.Context(), code, rng)
	if err != nil {
		return 0, nil, err
	}
	return http.StatusOK, ch, nil
}

// --- news ---

type newsItem struct {
	ID          string   `json:"id"`
	Source      string   `json:"source"`
	Title       string   `json:"title"`
	Link        string   `json:"link"`
	PublishedAt string   `json:"published_at"`
	Summary     string   `json:"summary"`
	ImageURL    *string  `json:"image_url"`
	Codes       []string `json:"codes"`
	Match       *string  `json:"match"`
}

type newsBody struct {
	Items   []newsItem     `json:"items"`
	Sources []SourceStatus `json:"sources"`
}

func (s *Service) getNews(r *http.Request) (int, any, error) {
	q := r.URL.Query()
	code := ""
	if raw := q.Get("code"); raw != "" {
		c, err := normCode(raw)
		if err != nil {
			return 0, nil, err
		}
		code = c
	}
	limit := defaultNews
	if raw := q.Get("limit"); raw != "" {
		n, err := strconv.Atoi(raw)
		if err != nil || n < 1 || n > NewsRing {
			return 0, nil, errBadRequest
		}
		limit = n
	}
	articles, sources := s.news.Items(r.Context())
	cands := s.candidates(code)
	body := newsBody{Items: []newsItem{}, Sources: sources}
	for _, a := range articles {
		if len(body.Items) == limit {
			break
		}
		m := MatchArticle(a.Title, a.Summary, cands)
		if code != "" && !slices.Contains(m.Codes, code) {
			continue
		}
		body.Items = append(body.Items, newsItemFrom(a, m))
	}
	return http.StatusOK, body, nil
}

func newsItemFrom(a Article, m ArticleMatch) newsItem {
	it := newsItem{ID: a.ID, Source: a.Source, Title: a.Title, Link: a.Link, Summary: a.Summary,
		PublishedAt: a.Published.In(WIB).Format(time.RFC3339), Codes: m.Codes}
	if it.Codes == nil {
		it.Codes = []string{}
	}
	if a.ImageURL != "" {
		img := a.ImageURL
		it.ImageURL = &img
	}
	if m.Strength != "" {
		st := m.Strength
		it.Match = &st
	}
	return it
}

// candidates are the watchlist and holdings codes plus extra, with stored and bundled names.
func (s *Service) candidates(extra string) []Candidate {
	var out []Candidate
	add := func(code, name string) {
		if code == "" || slices.ContainsFunc(out, func(c Candidate) bool { return c.Code == code }) {
			return
		}
		c := Candidate{Code: code, Name: name}
		if st, ok := s.uni().Lookup(code); ok {
			c.Aliases = st.Aliases
			if c.Name == "" {
				c.Name = st.Name
			}
		}
		out = append(out, c)
	}
	if st, err := s.snapshot(); err == nil {
		for _, e := range st.Watchlist {
			add(e.Code, e.Name)
		}
		for _, h := range st.Holdings {
			add(h.Code, h.Name)
		}
	}
	add(extra, "")
	return out
}

// --- search ---

type searchBody struct {
	Results []SearchResult `json:"results"`
}

// getSearch lists bundled matches first, then the source's IDX matches (skipped for an exact
// bundled code, and left out while the source fails).
func (s *Service) getSearch(r *http.Request) (int, any, error) {
	q := strings.TrimSpace(r.URL.Query().Get("q"))
	if n := utf8.RuneCountInString(q); n < 1 || n > maxSearchLen {
		return 0, nil, errBadRequest
	}
	u := s.uni()
	results := u.Search(q, maxSearchRows)
	if _, exact := u.Lookup(strings.ToUpper(q)); exact || len(results) >= maxSearchRows {
		return http.StatusOK, searchBody{Results: results}, nil
	}
	more, err := s.market.Search(r.Context(), q)
	if err != nil {
		s.log.Debug("stocks: search source failed", "err", err)
	}
	for _, m := range more {
		if len(results) == maxSearchRows {
			break
		}
		if slices.ContainsFunc(results, func(x SearchResult) bool { return x.Code == m.Code }) {
			continue
		}
		if st, ok := u.Lookup(m.Code); ok {
			m.Name = st.Name
		}
		m.InUniverse = u.InIndex(m.Code)
		results = append(results, m)
	}
	return http.StatusOK, searchBody{Results: results}, nil
}
