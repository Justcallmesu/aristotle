package stocks

import (
	"cmp"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"maps"
	"net/http"
	"slices"
	"sync"
	"time"

	"github.com/justcallmesu/aristotle/server/internal/statefile"
)

// The IDX screener (spec Q85/Q86/Q106, TS4 report, protocol §20.3). The screener.scan job runs every 10 min
// (5-30, the job's every_min) inside the delay-shifted session windows: per scan ⌈N/20⌉ spark calls 1.5 s apart
// for the codes with fresh stored bars, and once a day (the first scan whose data time is ≥ 09:00) one daily
// chart per liquid or watched/held code for the day's open. At the end of bars.daily the same rules run on the
// just-completed session from the store (no Yahoo call). Results, settings and the hits log (slice 3 reads it)
// live in stocks-screener.json. The screener never calls Hermes.

const (
	screenerFile      = "stocks-screener.json"
	screenerVersion   = 1
	ScanJobID         = "screener.scan"
	scanEveryMin      = 10
	maxScreenHits     = 500
	maxEODResults     = 5
	scanCooldown      = 2 * time.Minute
	quoteMaxAge       = 25 * time.Minute // a quote older than this (data time) fires nothing intraday
	maxScreenerBytes  = 4 << 20
	screenerStopWait  = 5 * time.Second
	openFetchDataTime = 9 * 60 // data time (minutes after midnight) from which the day's open exists
	maxOpenTries      = 3      // per code per day: a failed read or one with no bar for today yet is retried by later scans
)

// Hit origins (ScreenHit.Origin). Only scan and eod hits are new market data; a rerank hit comes from a settings
// change over kept inputs, so slice 3 never alerts on it. A later scan or eod fire of the same (date, code,
// setup, dir) upgrades a rerank-only hit to that origin (installLocked), so it alerts then.
const (
	HitScan   = "scan"
	HitEOD    = "eod"
	HitRerank = "rerank"
)

var (
	errScanRunning = &apiError{status: http.StatusConflict, code: "scan_running"}
	errTooSoon     = &apiError{status: http.StatusTooManyRequests, code: "too_soon"}
)

// ScreenHit is one entry of the hits log: the first time a (date, code, setup, dir) fired. Slice 3's alerts
// job reads the entries after its cursor (seq).
type ScreenHit struct {
	Seq      int64   `json:"seq"`
	Date     string  `json:"date"`
	Code     string  `json:"code"`
	Setup    string  `json:"setup"`
	Dir      string  `json:"dir"`
	Mode     string  `json:"mode"`
	Origin   string  `json:"origin"` // scan | eod | rerank ("" in hits logged before TBA1)
	At       string  `json:"at"`
	DataAsOf string  `json:"data_as_of"`
	Watch    bool    `json:"watch"`
	Held     bool    `json:"held"`
	Score    float64 `json:"score"`
}

// screenRun is the outcome of the last run of one mode.
type screenRun struct {
	LastRun     string `json:"last_run,omitempty"`
	Status      string `json:"status,omitempty"`
	Error       string `json:"error,omitempty"`
	CodesOK     int    `json:"codes_ok"`
	CodesFailed int    `json:"codes_failed"`
	CodesStale  int    `json:"codes_stale"`
	Stale       bool   `json:"stale,omitempty"` // the run did not replace the result: the one shown is older
}

// screenResult is one screen (intraday: the last scan; eod: one completed session).
type screenResult struct {
	Mode        string               `json:"mode"`
	SessionDate string               `json:"session_date"`
	ComputedAt  string               `json:"computed_at"`
	DataAsOf    *string              `json:"data_as_of"`
	Skipped     screenSkipped        `json:"skipped"`
	Candidates  []ScreenCandidate    `json:"candidates"`
	Flags       []ScreenFlag         `json:"flags"`
	Watched     []screenWatched      `json:"watched,omitempty"` // bullish setups of watched/held non-candidates (hits only)
	Inputs      map[string]liveQuote `json:"inputs,omitempty"`  // intraday: what a re-rank needs
}

// screenerDoc is stocks-screener.json. Settings are stored only once a PUT set them.
type screenerDoc struct {
	Version      int                  `json:"version"`
	Settings     *ScreenerSettings    `json:"settings,omitempty"`
	LastIntraday *screenResult        `json:"last_intraday,omitempty"`
	EOD          []screenResult       `json:"eod"`
	Runs         map[string]screenRun `json:"runs,omitempty"`
	Hits         []ScreenHit          `json:"hits"`
	Seq          int64                `json:"seq"`
}

type screener struct {
	svc *Service

	ctx    context.Context
	cancel context.CancelFunc
	wg     sync.WaitGroup

	mu          sync.Mutex
	doc         screenerDoc
	persist     bool
	running     bool
	runningMode string
	lastStart   time.Time
	setRev      int64            // bumped by every settings PUT: a run that read older settings recomputes before it installs
	opensDay    string           // the WIB date opens/opensDone/opensTries are for
	opens       map[string]int64 // code → the day's open (own map: never the phone's chart cache)
	opensDone   map[string]bool  // the open is known (or Yahoo does not know the code): not asked again today
	opensTries  map[string]int   // failed reads today (an error, or no bar for today yet); retried up to maxOpenTries
}

func newScreener(s *Service) *screener {
	ctx, cancel := context.WithCancel(context.Background())
	sc := &screener{svc: s, ctx: ctx, cancel: cancel, persist: true}
	doc, err := loadScreener(s.dir)
	if err != nil {
		sc.persist = false
		s.log.Error("stocks: screener file unusable; defaults are used and settings cannot be saved until it is fixed or removed",
			"file", screenerFile, "err", err)
	}
	if doc.Settings != nil && !doc.Settings.valid() {
		s.log.Warn("stocks: stored screener settings out of range; defaults used", "file", screenerFile)
		doc.Settings = nil
	}
	sc.doc = doc
	return sc
}

func loadScreener(dir string) (screenerDoc, error) {
	empty := screenerDoc{Version: screenerVersion, EOD: []screenResult{}, Hits: []ScreenHit{}, Runs: map[string]screenRun{}}
	data, err := statefile.Read(dir, screenerFile)
	if errors.Is(err, fs.ErrNotExist) {
		return empty, nil
	}
	if err != nil {
		return empty, err
	}
	if len(data) > maxScreenerBytes {
		return empty, fmt.Errorf("%s: %d bytes, over the cap", screenerFile, len(data))
	}
	var doc screenerDoc
	if err := decodeStrict(data, &doc); err != nil {
		return empty, fmt.Errorf("%s: %w", screenerFile, err)
	}
	if doc.Version != screenerVersion {
		return empty, fmt.Errorf("%s: version %d, want %d", screenerFile, doc.Version, screenerVersion)
	}
	if doc.EOD == nil {
		doc.EOD = []screenResult{}
	}
	if doc.Hits == nil {
		doc.Hits = []ScreenHit{}
	}
	if doc.Runs == nil {
		doc.Runs = map[string]screenRun{}
	}
	if n := len(doc.Hits); n > maxScreenHits {
		doc.Hits = doc.Hits[n-maxScreenHits:]
	}
	return doc, nil
}

// saveLocked writes stocks-screener.json; the caller holds mu.
func (sc *screener) saveLocked() error {
	if !sc.persist {
		return errState
	}
	data, err := json.Marshal(sc.doc)
	if err == nil {
		err = statefile.WriteAtomic(sc.svc.dir, screenerFile, append(data, '\n'))
	}
	if err != nil {
		sc.svc.log.Error("stocks: saving the screener file failed", "err", err)
		return errState
	}
	return nil
}

func (sc *screener) settings() ScreenerSettings {
	set, _ := sc.settingsRev()
	return set
}

// settingsRev is the settings in force and their revision.
func (sc *screener) settingsRev() (ScreenerSettings, int64) {
	sc.mu.Lock()
	defer sc.mu.Unlock()
	return sc.settingsLocked(), sc.setRev
}

func (sc *screener) settingsLocked() ScreenerSettings {
	if sc.doc.Settings != nil {
		return *sc.doc.Settings
	}
	return DefaultScreenerSettings()
}

// installComputed computes a result with set and installs it, unless a settings PUT landed meanwhile: then it
// computes again with the new settings first, so a run that read older settings never replaces the re-rank of
// a newer PUT (TSC2 review MINOR-1). still, when set, is checked under the lock before installing; false drops
// the result (a re-rank whose base was replaced by a newer run). It saves and reports whether it installed.
func (sc *screener) installComputed(set ScreenerSettings, rev int64, compute func(ScreenerSettings) screenResult, run *screenRun,
	origin string, still func() bool) bool {
	for {
		res := compute(set)
		sc.mu.Lock()
		if still != nil && !still() {
			sc.mu.Unlock()
			return false
		}
		if sc.setRev == rev {
			sc.installLocked(res, run, origin)
			_ = sc.saveLocked()
			sc.mu.Unlock()
			return true
		}
		set, rev = sc.settingsLocked(), sc.setRev
		sc.mu.Unlock()
	}
}

func (sc *screener) close() {
	sc.cancel()
	done := make(chan struct{})
	go func() { sc.wg.Wait(); close(done) }()
	select {
	case <-done:
	case <-time.After(screenerStopWait):
		sc.svc.log.Warn("stocks: screener scan did not stop in time")
	}
}

// --- windows and inputs ---

// screenerWindows are the scan windows of a trading day (wall clock): each session shifted by Yahoo's 10-min
// delay plus 5 min of data, session 2 from 10 min after its start (TS4 §1): Mon-Thu [09:15,12:15) [13:40,16:15),
// Fri [09:15,11:45) [14:10,16:15).
func screenerWindows(day time.Time) []Window {
	ss := daySessions(day.Weekday())
	return []Window{
		{Start: at(day, ss[0].start).Add(15 * time.Minute), End: at(day, ss[0].end).Add(15 * time.Minute)},
		{Start: at(day, ss[1].start).Add(10 * time.Minute), End: at(day, ss[1].end).Add(15 * time.Minute)},
	}
}

// inScanWindow: now is inside a scan window of a trading day.
func inScanWindow(now time.Time) bool {
	now = now.In(WIB)
	if !IsTradingDay(now) {
		return false
	}
	for _, w := range screenerWindows(at(now, clock{0, 0})) {
		if !now.Before(w.Start) && now.Before(w.End) {
			return true
		}
	}
	return false
}

// barsCover: bars fetched at fetchedAt hold day's session (fetched at least 15 min after its close).
func barsCover(fetchedAt, day time.Time) bool {
	return !fetchedAt.IsZero() && !fetchedAt.Before(at(day.In(WIB), clock{0, 0}).Add(barsFreshAfter))
}

// eodDay is the last completed session at now.
func eodDay(now time.Time) time.Time { return prevTradingDay(SessionDate(now)) }

func closeTime(date string) *string {
	d, err := time.ParseInLocation(time.DateOnly, date, WIB)
	if err != nil {
		return nil
	}
	return rfc(at(d, daySessions(d.Weekday())[1].end))
}

// screenBars are code's completed bars for a screen: intraday on day, the bars before it (the store must
// cover the session before day); eod for day, the bars through day (the last one must be day's).
func (s *Service) screenBars(code, mode string, day time.Time) []Bar {
	sb, ok := s.bars.get(code)
	if !ok {
		return nil
	}
	if mode == ModeIntraday {
		if !barsCover(sb.fetchedAt, prevTradingDay(day)) {
			return nil
		}
		return completedBars(sb.bars, day)
	}
	if !barsCover(sb.fetchedAt, day) {
		return nil
	}
	bars := completedBars(sb.bars, day.AddDate(0, 0, 1))
	if len(bars) == 0 || !barDay(bars[len(bars)-1]).Equal(at(day, clock{0, 0})) {
		return nil // no bar for that session (suspended, or the fetch ran before it)
	}
	return bars
}

// screenCodes is the quoted set (universe, watchlist, holdings; ≤ 180, as bars.daily) and each code's role.
func (s *Service) screenCodes() ([]string, map[string]codeRole) {
	codes, _ := s.barCodes()
	roles := make(map[string]codeRole, len(codes))
	u := s.uni()
	st, _ := s.snapshot()
	for _, c := range codes {
		r := codeRole{Watch: st.watchIndex(c) >= 0, Held: st.holdingIndex(c) >= 0}
		r.Screened = u.InIndex(c) || r.Watch
		roles[c] = r
	}
	return codes, roles
}

// screenName names a code: the stored watchlist/holding name, the bundled list, else the bar store's.
func (s *Service) screenName(code string, names map[string]string) string {
	if n := names[code]; n != "" {
		return n
	}
	if b, ok := s.uni().Lookup(code); ok {
		return b.Name
	}
	sb, _ := s.bars.get(code)
	return sb.name
}

// compute screens every quoted code. day is the session (intraday: today; eod: the completed session);
// inputs are the intraday quotes. It returns the result and the codes_stale count.
func (s *Service) compute(mode string, day, ref time.Time, inputs map[string]liveQuote, dataAsOf *string, set ScreenerSettings) (screenResult, int) {
	codes, roles := s.screenCodes()
	evals := make([]codeEval, 0, len(codes))
	stale := 0
	for _, c := range codes {
		bars := s.screenBars(c, mode, day)
		var ev codeEval
		if mode == ModeIntraday {
			var q *liveQuote
			if lq, ok := inputs[c]; ok {
				q = &lq
			}
			ev = evalIntraday(day, bars, q, set)
			if !ev.NoBars && (ev.Mismatch || (q != nil && ev.Stale)) {
				stale++
			}
		} else {
			ev = evalEOD(bars, set)
		}
		ev.Code = c
		evals = append(evals, ev)
	}
	cands, flags, watched, sk := rank(evals, roles, set)
	names := s.knownNames()
	for i := range cands {
		cands[i].Name = s.screenName(cands[i].Code, names)
	}
	for i := range flags {
		flags[i].Name = s.screenName(flags[i].Code, names)
	}
	res := screenResult{Mode: mode, SessionDate: dayString(day), ComputedAt: *rfc(ref), DataAsOf: dataAsOf, Skipped: sk,
		Candidates: cands, Flags: flags, Watched: watched}
	if mode == ModeIntraday {
		res.Inputs = inputs
	}
	return res, stale
}

// --- install, hits ---

// signalDataTime is the data time a signal stands on: the scan's for today's intraday signals, else its
// session's close.
func signalDataTime(g Signal, res *screenResult) string {
	if res.Mode == ModeIntraday && g.Date == res.SessionDate && res.DataAsOf != nil {
		return *res.DataAsOf
	}
	if t := closeTime(g.Date); t != nil {
		return *t
	}
	return res.ComputedAt
}

// installLocked keeps res as the result of its mode (and run as the mode's last run when given), fills
// first_at from the hits log and logs each first fire with its origin. The caller holds mu and saves.
func (sc *screener) installLocked(res screenResult, run *screenRun, origin string) {
	now := *rfc(sc.svc.now())
	seen := make(map[string]int, len(sc.doc.Hits)) // key → the hit's index in the log
	for i, h := range sc.doc.Hits {
		seen[h.Date+"|"+h.Code+"|"+h.Setup+"|"+h.Dir] = i
	}
	upgraded := map[int]bool{} // rerank-only hits fired again by a scan or eod run
	fresh := origin == HitScan || origin == HitEOD
	mark := func(code string, watch, held bool, score float64, sigs []Signal) {
		for i := range sigs {
			g := &sigs[i]
			key := g.Date + "|" + code + "|" + g.ID + "|" + g.Dir
			if at, ok := seen[key]; ok {
				h := &sc.doc.Hits[at]
				if fresh && h.Origin == HitRerank && !upgraded[at] {
					upgraded[at] = true
				}
				t := h.DataAsOf
				g.FirstAt = &t
				continue
			}
			sc.doc.Seq++
			h := ScreenHit{Seq: sc.doc.Seq, Date: g.Date, Code: code, Setup: g.ID, Dir: g.Dir, Mode: res.Mode, Origin: origin, At: now,
				DataAsOf: signalDataTime(*g, &res), Watch: watch, Held: held, Score: score}
			sc.doc.Hits = append(sc.doc.Hits, h)
			seen[key] = len(sc.doc.Hits) - 1
			t := h.DataAsOf
			g.FirstAt = &t
		}
	}
	for i := range res.Candidates {
		c := &res.Candidates[i]
		mark(c.Code, c.InWatchlist, c.Held, c.Score, c.Setups)
	}
	for i := range res.Flags {
		f := &res.Flags[i]
		mark(f.Code, f.InWatchlist, f.Held, 0, f.Setups)
	}
	for i := range res.Watched {
		w := &res.Watched[i]
		mark(w.Code, w.Watch, w.Held, w.Score, w.Setups)
	}
	sc.upgradeHitsLocked(upgraded, origin, now)
	if n := len(sc.doc.Hits); n > maxScreenHits {
		sc.doc.Hits = slices.Clone(sc.doc.Hits[n-maxScreenHits:])
	}
	if res.Mode == ModeIntraday {
		sc.doc.LastIntraday = &res
	} else {
		eod := slices.DeleteFunc(slices.Clone(sc.doc.EOD), func(r screenResult) bool { return r.SessionDate == res.SessionDate })
		eod = append(eod, res)
		slices.SortFunc(eod, func(a, b screenResult) int { return cmp.Compare(a.SessionDate, b.SessionDate) })
		if len(eod) > maxEODResults {
			eod = eod[len(eod)-maxEODResults:]
		}
		sc.doc.EOD = eod
	}
	if run != nil {
		sc.doc.Runs[res.Mode] = *run
	}
}

// upgradeHitsLocked re-logs the hits at the given indexes under origin with a new seq at the end of the log, so
// the alerts cursor reads them again; their data time stays the first fire's.
func (sc *screener) upgradeHitsLocked(at map[int]bool, origin, now string) {
	if len(at) == 0 {
		return
	}
	var moved []ScreenHit
	kept := make([]ScreenHit, 0, len(sc.doc.Hits))
	for i, h := range sc.doc.Hits {
		if !at[i] {
			kept = append(kept, h)
			continue
		}
		sc.doc.Seq++
		h.Seq, h.Origin, h.At = sc.doc.Seq, origin, now
		moved = append(moved, h)
	}
	sc.doc.Hits = append(kept, moved...)
}

// failRunLocked records a run that did not replace the result.
func (sc *screener) failRunLocked(mode string, run screenRun) {
	run.Stale = true
	sc.doc.Runs[mode] = run
	_ = sc.saveLocked()
}

func (sc *screener) latestEODLocked() *screenResult {
	if n := len(sc.doc.EOD); n > 0 {
		r := sc.doc.EOD[n-1]
		return &r
	}
	return nil
}

// --- EOD ---

// screenEOD screens the last completed session from the store. force: also when that session has a result
// (after bars.daily refreshed the store). Nothing is installed when no code has bars for it.
func (sc *screener) screenEOD(now time.Time, force bool) {
	day := eodDay(now)
	date := dayString(day)
	sc.mu.Lock()
	have := false
	if r := sc.latestEODLocked(); r != nil && r.SessionDate == date {
		have = true
	}
	sc.mu.Unlock()
	if have && !force {
		return
	}
	codes, _ := sc.svc.screenCodes()
	ok := 0
	for _, c := range codes {
		if len(sc.svc.screenBars(c, ModeEOD, day)) >= minScreenBars {
			ok++
		}
	}
	if ok == 0 {
		if force {
			sc.mu.Lock()
			sc.failRunLocked(ModeEOD, screenRun{LastRun: *rfc(now), Status: JobStatusFailed, Error: "no_bars"})
			sc.mu.Unlock()
		}
		return
	}
	set, rev := sc.settingsRev()
	var res screenResult
	run := screenRun{LastRun: *rfc(now), Status: JobStatusOK, CodesOK: ok}
	sc.installComputed(set, rev, func(set ScreenerSettings) screenResult {
		res, _ = sc.svc.compute(ModeEOD, day, now, nil, closeTime(date), set)
		return res
	}, &run, HitEOD, nil)
	sc.svc.log.Info("stocks: screener eod", "session", date, "codes", ok, "candidates", len(res.Candidates), "flags", len(res.Flags))
	if force {
		// After bars.daily or a manual scan: its hits may alert at once. A GET's screen leaves them to the
		// alerts job (5 min), so a phone's request never waits on an automatic analysis' Hermes check.
		sc.svc.alerts.afterHits()
	}
}

// --- intraday scan ---

func (sc *screener) begin(mode string, now time.Time, manual bool) error {
	sc.mu.Lock()
	defer sc.mu.Unlock()
	if sc.running {
		return errScanRunning
	}
	if manual {
		if d := scanCooldown - now.Sub(sc.lastStart); !sc.lastStart.IsZero() && d > 0 {
			return &apiError{status: errTooSoon.status, code: errTooSoon.code, retryAfter: d}
		}
	}
	sc.running, sc.runningMode, sc.lastStart = true, mode, now
	return nil
}

func (sc *screener) end() {
	sc.mu.Lock()
	defer sc.mu.Unlock()
	sc.running, sc.runningMode = false, ""
}

// usableQuote applies the no-signal rules: not stale, a market time on day and not older than 25 min.
func usableQuote(q Quote, day, now time.Time) bool {
	if q.Stale || q.MarketTime == nil {
		return false
	}
	tm, err := time.Parse(time.RFC3339, *q.MarketTime)
	return err == nil && sameDay(tm, day) && !tm.Before(now.Add(-quoteMaxAge))
}

// dayOpen is the open of day's bar in a daily chart (0 when absent).
func dayOpen(bars []Bar, day time.Time) int64 {
	for _, b := range slices.Backward(bars) {
		if barDay(b).Equal(day) {
			return b.O
		}
	}
	return 0
}

// scanIntraday is one intraday scan (the screener.scan job body and the manual scan).
func (sc *screener) scanIntraday(ctx context.Context) error {
	s := sc.svc
	began := time.Now()
	now := s.now().In(WIB)
	day := at(now, clock{0, 0})
	set, rev := sc.settingsRev()
	codes, roles := s.screenCodes()
	barsOf := map[string][]Bar{}
	var want []string
	for _, c := range codes {
		if b := s.screenBars(c, ModeIntraday, day); len(b) >= minScreenBars {
			barsOf[c] = b
			want = append(want, c)
		}
	}
	if len(want) == 0 {
		sc.mu.Lock()
		sc.failRunLocked(ModeIntraday, screenRun{LastRun: *rfc(now), Status: JobStatusFailed, Error: "no_bars"})
		sc.mu.Unlock()
		s.log.Info("stocks: screener scan skipped: no fresh daily bars yet (bars.daily has not run)")
		return &JobError{Code: "no_bars"}
	}
	quotes := map[string]Quote{}
	spark, charts, failed := 0, 0, 0
	asked := false
	var stop error
	pace := func() error {
		if !asked {
			return nil
		}
		return s.barsPace(ctx)
	}
	for i := 0; i < len(want); i += sparkBatch {
		if err := pace(); err != nil {
			stop = err
			break
		}
		batch := want[i:min(i+sparkBatch, len(want))]
		res, called, srcErr := s.market.quotesReport(ctx, batch)
		if called {
			spark++
			asked = true
		}
		var be *BlockedError
		if errors.As(srcErr, &be) || ctx.Err() != nil {
			stop = cmp.Or(ctx.Err(), srcErr)
			break
		}
		for _, c := range batch {
			if q, ok := res.Quotes[c]; ok {
				quotes[c] = q
			} else {
				failed++
			}
		}
	}
	logScan := func(status, code string, candidates, flags int) {
		s.log.Info("stocks: screener scan", "codes", len(want), "quotes", len(quotes), "failed", failed, "spark_calls", spark,
			"chart_calls", charts, "status", status, "error", code, "candidates", candidates, "flags", flags,
			"duration_ms", time.Since(began).Milliseconds())
	}
	if stop != nil || len(quotes) == 0 {
		_, code := jobOutcome(stop)
		if stop == nil {
			code = "codes_failed"
		}
		sc.mu.Lock()
		sc.failRunLocked(ModeIntraday, screenRun{LastRun: *rfc(now), Status: JobStatusFailed, Error: code, CodesFailed: failed})
		sc.mu.Unlock()
		logScan(JobStatusFailed, code, 0, 0)
		return &JobError{Code: code}
	}
	var asOf time.Time
	for _, q := range quotes {
		if q.MarketTime == nil {
			continue
		}
		if tm, err := time.Parse(time.RFC3339, *q.MarketTime); err == nil && sameDay(tm, day) && tm.After(asOf) {
			asOf = tm
		}
	}
	var dataAsOf *string
	if !asOf.IsZero() {
		dataAsOf = rfc(asOf)
	}
	// The day's open: one daily chart per liquid screened or watched/held code, once a day.
	var openStop error
	if set.Setups.Gap.On && !asOf.IsZero() && !asOf.Before(day.Add(openFetchDataTime*time.Minute)) {
		for _, c := range want {
			r := roles[c]
			liquid := r.Screened && (set.MinValueBn == 0 || valueTraded20(barsOf[c]) >= set.MinValueBn)
			if _, ok := quotes[c]; !ok || !(liquid || r.Watch || r.Held) || !sc.needOpen(day, c) {
				continue
			}
			if err := pace(); err != nil {
				openStop = err
				break
			}
			ch, err := s.market.ChartDirect(ctx, c, openRange)
			charts++
			asked = true
			var be *BlockedError
			if errors.As(err, &be) || ctx.Err() != nil {
				openStop = cmp.Or(ctx.Err(), err)
				break
			}
			sc.setOpen(day, c, ch.Bars, err)
		}
	}
	opens := sc.opensFor(day)
	inputs := make(map[string]liveQuote, len(quotes))
	for c, q := range quotes {
		lq := liveQuote{Price: q.Price, PrevClose: q.PrevClose, Volume: q.Volume, Open: opens[c], Usable: usableQuote(q, day, now)}
		if q.MarketTime != nil {
			lq.MarketTime = *q.MarketTime
		}
		inputs[c] = lq
	}
	run := screenRun{LastRun: *rfc(now), Status: JobStatusOK, CodesFailed: failed}
	var out error
	switch {
	case openStop != nil:
		_, code := jobOutcome(openStop)
		run.Status, run.Error = JobStatusPartial, code
		out = &JobError{Code: code, Partial: true}
	case failed > 0:
		run.Status, run.Error = JobStatusPartial, "codes_failed"
		out = &JobError{Code: "codes_failed", Partial: true}
	}
	var res screenResult
	sc.installComputed(set, rev, func(set ScreenerSettings) screenResult {
		var stale int
		res, stale = s.compute(ModeIntraday, day, now, inputs, dataAsOf, set)
		run.CodesOK, run.CodesStale = len(quotes)-stale, stale
		return res
	}, &run, HitScan, nil)
	logScan(run.Status, run.Error, len(res.Candidates), len(res.Flags))
	s.alerts.afterScan(now, quotes)
	return out
}

func (sc *screener) resetOpensLocked(day time.Time) {
	if d := dayString(day); sc.opensDay != d {
		sc.opensDay, sc.opens, sc.opensDone, sc.opensTries = d, map[string]int64{}, map[string]bool{}, map[string]int{}
	}
}

// needOpen: code's open is still unknown today and its reads have not all failed (TSC2 review MINOR-4: a read at
// 09:05 may come before Yahoo has today's daily bar; a later scan asks again).
func (sc *screener) needOpen(day time.Time, code string) bool {
	sc.mu.Lock()
	defer sc.mu.Unlock()
	sc.resetOpensLocked(day)
	return !sc.opensDone[code] && sc.opensTries[code] < maxOpenTries
}

func (sc *screener) setOpen(day time.Time, code string, bars []Bar, err error) {
	sc.mu.Lock()
	defer sc.mu.Unlock()
	sc.resetOpensLocked(day)
	switch o := dayOpen(bars, day); {
	case err == nil && o > 0:
		sc.opens[code], sc.opensDone[code] = o, true
	case errors.Is(err, ErrUnknownSymbol):
		sc.opensDone[code] = true // never answers: no retry
	default:
		sc.opensTries[code]++
	}
}

func (sc *screener) opensFor(day time.Time) map[string]int64 {
	sc.mu.Lock()
	defer sc.mu.Unlock()
	sc.resetOpensLocked(day)
	return maps.Clone(sc.opens)
}

// runScanJob is the screener.scan job body. A manual scan in progress does the same work: the slot is skipped.
func (s *Service) runScanJob(ctx context.Context, _ JobRun) error {
	if err := s.screen.begin(ModeIntraday, s.now(), false); err != nil {
		s.log.Info("stocks: screener slot skipped: a manual scan is running")
		return nil
	}
	defer s.screen.end()
	return s.screen.scanIntraday(ctx)
}

// --- re-rank (settings change) ---

// rerank recomputes the kept intraday result (from its stored quotes) and the latest EOD result (from the
// store) with set. No Yahoo call.
// rev is the revision set was saved at: a later PUT's re-rank wins (installComputed), and a result a scan or
// eod screen installed meanwhile is never replaced by the re-rank of the older one.
func (sc *screener) rerank(set ScreenerSettings, rev int64) {
	s := sc.svc
	sc.mu.Lock()
	li, eod := sc.doc.LastIntraday, sc.latestEODLocked()
	sc.mu.Unlock()
	if li != nil {
		day, err1 := time.ParseInLocation(time.DateOnly, li.SessionDate, WIB)
		ref, err2 := time.Parse(time.RFC3339, li.ComputedAt)
		if err1 == nil && err2 == nil {
			sc.installComputed(set, rev, func(set ScreenerSettings) screenResult {
				r, _ := s.compute(ModeIntraday, day, ref, li.Inputs, li.DataAsOf, set)
				return r
			}, nil, HitRerank, func() bool { return sc.doc.LastIntraday != nil && sc.doc.LastIntraday.ComputedAt == li.ComputedAt })
		}
	}
	if eod != nil {
		if day, err := time.ParseInLocation(time.DateOnly, eod.SessionDate, WIB); err == nil {
			sc.installComputed(set, rev, func(set ScreenerSettings) screenResult {
				r, _ := s.compute(ModeEOD, day, s.now(), nil, eod.DataAsOf, set)
				r.ComputedAt = eod.ComputedAt
				return r
			}, nil, HitRerank, func() bool {
				cur := sc.latestEODLocked()
				return cur != nil && cur.SessionDate == eod.SessionDate && cur.ComputedAt == eod.ComputedAt
			})
		}
	}
}

// --- HTTP ---

type screenerUniverse struct {
	Count   int    `json:"count"`
	Source  string `json:"source"`
	ValidTo string `json:"valid_to"`
	Expired bool   `json:"expired"`
}

type screenScanView struct {
	Running     bool    `json:"running"`
	LastRun     *string `json:"last_run"`
	NextRun     *string `json:"next_run"`
	Status      *string `json:"status"`
	Error       *string `json:"error"`
	CodesOK     int     `json:"codes_ok"`
	CodesFailed int     `json:"codes_failed"`
	CodesStale  int     `json:"codes_stale"`
}

type screenerBody struct {
	At          string            `json:"at"`
	Mode        string            `json:"mode"`
	SessionDate *string           `json:"session_date"`
	DataAsOf    *string           `json:"data_as_of"`
	Stale       bool              `json:"stale"`
	Market      MarketStatus      `json:"market"`
	Universe    screenerUniverse  `json:"universe"`
	Scan        screenScanView    `json:"scan"`
	Skipped     screenSkipped     `json:"skipped"`
	Candidates  []ScreenCandidate `json:"candidates"`
	Flags       []ScreenFlag      `json:"flags"`
}

func sptr(v string) *string { return &v }

func (sc *scheduler) nextRunOf(id string) *string {
	for _, v := range sc.views() {
		if v.ID == id {
			return v.NextRun
		}
	}
	return nil
}

// view renders mode ("" = intraday when a scan ran on today's trading day, else eod). Watchlist/holding
// marks and names follow the current lists; flags only for codes still watched or held.
func (sc *screener) view(mode string, now time.Time) screenerBody {
	s := sc.svc
	st, stErr := s.snapshot()
	names := s.knownNames()
	nextScan, nextBars := s.sched.nextRunOf(ScanJobID), s.sched.nextRunOf(BarsJobID)
	u := s.uni()
	today := dayString(now)
	sc.mu.Lock()
	defer sc.mu.Unlock()
	if mode == "" {
		mode = ModeEOD
		if li := sc.doc.LastIntraday; li != nil && li.SessionDate == today && IsTradingDay(now) {
			mode = ModeIntraday
		}
	}
	var res *screenResult
	next := nextBars
	if mode == ModeIntraday {
		res, next = sc.doc.LastIntraday, nextScan
	} else {
		res = sc.latestEODLocked()
	}
	run := sc.doc.Runs[mode]
	b := screenerBody{At: *rfc(now), Mode: mode, Market: Status(now), Stale: run.Stale,
		Universe:   screenerUniverse{Count: len(u.Members()), Source: u.Source, ValidTo: u.ValidTo, Expired: u.Expired(now)},
		Scan:       screenScanView{Running: sc.running && sc.runningMode == mode, LastRun: nonEmpty(run.LastRun), NextRun: next, Status: nonEmpty(run.Status), Error: nonEmpty(run.Error), CodesOK: run.CodesOK, CodesFailed: run.CodesFailed, CodesStale: run.CodesStale},
		Candidates: []ScreenCandidate{}, Flags: []ScreenFlag{}}
	if res == nil {
		if b.Scan.Error == nil && mode == ModeEOD {
			b.Scan.Error = sptr("no_bars")
		}
		return b
	}
	b.SessionDate, b.DataAsOf, b.Skipped = sptr(res.SessionDate), res.DataAsOf, res.Skipped
	watch := func(c string) bool { return stErr == nil && st.watchIndex(c) >= 0 }
	held := func(c string) bool { return stErr == nil && st.holdingIndex(c) >= 0 }
	for _, c := range res.Candidates {
		c.InWatchlist, c.Held, c.Name = watch(c.Code), held(c.Code), s.screenName(c.Code, names)
		b.Candidates = append(b.Candidates, c)
	}
	for _, f := range res.Flags {
		f.InWatchlist, f.Held, f.Name = watch(f.Code), held(f.Code), s.screenName(f.Code, names)
		if f.InWatchlist || f.Held {
			b.Flags = append(b.Flags, f)
		}
	}
	return b
}

func (s *Service) getScreener(r *http.Request) (int, any, error) {
	mode := r.URL.Query().Get("mode")
	if mode != "" && mode != ModeIntraday && mode != ModeEOD {
		return 0, nil, errBadRequest
	}
	now := s.now().In(WIB)
	s.screen.screenEOD(now, false) // the latest completed session, once its bars are stored
	return http.StatusOK, s.screen.view(mode, now), nil
}

func (s *Service) getScreenerSettings(*http.Request) (int, any, error) {
	return http.StatusOK, s.screen.settings(), nil
}

func (s *Service) putScreenerSettings(r *http.Request) (int, any, error) {
	set, err := decodeScreenerSettings(r)
	if err != nil {
		return 0, nil, err
	}
	sc := s.screen
	sc.mu.Lock()
	old := sc.doc.Settings
	sc.doc.Settings = &set
	if err := sc.saveLocked(); err != nil {
		sc.doc.Settings = old
		sc.mu.Unlock()
		return 0, nil, err
	}
	sc.setRev++
	rev := sc.setRev
	sc.mu.Unlock()
	sc.rerank(set, rev)
	return http.StatusOK, set, nil
}

type scanStartBody struct {
	Mode string         `json:"mode"`
	Scan screenScanView `json:"scan"`
}

// postScreenerScan starts a manual scan: intraday inside a scan window, else the EOD screen from the store.
func (s *Service) postScreenerScan(*http.Request) (int, any, error) {
	now := s.now().In(WIB)
	mode := ModeEOD
	if inScanWindow(now) {
		mode = ModeIntraday
	}
	sc := s.screen
	if err := sc.begin(mode, now, true); err != nil {
		return 0, nil, err
	}
	sc.wg.Go(func() {
		defer sc.end()
		if mode == ModeIntraday {
			_ = sc.scanIntraday(sc.ctx)
			return
		}
		sc.screenEOD(s.now(), true)
	})
	return http.StatusAccepted, scanStartBody{Mode: mode, Scan: s.screen.view(mode, now).Scan}, nil
}
