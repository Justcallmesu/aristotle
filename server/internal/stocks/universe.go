package stocks

import (
	_ "embed"
	"encoding/json"
	"fmt"
	"slices"
	"strings"
	"time"
)

// universe.json is the bundled ticker list: LQ45 + IDX80 for this index period (sources in its
// "_sources" key; the IDX80 rows come from Peng-00205/BEI.POP/10-2026 Appendix 2) plus a few
// search-only extras. Hand-edited after each quarterly review.
//
//go:embed universe.json
var universeJSON []byte

// Stock is one bundled ticker.
type Stock struct {
	Code    string   `json:"code"`
	Name    string   `json:"name"`
	Aliases []string `json:"aliases"`
	Tags    []string `json:"tags"` // "LQ45", "IDX80"; none = search only
}

// InIndex reports whether the stock belongs to the screener universe (LQ45 or IDX80, spec Q85).
func (s Stock) InIndex() bool { return len(s.Tags) > 0 }

// Universe is the bundled list, keyed by code.
type Universe struct {
	ValidFrom string  `json:"valid_from"`
	ValidTo   string  `json:"valid_to"`
	Stocks    []Stock `json:"stocks"`
	byCode    map[string]Stock
}

func parseUniverse(data []byte) (*Universe, error) {
	var u Universe
	if err := json.Unmarshal(data, &u); err != nil {
		return nil, fmt.Errorf("universe.json: %w", err)
	}
	u.byCode = make(map[string]Stock, len(u.Stocks))
	for _, s := range u.Stocks {
		if !codeRe.MatchString(s.Code) || s.Name == "" {
			return nil, fmt.Errorf("universe.json: bad row %q", s.Code)
		}
		if _, dup := u.byCode[s.Code]; dup {
			return nil, fmt.Errorf("universe.json: %s twice", s.Code)
		}
		u.byCode[s.Code] = s
	}
	return &u, nil
}

// bundledUniverse panics on a broken embed: the file ships with the binary and a test reads it.
func bundledUniverse() *Universe {
	u, err := parseUniverse(universeJSON)
	if err != nil {
		panic(err)
	}
	return u
}

// Lookup returns the bundled stock for code.
func (u *Universe) Lookup(code string) (Stock, bool) {
	s, ok := u.byCode[code]
	return s, ok
}

// Expired reports whether the index period ended before now (the list needs its quarterly edit).
func (u *Universe) Expired(now time.Time) bool {
	return u.ValidTo != "" && now.In(WIB).Format(time.DateOnly) > u.ValidTo
}

// SearchResult is one row of GET /v1/stocks/search.
type SearchResult struct {
	Code       string `json:"code"`
	Name       string `json:"name"`
	InUniverse bool   `json:"in_universe"`
}

// Search ranks bundled stocks for q: exact code, code prefix, exact alias, then name or alias
// containing q (case-insensitive). At most limit rows.
func (u *Universe) Search(q string, limit int) []SearchResult {
	q = strings.ToLower(strings.TrimSpace(q))
	type hit struct {
		rank int
		s    Stock
	}
	var hits []hit
	for _, s := range u.Stocks {
		if r := searchRank(s, q); r >= 0 {
			hits = append(hits, hit{r, s})
		}
	}
	slices.SortStableFunc(hits, func(a, b hit) int { return a.rank - b.rank })
	out := []SearchResult{}
	for _, h := range hits {
		if len(out) == limit {
			break
		}
		out = append(out, SearchResult{Code: h.s.Code, Name: h.s.Name, InUniverse: h.s.InIndex()})
	}
	return out
}

// searchRank is 0 (best) .. 3, or -1 for no match.
func searchRank(s Stock, q string) int {
	code := strings.ToLower(s.Code)
	switch {
	case code == q:
		return 0
	case strings.HasPrefix(code, q):
		return 1
	case slices.ContainsFunc(s.Aliases, func(a string) bool { return strings.ToLower(a) == q }):
		return 2
	case strings.Contains(strings.ToLower(s.Name), q),
		slices.ContainsFunc(s.Aliases, func(a string) bool { return strings.Contains(strings.ToLower(a), q) }):
		return 3
	}
	return -1
}
