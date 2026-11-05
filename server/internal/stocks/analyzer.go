package stocks

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"net/http"
	"slices"
	"sync"
	"time"

	"github.com/justcallmesu/aristotle/server/internal/hermes"
	"github.com/justcallmesu/aristotle/server/internal/statefile"
)

// The emiten analyzer (spec Q96-Q103, P5-P7; protocol §20 "Analyzer"): POST /v1/stocks/analyze/{code}
// starts a job that computes the indicators, asks Hermes once (plus at most one repair turn) for a strict
// JSON verdict, checks it and keeps the last good result per code in stocks-analyses.json. Logs carry
// the job id, code, state, duration and error code only: never the prompt, holdings or verdict text.

// AnalystProfile is the Hermes profile the analyzer runs in (spec Q102).
const AnalystProfile = "aristotle-stocks"

const (
	analysesFile        = "stocks-analyses.json"
	analysesVersion     = 1
	maxAnalyses         = 50  // kept results, newest wins
	maxFailedSessions   = 100 // remembered failed sessions awaiting the sweep
	maxRunningJobs      = 2
	failedSessionTTL    = 7 * 24 * time.Hour
	sweepInterval       = 24 * time.Hour
	analysisNewsTimeout = 20 * time.Second
	analysisCleanup     = 15 * time.Second
	analyzerStopWait    = 5 * time.Second
)

// Job states.
const (
	JobIdle    = "idle"
	JobRunning = "running"
	JobDone    = "done"
	JobFailed  = "failed"
)

var (
	errAnalysisRunning = &apiError{status: http.StatusConflict, code: "analysis_running"}
	errAnalyzerBusy    = &apiError{status: http.StatusTooManyRequests, code: "busy"}
	errHermesDown      = &apiError{status: http.StatusServiceUnavailable, code: "hermes_unavailable"}
	errNotSetUp        = &apiError{status: http.StatusServiceUnavailable, code: "analyzer_not_set_up"}
	errNoDataAPI       = &apiError{status: http.StatusUnprocessableEntity, code: "no_data"}
)

// Analyst is the Hermes seam of the analyzer (HermesAnalyst in production, a fake in tests).
type Analyst interface {
	// Check reports whether Hermes runs and serves AnalystProfile (hermes.ErrUnknownProfile when not).
	Check(ctx context.Context) error
	// Open creates one tool-less analysis session.
	Open(ctx context.Context) (AnalystSession, error)
	// DeleteSession removes a session left by an earlier failed job.
	DeleteSession(ctx context.Context, id string) error
}

// AnalystSession is one open analysis session (*hermes.AnalysisSession).
type AnalystSession interface {
	ID() string
	Model() string
	Ask(ctx context.Context, prompt string) (string, error)
	Delete(ctx context.Context) error
	Close()
}

// HermesAnalyst runs analyses over c on a socket scoped to AnalystProfile.
func HermesAnalyst(c *hermes.Connector) Analyst { return hermesAnalyst{c} }

type hermesAnalyst struct{ c *hermes.Connector }

func (h hermesAnalyst) Check(ctx context.Context) error {
	return h.c.ServesProfile(ctx, AnalystProfile)
}

func (h hermesAnalyst) Open(ctx context.Context) (AnalystSession, error) {
	s, err := h.c.OpenAnalysis(ctx, hermes.AnalysisOptions{Profile: AnalystProfile})
	if err != nil {
		return nil, err
	}
	return s, nil
}

func (h hermesAnalyst) DeleteSession(ctx context.Context, id string) error {
	return h.c.DeleteAnalysisSession(ctx, AnalystProfile, id)
}

// AnalysisResult is one kept verdict (GET /v1/stocks/analysis/{code} "result").
type AnalysisResult struct {
	Code        string         `json:"code"`
	Name        string         `json:"name"`
	Held        bool           `json:"held"`
	Lang        string         `json:"lang"`
	AnalyzedAt  string         `json:"analyzed_at"`
	DataAsOf    string         `json:"data_as_of"`
	SessionDate string         `json:"session_date"`
	MarketState string         `json:"market_state"`
	Model       string         `json:"model"`
	DurationMS  int64          `json:"duration_ms"`
	Verdict     string         `json:"verdict"`
	Confidence  string         `json:"confidence"`
	EntryLow    *int64         `json:"entry_low"`
	EntryHigh   *int64         `json:"entry_high"`
	Target      *int64         `json:"target"`
	Stop        *int64         `json:"stop"`
	Summary     string         `json:"summary"`
	Reasons     []Reason       `json:"reasons"`
	Flags       []string       `json:"flags"`
	Repaired    bool           `json:"repaired"`
	Price       int64          `json:"price"`
	ARA         *int64         `json:"ara"`
	ARB         *int64         `json:"arb"`
	Indicators  map[string]any `json:"indicators"`
	Levels      map[string]any `json:"levels"`
	News        []NewsRef      `json:"news"`
}

type failedSession struct {
	SessionID string `json:"session_id"`
	FailedAt  string `json:"failed_at"`
}

// analysesState is stocks-analyses.json.
type analysesState struct {
	Version  int                       `json:"version"`
	Analyses map[string]AnalysisResult `json:"analyses"`
	Failed   []failedSession           `json:"failed_sessions"`
}

type analysisJob struct {
	ID         string
	State      string
	StartedAt  time.Time
	FinishedAt time.Time
	Error      string
}

// analyzer owns the jobs and the kept results.
type analyzer struct {
	svc     *Service
	analyst Analyst
	after   func(time.Duration) <-chan time.Time
	ctx     context.Context
	cancel  context.CancelFunc
	wg      sync.WaitGroup

	mu      sync.Mutex
	jobs    map[string]*analysisJob // the latest job per code since the start
	running int
	st      analysesState
	persist bool // false when the file was unusable: it is left alone and results stay in memory
}

func newAnalyzer(s *Service, a Analyst, after func(time.Duration) <-chan time.Time) *analyzer {
	ctx, cancel := context.WithCancel(context.Background())
	an := &analyzer{svc: s, analyst: a, after: after, ctx: ctx, cancel: cancel, jobs: map[string]*analysisJob{}, persist: true}
	if an.after == nil {
		an.after = time.After
	}
	st, err := loadAnalyses(s.dir)
	if err != nil {
		an.persist = false
		s.log.Error("stocks: analyses file unusable; analyses are kept in memory until it is fixed or removed", "file", analysesFile, "err", err)
		st = emptyAnalyses()
	}
	an.st = st
	if a != nil {
		an.wg.Go(an.sweepLoop)
	}
	return an
}

func emptyAnalyses() analysesState {
	return analysesState{Version: analysesVersion, Analyses: map[string]AnalysisResult{}, Failed: []failedSession{}}
}

func loadAnalyses(dir string) (analysesState, error) {
	data, err := statefile.Read(dir, analysesFile)
	if errors.Is(err, fs.ErrNotExist) {
		return emptyAnalyses(), nil
	}
	if err != nil {
		return analysesState{}, err
	}
	var st analysesState
	dec := json.NewDecoder(bytes.NewReader(data))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&st); err != nil {
		return analysesState{}, fmt.Errorf("%s: %w", analysesFile, err)
	}
	if st.Version != analysesVersion {
		return analysesState{}, fmt.Errorf("%s: version %d, want %d", analysesFile, st.Version, analysesVersion)
	}
	if st.Analyses == nil {
		st.Analyses = map[string]AnalysisResult{}
	}
	if st.Failed == nil {
		st.Failed = []failedSession{}
	}
	return st, nil
}

// saveLocked writes the file; the caller holds mu.
func (an *analyzer) saveLocked() {
	if !an.persist {
		return
	}
	data, err := json.MarshalIndent(an.st, "", "  ")
	if err == nil {
		err = statefile.WriteAtomic(an.svc.dir, analysesFile, append(data, '\n'))
	}
	if err != nil {
		an.svc.log.Error("stocks: saving analyses failed", "err", err)
	}
}

// close cancels running jobs (each interrupts its turn) and waits for them, bounded.
func (an *analyzer) close() {
	an.cancel()
	done := make(chan struct{})
	go func() { an.wg.Wait(); close(done) }()
	select {
	case <-done:
	case <-time.After(analyzerStopWait):
		an.svc.log.Warn("stocks: analysis jobs did not stop in time")
	}
}

// --- HTTP ---

type analyzeBody struct {
	JobID     string `json:"job_id"`
	State     string `json:"state"`
	StartedAt string `json:"started_at"`
}

type analysisBody struct {
	Code       string          `json:"code"`
	State      string          `json:"state"`
	JobID      *string         `json:"job_id"`
	StartedAt  *string         `json:"started_at"`
	FinishedAt *string         `json:"finished_at"`
	Error      *string         `json:"error"`
	Result     *AnalysisResult `json:"result"`
}

func (s *Service) postAnalyze(r *http.Request) (int, any, error) {
	code, err := normCode(r.PathValue("code"))
	if err != nil {
		return 0, nil, err
	}
	var body struct {
		Lang string `json:"lang"`
	}
	if err := decodeBody(r, &body); err != nil || (body.Lang != analysisLangID && body.Lang != analysisLangEN) {
		return 0, nil, errBadRequest
	}
	return s.analyzer.start(r.Context(), code, body.Lang)
}

func (s *Service) getAnalysis(r *http.Request) (int, any, error) {
	code, err := normCode(r.PathValue("code"))
	if err != nil {
		return 0, nil, err
	}
	return http.StatusOK, s.analyzer.status(code), nil
}

func (an *analyzer) status(code string) analysisBody {
	an.mu.Lock()
	defer an.mu.Unlock()
	body := analysisBody{Code: code, State: JobIdle}
	if j := an.jobs[code]; j != nil {
		body.State, body.JobID, body.StartedAt = j.State, &j.ID, rfc(j.StartedAt)
		if !j.FinishedAt.IsZero() {
			body.FinishedAt = rfc(j.FinishedAt)
		}
		if j.Error != "" {
			e := j.Error
			body.Error = &e
		}
	}
	if res, ok := an.st.Analyses[code]; ok {
		body.Result = &res
	}
	return body
}

// start reserves a slot, runs the checks a phone must hear about at once (state, Hermes, bars) and
// launches the job.
func (an *analyzer) start(ctx context.Context, code, lang string) (int, any, error) {
	if an.analyst == nil {
		return 0, nil, errNotSetUp
	}
	job, prev, err := an.reserve(code)
	if err != nil {
		return 0, nil, err
	}
	in, daily, err := an.preflight(ctx, code, lang)
	if err != nil {
		an.release(code, prev)
		return 0, nil, err
	}
	an.wg.Go(func() { an.run(job, code, in, daily) })
	return http.StatusAccepted, analyzeBody{JobID: job.ID, State: JobRunning, StartedAt: *rfc(job.StartedAt)}, nil
}

func (an *analyzer) reserve(code string) (job, prev *analysisJob, err error) {
	an.mu.Lock()
	defer an.mu.Unlock()
	prev = an.jobs[code]
	if prev != nil && prev.State == JobRunning {
		return nil, nil, errAnalysisRunning
	}
	if an.running >= maxRunningJobs {
		return nil, nil, errAnalyzerBusy
	}
	job = &analysisJob{ID: "an_" + randomHex(6), State: JobRunning, StartedAt: an.svc.now()}
	an.jobs[code] = job
	an.running++
	return job, prev, nil
}

func (an *analyzer) release(code string, prev *analysisJob) {
	an.mu.Lock()
	defer an.mu.Unlock()
	an.running--
	if prev == nil {
		delete(an.jobs, code)
	} else {
		an.jobs[code] = prev
	}
}

// preflight: the user's state (held or not), Hermes and the profile, then the daily bars.
func (an *analyzer) preflight(ctx context.Context, code, lang string) (analysisInput, []Bar, error) {
	s := an.svc
	st, err := s.snapshot()
	if err != nil {
		return analysisInput{}, nil, err
	}
	if err := an.analyst.Check(ctx); err != nil {
		if errors.Is(err, hermes.ErrUnknownProfile) {
			return analysisInput{}, nil, errNotSetUp
		}
		return analysisInput{}, nil, errHermesDown
	}
	ch, err := s.market.Chart(ctx, code, "1y")
	if err != nil {
		return analysisInput{}, nil, err
	}
	if len(completedBars(ch.Bars, SessionDate(s.now()))) == 0 {
		return analysisInput{}, nil, errNoDataAPI
	}
	in := analysisInput{Code: code, Lang: lang, Name: an.name(st, code, ch.Name)}
	if i := st.holdingIndex(code); i >= 0 {
		h := st.Holdings[i]
		in.Holding = &HoldingInput{Lots: h.Lots, AvgPrice: h.AvgPrice}
	}
	return in, ch.Bars, nil
}

func (an *analyzer) name(st State, code, fromChart string) string {
	if i := st.watchIndex(code); i >= 0 {
		return st.Watchlist[i].Name
	}
	if i := st.holdingIndex(code); i >= 0 {
		return st.Holdings[i].Name
	}
	if b, ok := an.svc.universe.Lookup(code); ok {
		return b.Name
	}
	if fromChart != "" {
		return fromChart
	}
	return code
}

// run is one job: gather, compute, ask, check, keep.
func (an *analyzer) run(job *analysisJob, code string, in analysisInput, daily []Bar) {
	began := time.Now()
	sessionID, errCode := an.analyze(code, in, daily, began)
	an.svc.log.Info("stocks: analysis finished", "job", job.ID, "code", code, "state", jobState(errCode),
		"error", errCode, "duration_ms", time.Since(began).Milliseconds())
	an.finish(job, sessionID, errCode)
}

func jobState(errCode string) string {
	if errCode == "" {
		return JobDone
	}
	return JobFailed
}

// analyze keeps the result, or returns the error code and the id of the session a failure keeps.
func (an *analyzer) analyze(code string, in analysisInput, daily []Bar, began time.Time) (keptSession, errCode string) {
	in, err := an.gather(code, in, daily)
	if err != nil {
		return "", "data_unavailable"
	}
	sess, err := an.analyst.Open(an.ctx)
	if err != nil {
		return "", hermesErrorCode(err)
	}
	defer sess.Close()
	v, flags, repaired, errCode := an.converse(sess, in)
	if errCode != "" {
		return sess.ID(), errCode
	}
	res := resultFrom(in, v, flags, repaired, sess.Model(), an.svc.now(), time.Since(began))
	an.keep(code, res)
	dctx, cancel := context.WithTimeout(context.Background(), analysisCleanup)
	defer cancel()
	if err := sess.Delete(dctx); err != nil {
		an.svc.log.Warn("stocks: analysis session not deleted; the sweep retries", "code", code, "err", hermesErrorCode(err))
		an.rememberFailed(sess.ID())
	}
	return "", ""
}

// converse asks once and, when the reply fails the schema or the checks, once more with the errors.
func (an *analyzer) converse(sess AnalystSession, in analysisInput) (Verdict, []string, bool, string) {
	reply, err := sess.Ask(an.ctx, analysisPrompt(in))
	if err != nil {
		return Verdict{}, nil, false, hermesErrorCode(err)
	}
	v, flags, errs, rejects := judge(reply, in)
	if len(errs)+len(rejects) == 0 {
		return v, flags, false, ""
	}
	reply, err = sess.Ask(an.ctx, repairPrompt(append(errs, rejects...)))
	if err != nil {
		return Verdict{}, nil, true, hermesErrorCode(err)
	}
	v, flags, errs, rejects = judge(reply, in)
	switch {
	case len(errs) > 0:
		return Verdict{}, nil, true, "invalid_verdict"
	case len(rejects) > 0:
		return Verdict{}, nil, true, "verdict_rejected"
	}
	return v, flags, true, ""
}

func judge(reply string, in analysisInput) (v Verdict, flags, errs, rejects []string) {
	v, errs = parseVerdict(reply, in)
	if len(errs) > 0 {
		return v, nil, errs, nil
	}
	rejects, flags = checkVerdict(v, in.Ind)
	return v, flags, nil, rejects
}

// gather fetches today's bars, the quote and the news, and computes the indicators.
func (an *analyzer) gather(code string, in analysisInput, daily []Bar) (analysisInput, error) {
	s := an.svc
	var intraday []Bar
	if ch, err := s.market.Chart(an.ctx, code, "1d"); err == nil {
		intraday = ch.Bars
	}
	var quote *Quote
	if q, ok := s.market.Quotes(an.ctx, []string{code}).Quotes[code]; ok {
		quote = &q
	}
	ind, err := ComputeIndicators(IndicatorInput{Now: s.now(), Daily: daily, Intraday: intraday, Quote: quote})
	if err != nil {
		return in, err
	}
	in.Ind = ind
	in.AsOf = asOf(quote, intraday, daily)
	if in.Holding != nil {
		if st, err := s.snapshot(); err == nil {
			price := ind.Price
			h := Holding{Code: code, Lots: in.Holding.Lots, AvgPrice: in.Holding.AvgPrice}
			in.Holding.PLPct = Evaluate(h, &price, st.Settings).PLPct
		}
	}
	nctx, cancel := context.WithTimeout(an.ctx, analysisNewsTimeout)
	defer cancel()
	articles, _ := s.news.Items(nctx)
	in.News = newsForCode(articles, s.candidates(code), code)
	return in, nil
}

// asOf is the time of the newest price used: the quote's market time, else the last bar.
func asOf(q *Quote, intraday, daily []Bar) string {
	if q != nil && q.MarketTime != nil {
		return *q.MarketTime
	}
	last := daily[len(daily)-1].T
	if n := len(intraday); n > 0 && intraday[n-1].T > last {
		last = intraday[n-1].T
	}
	return *rfc(time.Unix(last, 0))
}

func resultFrom(in analysisInput, v Verdict, flags []string, repaired bool, model string, now time.Time, took time.Duration) AnalysisResult {
	if flags == nil {
		flags = []string{}
	}
	news := in.News
	if news == nil {
		news = []NewsRef{}
	}
	return AnalysisResult{Code: in.Code, Name: in.Name, Held: in.held(), Lang: in.Lang, AnalyzedAt: *rfc(now), DataAsOf: in.AsOf,
		SessionDate: in.Ind.SessionDate, MarketState: in.Ind.MarketState, Model: model, DurationMS: took.Milliseconds(),
		Verdict: v.Verdict, Confidence: v.Confidence, EntryLow: v.EntryLow, EntryHigh: v.EntryHigh, Target: v.Target, Stop: v.Stop,
		Summary: v.Summary, Reasons: v.Reasons, Flags: flags, Repaired: repaired, Price: in.Ind.Price, ARA: in.Ind.ARA, ARB: in.Ind.ARB,
		Indicators: in.indicatorIDs(), Levels: in.levelIDs(), News: news}
}

// hermesErrorCode maps an analysis failure to the job's error code (protocol §20).
func hermesErrorCode(err error) string {
	var te *hermes.TurnError
	switch {
	case errors.Is(err, hermes.ErrToolsPresent):
		return "tools_present"
	case errors.Is(err, hermes.ErrUnexpectedPrompt):
		return "unexpected_prompt"
	case errors.Is(err, hermes.ErrTurnTimeout):
		return "timeout"
	case errors.Is(err, hermes.ErrTurnInterrupted), errors.Is(err, context.Canceled):
		return "interrupted"
	case errors.As(err, &te):
		return "model_error"
	case errors.Is(err, hermes.ErrUnknownProfile):
		return "analyzer_not_set_up"
	default:
		return "hermes_lost"
	}
}

// keep stores a result (newest wins; at most maxAnalyses codes, the oldest dropped).
func (an *analyzer) keep(code string, res AnalysisResult) {
	an.mu.Lock()
	defer an.mu.Unlock()
	an.st.Analyses[code] = res
	for len(an.st.Analyses) > maxAnalyses {
		oldest := ""
		for c, r := range an.st.Analyses {
			if oldest == "" || r.AnalyzedAt < an.st.Analyses[oldest].AnalyzedAt {
				oldest = c
			}
		}
		delete(an.st.Analyses, oldest)
	}
	an.saveLocked()
}

func (an *analyzer) finish(job *analysisJob, sessionID, errCode string) {
	if sessionID != "" {
		an.rememberFailed(sessionID)
	}
	an.mu.Lock()
	defer an.mu.Unlock()
	an.running--
	job.FinishedAt = an.svc.now()
	job.State, job.Error = jobState(errCode), errCode
}

// rememberFailed records a kept session for the sweep (P7: failed sessions stay 7 days for debugging).
func (an *analyzer) rememberFailed(id string) {
	an.mu.Lock()
	defer an.mu.Unlock()
	an.st.Failed = append(an.st.Failed, failedSession{SessionID: id, FailedAt: *rfc(an.svc.now())})
	if n := len(an.st.Failed); n > maxFailedSessions {
		an.st.Failed = slices.Clone(an.st.Failed[n-maxFailedSessions:])
	}
	an.saveLocked()
}

// sweepLoop deletes failed sessions older than 7 days at the start and then daily.
func (an *analyzer) sweepLoop() {
	for {
		an.sweep()
		select {
		case <-an.after(sweepInterval):
		case <-an.ctx.Done():
			return
		}
	}
}

func (an *analyzer) sweep() {
	an.mu.Lock()
	cutoff := an.svc.now().Add(-failedSessionTTL)
	var due []string
	for _, f := range an.st.Failed {
		if t, err := time.Parse(time.RFC3339, f.FailedAt); err != nil || t.Before(cutoff) {
			due = append(due, f.SessionID)
		}
	}
	an.mu.Unlock()
	gone := map[string]bool{}
	for _, id := range due {
		ctx, cancel := context.WithTimeout(an.ctx, analysisCleanup)
		err := an.analyst.DeleteSession(ctx, id)
		cancel()
		if err != nil && !errors.Is(err, hermes.ErrSessionNotFound) {
			an.svc.log.Warn("stocks: sweep of old analysis sessions stopped", "err", err)
			break
		}
		gone[id] = true
	}
	if len(gone) == 0 {
		return
	}
	an.mu.Lock()
	defer an.mu.Unlock()
	an.st.Failed = slices.DeleteFunc(an.st.Failed, func(f failedSession) bool { return gone[f.SessionID] })
	an.saveLocked()
	an.svc.log.Info("stocks: old analysis sessions deleted", "count", len(gone))
}

func randomHex(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}
