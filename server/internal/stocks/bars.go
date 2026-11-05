package stocks

import (
	"cmp"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"slices"
	"sync"
	"time"

	"github.com/justcallmesu/aristotle/server/internal/statefile"
)

// The daily-bar store (spec P8, TSC0 §1, protocol §20.2): stocks-bars.json keeps up to a year of completed
// daily bars per code of the quoted set (screener universe ∪ watchlist ∪ holdings). It is read once at start
// and rewritten atomically once per bars.daily run (16:20 WIB on trading days). The run asks Yahoo for each
// code's 1y daily chart, one call every 1.5 s, the codes fetched longest ago first; a block stops the run (what
// was fetched is kept) and the next day's run starts with the codes left over. The analyzer reads a code's
// bars from here when they are fresh and asks Yahoo otherwise.

const (
	barsFile         = "stocks-bars.json"
	barsVersion      = 1
	BarsJobID        = "bars.daily"
	barsJobAt        = "16:20"
	barsRange        = "1y"
	barsPace         = 1500 * time.Millisecond
	maxBarsPerCode   = 300                  // ~250 sessions a year
	maxBarAge        = 400 * 24 * time.Hour // a year and some, from the fetch time
	maxBarCodes      = 400                  // codes in the file, with room
	maxBarsRunCodes  = 180                  // codes one run fetches (the 180-call budget): universe, then watchlist, then holdings
	barsJobMinAt     = "16:15"              // the session closes at 16:00; a run earlier would miss the day's bar
	maxBarsFileBytes = 32 << 20
	barsFreshAfter   = 16*time.Hour + 15*time.Minute // a store fetched this long after a session's 00:00 has its bar
)

// barSeries is one code in stocks-bars.json. Bars are [t,o,h,l,c,v], oldest first.
type barSeries struct {
	Name      string     `json:"name,omitempty"`
	FetchedAt string     `json:"fetched_at"`
	Bars      [][6]int64 `json:"bars"`
}

type barsDoc struct {
	Version int                  `json:"version"`
	Codes   map[string]barSeries `json:"codes"`
}

type storedBars struct {
	name      string
	fetchedAt time.Time
	bars      []Bar
}

// barStore is the in-memory store; bars slices are replaced, never changed in place.
type barStore struct {
	dir string
	log logger

	mu    sync.Mutex
	codes map[string]storedBars
}

// loadBarStore reads stocks-bars.json. A file that cannot be used is logged and the store starts empty (it is
// a cache: the next run rewrites it).
func loadBarStore(dir string, log logger) *barStore {
	b := &barStore{dir: dir, log: log, codes: map[string]storedBars{}}
	data, err := statefile.Read(dir, barsFile)
	if errors.Is(err, fs.ErrNotExist) {
		return b
	}
	if err == nil {
		b.codes, err = parseBars(data)
	}
	if err != nil {
		b.codes = map[string]storedBars{}
		log.Error("stocks: bar store unusable; starting empty (the next bars run rewrites it)", "file", barsFile, "err", err)
	}
	return b
}

func parseBars(data []byte) (map[string]storedBars, error) {
	if len(data) > maxBarsFileBytes {
		return nil, fmt.Errorf("%s: %d bytes, over the cap", barsFile, len(data))
	}
	var doc barsDoc
	if err := json.Unmarshal(data, &doc); err != nil {
		return nil, fmt.Errorf("%s: %w", barsFile, err)
	}
	if doc.Version != barsVersion {
		return nil, fmt.Errorf("%s: version %d, want %d", barsFile, doc.Version, barsVersion)
	}
	if len(doc.Codes) > maxBarCodes {
		return nil, fmt.Errorf("%s: %d codes, over the cap", barsFile, len(doc.Codes))
	}
	out := make(map[string]storedBars, len(doc.Codes))
	for code, s := range doc.Codes {
		at, err := time.Parse(time.RFC3339, s.FetchedAt)
		if !codeRe.MatchString(code) || err != nil || len(s.Bars) > maxBarsPerCode {
			return nil, fmt.Errorf("%s: bad entry %q", barsFile, code)
		}
		bars := make([]Bar, len(s.Bars))
		for i, r := range s.Bars {
			bars[i] = Bar{T: r[0], O: r[1], H: r[2], L: r[3], C: r[4], V: r[5]}
			if bars[i].C <= 0 || (i > 0 && bars[i].T <= bars[i-1].T) {
				return nil, fmt.Errorf("%s: bad bar in %q", barsFile, code)
			}
		}
		out[code] = storedBars{name: s.Name, fetchedAt: at, bars: bars}
	}
	return out, nil
}

func (b *barStore) get(code string) (storedBars, bool) {
	b.mu.Lock()
	defer b.mu.Unlock()
	s, ok := b.codes[code]
	return s, ok
}

// put replaces code's bars with the completed ones fetched at at (≤ a year, ≤ maxBarsPerCode, oldest first).
func (b *barStore) put(code, name string, bars []Bar, at time.Time) {
	cut := at.Add(-maxBarAge).Unix()
	kept := make([]Bar, 0, len(bars))
	for _, x := range bars {
		if x.T >= cut && x.C > 0 && (len(kept) == 0 || x.T > kept[len(kept)-1].T) {
			kept = append(kept, x)
		}
	}
	if n := len(kept); n > maxBarsPerCode {
		kept = kept[n-maxBarsPerCode:]
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	if name == "" {
		name = b.codes[code].name
	}
	b.codes[code] = storedBars{name: name, fetchedAt: at, bars: kept}
}

// prune drops the codes outside keep.
func (b *barStore) prune(keep map[string]bool) int {
	b.mu.Lock()
	defer b.mu.Unlock()
	n := 0
	for code := range b.codes {
		if !keep[code] {
			delete(b.codes, code)
			n++
		}
	}
	return n
}

// fetchedAt is when code was last fetched (zero when never).
func (b *barStore) fetchedAt(code string) time.Time {
	s, _ := b.get(code)
	return s.fetchedAt
}

func (b *barStore) count() int {
	b.mu.Lock()
	defer b.mu.Unlock()
	return len(b.codes)
}

func (b *barStore) save() error {
	b.mu.Lock()
	doc := barsDoc{Version: barsVersion, Codes: make(map[string]barSeries, len(b.codes))}
	for code, s := range b.codes {
		rows := make([][6]int64, len(s.bars))
		for i, x := range s.bars {
			rows[i] = [6]int64{x.T, x.O, x.H, x.L, x.C, x.V}
		}
		doc.Codes[code] = barSeries{Name: s.name, FetchedAt: s.fetchedAt.In(WIB).Format(time.RFC3339), Bars: rows}
	}
	b.mu.Unlock()
	data, err := json.Marshal(doc)
	if err != nil {
		return err
	}
	return statefile.WriteAtomic(b.dir, barsFile, append(data, '\n'))
}

// prevTradingDay is the last trading day before day.
func prevTradingDay(day time.Time) time.Time {
	d := at(day.In(WIB), clock{0, 0})
	for range 30 {
		d = d.AddDate(0, 0, -1)
		if IsTradingDay(d) {
			break
		}
	}
	return d
}

// barsFresh: bars fetched at fetchedAt hold every session completed before the session date of now (fetched
// at least 15 minutes after the close of the last trading day before it).
func barsFresh(fetchedAt, now time.Time) bool {
	return !fetchedAt.IsZero() && !fetchedAt.Before(prevTradingDay(SessionDate(now)).Add(barsFreshAfter))
}

// dailyBars are a code's daily bars for the indicators: from the store when fresh (no Yahoo call), else the
// 1y chart through Market. name is the source's name for the code ("" when unknown).
func (s *Service) dailyBars(ctx context.Context, code string) (bars []Bar, name string, err error) {
	if sb, ok := s.bars.get(code); ok && len(sb.bars) > 0 && barsFresh(sb.fetchedAt, s.now()) {
		return slices.Clone(sb.bars), sb.name, nil
	}
	ch, err := s.market.Chart(ctx, code, barsRange)
	if err != nil {
		return nil, "", err
	}
	return ch.Bars, ch.Name, nil
}

// barCodes is the quoted set, at most maxBarsRunCodes: the screener universe (sorted), then the watchlist,
// then the holdings. full is false when stocks.json is unusable (then the store must not be pruned: the
// user's codes are unknown). Codes cut by the cap are logged.
func (s *Service) barCodes() (codes []string, full bool) {
	seen := map[string]bool{}
	add := func(c string) {
		if !seen[c] {
			seen[c] = true
			codes = append(codes, c)
		}
	}
	for _, c := range s.uni().Members() { // sorted
		add(c)
	}
	st, err := s.snapshot()
	if err == nil {
		for _, e := range st.Watchlist {
			add(e.Code)
		}
		for _, h := range st.Holdings {
			add(h.Code)
		}
	}
	if len(codes) > maxBarsRunCodes {
		s.log.Warn("stocks: quoted set over the bars cap; the last codes are left out", "codes", len(codes), "cap", maxBarsRunCodes,
			"left_out", len(codes)-maxBarsRunCodes)
		codes = codes[:maxBarsRunCodes]
	}
	return codes, err == nil
}

// barsWanted decides an at-slot of bars.daily. A catch-up (late) runs only when some quoted code's bars are
// older than the last completed session; an on-time run is skipped when a catch-up that completed today
// already did the work.
func (s *Service) barsWanted(_, now time.Time, late bool, prev RunInfo) bool {
	if !late {
		return !(prev.Late && prev.Status == JobStatusOK && sameDay(prev.At, now))
	}
	codes, _ := s.barCodes()
	for _, c := range codes {
		if sb, ok := s.bars.get(c); !ok || len(sb.bars) == 0 || !barsFresh(sb.fetchedAt, now) {
			return true
		}
	}
	return false
}

// runBarsDaily is the bars.daily job.
func (s *Service) runBarsDaily(ctx context.Context, _ JobRun) error {
	codes, full := s.barCodes()
	// Never fetched first, then the oldest: a run stopped by a block resumes where it stopped.
	slices.SortStableFunc(codes, func(a, b string) int { return s.bars.fetchedAt(a).Compare(s.bars.fetchedAt(b)) })
	day := SessionDate(s.now())
	fetched, failed := 0, 0
	var stop error
	for i, code := range codes {
		if i > 0 {
			if err := s.barsPace(ctx); err != nil {
				stop = err
				break
			}
		}
		ch, err := s.market.ChartFresh(ctx, code, barsRange)
		var be *BlockedError
		if errors.As(err, &be) || ctx.Err() != nil {
			stop = cmp.Or(ctx.Err(), err)
			break
		}
		if err != nil {
			failed++
			continue
		}
		s.bars.put(code, ch.Name, completedBars(ch.Bars, day), s.now())
		fetched++
	}
	pruned := 0
	if full && stop == nil {
		keep := map[string]bool{}
		for _, c := range codes {
			keep[c] = true
		}
		pruned = s.bars.prune(keep)
	}
	saveErr := s.bars.save()
	if ctx.Err() == nil {
		s.screen.screenEOD(s.now(), true) // the just-completed session, from the store (no Yahoo call)
	}
	s.log.Info("stocks: bars run", "codes", len(codes), "fetched", fetched, "failed", failed, "pruned", pruned,
		"left", len(codes)-fetched-failed, "stored", s.bars.count())
	switch {
	case saveErr != nil:
		s.log.Error("stocks: saving the bar store failed", "err", saveErr)
		return &JobError{Code: "state_error"}
	case stop != nil:
		_, code := jobOutcome(stop)
		return &JobError{Code: code, Partial: fetched > 0}
	case failed > 0:
		return &JobError{Code: "codes_failed", Partial: fetched > 0}
	}
	return nil
}

// sleepPace waits barsPace between two fetches of a run.
func sleepPace(ctx context.Context) error {
	t := time.NewTimer(barsPace)
	defer t.Stop()
	select {
	case <-t.C:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}
