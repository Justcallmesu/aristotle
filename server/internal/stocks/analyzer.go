package stocks

import (
	"bytes"
	"cmp"
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

// sweepDeleteTimeout bounds one delete of the sweep (a variable so a test need not wait 15 s).
var sweepDeleteTimeout = analysisCleanup

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
	errShuttingDown    = &apiError{status: http.StatusServiceUnavailable, code: "shutting_down"}
)

// Analysis triggers (AnalysisResult.Trigger) and the model of the automatic runs (spec Q87, Q107, Q110).
const (
	TriggerManual = "manual" // the phone's Analyze button (profile default model: Opus, Q103)
	TriggerAuto   = "auto"   // started by a setup alert (Sonnet, Q110)
	// AutoModel is pinned per session at create time for the automatic runs (hermes.AnalysisOptions.Model).
	AutoModel = "claude-sonnet-5-5"
)

// SessionOptions pick the model and run budget of one analysis session ("" / 0: the profile default, 90 s).
type SessionOptions struct {
	Model     string
	RunBudget int
}

// Analyst is the Hermes seam of the analyzer (HermesAnalyst in production, a fake in tests).
type Analyst interface {
	// Check reports whether Hermes runs and serves AnalystProfile (hermes.ErrUnknownProfile when not).
	Check(ctx context.Context) error
	// Open creates one tool-less analysis session. created hears the session id as soon as Hermes has
	// made it, before any later step can fail (also when Open then fails).
	Open(ctx context.Context, o SessionOptions, created func(id string)) (AnalystSession, error)
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

func (h hermesAnalyst) Open(ctx context.Context, o SessionOptions, created func(id string)) (AnalystSession, error) {
	s, err := h.c.OpenAnalysis(ctx, hermes.AnalysisOptions{Profile: AnalystProfile, Created: created, Model: o.Model, RunBudget: o.RunBudget})
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
	Trigger     string         `json:"trigger"` // manual | auto (results kept before TBA1 read as manual)
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

// failedSession is a Hermes session awaiting the sweep. It is recorded when session.create answers and
// dropped once the session is deleted, so FailedAt is the creation time (spec P7: kept 7 days).
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

// runOptions are a job's trigger, model and completion hook (the automatic runs' analysis alert).
type runOptions struct {
	Trigger string // TriggerManual when ""
	Model   string // "" = the profile default
	// Done, when set, hears the outcome after the job finished: the kept result, or nil and the error code.
	Done func(res *AnalysisResult, errCode string)
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
	closing bool // close has begun: no new job (and no wg.Add racing its Wait)
	st      analysesState
	persist bool // false when the file was unusable or newer: it is left alone and results stay in memory
}

func newAnalyzer(s *Service, a Analyst, after func(time.Duration) <-chan time.Time) *analyzer {
	ctx, cancel := context.WithCancel(context.Background())
	an := &analyzer{svc: s, analyst: a, after: after, ctx: ctx, cancel: cancel, jobs: map[string]*analysisJob{}, persist: true}
	if an.after == nil {
		an.after = time.After
	}
	st, err := loadAnalyses(s.dir)
	var newer *newerAnalysesError
	switch {
	case errors.As(err, &newer):
		an.persist = false
		s.log.Warn("stocks: analyses file was written by a newer server; it is read but never written, new results stay in memory",
			"file", analysesFile, "version", newer.version, "supported", analysesVersion)
	case err != nil:
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
	var head struct {
		Version int `json:"version"`
	}
	if err := json.Unmarshal(data, &head); err != nil {
		return analysesState{}, fmt.Errorf("%s: %w", analysesFile, err)
	}
	var st analysesState
	switch {
	case head.Version > analysesVersion:
		// After a downgrade: read what this version knows (unknown fields ignored), never write it back.
		if err := json.Unmarshal(data, &st); err != nil {
			return analysesState{}, fmt.Errorf("%s: %w", analysesFile, err)
		}
		err = &newerAnalysesError{version: head.Version}
	case head.Version != analysesVersion:
		return analysesState{}, fmt.Errorf("%s: version %d, want %d", analysesFile, head.Version, analysesVersion)
	default:
		dec := json.NewDecoder(bytes.NewReader(data))
		dec.DisallowUnknownFields()
		if err := dec.Decode(&st); err != nil {
			return analysesState{}, fmt.Errorf("%s: %w", analysesFile, err)
		}
	}
	if st.Analyses == nil {
		st.Analyses = map[string]AnalysisResult{}
	}
	if st.Failed == nil {
		st.Failed = []failedSession{}
	}
	for c, r := range st.Analyses {
		if r.Trigger == "" {
			r.Trigger = TriggerManual
			st.Analyses[c] = r
		}
	}
	return st, err
}

// newerAnalysesError: the file has a newer version than this server writes. loadAnalyses still returns
// what it could read; the analyzer then uses it read-only.
type newerAnalysesError struct{ version int }

func (e *newerAnalysesError) Error() string {
	return fmt.Sprintf("%s: version %d is newer than %d", analysesFile, e.version, analysesVersion)
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

// close refuses new jobs, cancels running ones (each interrupts its turn) and waits for them, bounded.
func (an *analyzer) close() {
	an.mu.Lock()
	an.closing = true
	an.mu.Unlock()
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
	s.alerts.setLang(body.Lang)
	return s.analyzer.start(r.Context(), code, body.Lang, runOptions{})
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
func (an *analyzer) start(ctx context.Context, code, lang string, o runOptions) (int, any, error) {
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
	in.Trigger, in.Model = cmp.Or(o.Trigger, TriggerManual), o.Model
	go func() {
		defer an.wg.Done() // added by reserve
		an.run(job, code, in, daily, o.Done)
	}()
	return http.StatusAccepted, analyzeBody{JobID: job.ID, State: JobRunning, StartedAt: *rfc(job.StartedAt)}, nil
}

func (an *analyzer) reserve(code string) (job, prev *analysisJob, err error) {
	an.mu.Lock()
	defer an.mu.Unlock()
	if an.closing {
		return nil, nil, errShuttingDown
	}
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
	an.wg.Add(1) // under mu: close sets closing under mu before it waits, so no Add can race that Wait
	return job, prev, nil
}

// reserveSlot takes one of the maxRunningJobs Hermes slots for a job that is not a code's analysis (the
// 08:30 brief); false when they are all taken or the analyzer is closing. releaseSlot gives it back.
func (an *analyzer) reserveSlot() bool {
	an.mu.Lock()
	defer an.mu.Unlock()
	if an.closing || an.running >= maxRunningJobs {
		return false
	}
	an.running++
	an.wg.Add(1)
	return true
}

func (an *analyzer) releaseSlot() {
	an.mu.Lock()
	defer an.mu.Unlock()
	an.running--
	an.wg.Done()
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
	an.wg.Done()
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
	daily, srcName, err := s.dailyBars(ctx, code) // the bar store when fresh, else Yahoo
	if err != nil {
		return analysisInput{}, nil, err
	}
	if len(completedBars(daily, SessionDate(s.now()))) == 0 {
		return analysisInput{}, nil, errNoDataAPI
	}
	in := analysisInput{Code: code, Lang: lang, Name: an.name(st, code, srcName)}
	if i := st.holdingIndex(code); i >= 0 {
		h := st.Holdings[i]
		in.Holding = &HoldingInput{Lots: h.Lots, AvgPrice: h.AvgPrice}
	}
	return in, daily, nil
}

func (an *analyzer) name(st State, code, fromChart string) string {
	if i := st.watchIndex(code); i >= 0 {
		return st.Watchlist[i].Name
	}
	if i := st.holdingIndex(code); i >= 0 {
		return st.Holdings[i].Name
	}
	if b, ok := an.svc.uni().Lookup(code); ok {
		return b.Name
	}
	if fromChart != "" {
		return fromChart
	}
	return code
}

// run is one job: gather, compute, ask, check, keep.
func (an *analyzer) run(job *analysisJob, code string, in analysisInput, daily []Bar, done func(*AnalysisResult, string)) {
	began := time.Now()
	res, errCode := an.analyze(code, in, daily, began)
	an.svc.log.Info("stocks: analysis finished", "job", job.ID, "code", code, "trigger", in.Trigger, "state", jobState(errCode),
		"error", errCode, "duration_ms", time.Since(began).Milliseconds())
	an.finish(job, errCode)
	if done != nil {
		done(res, errCode)
	}
}

func jobState(errCode string) string {
	if errCode == "" {
		return JobDone
	}
	return JobFailed
}

// analyze keeps the result or returns the error code. The Hermes session is recorded for the sweep the
// moment it exists and forgotten only once it is deleted, so no failure path can orphan it (P7).
func (an *analyzer) analyze(code string, in analysisInput, daily []Bar, began time.Time) (*AnalysisResult, string) {
	in, err := an.gather(code, in, daily)
	if err != nil {
		return nil, "data_unavailable"
	}
	created := ""
	sess, err := an.analyst.Open(an.ctx, SessionOptions{Model: in.Model}, func(id string) {
		created = id
		an.recordSession(id)
	})
	if err != nil {
		return nil, an.openFailed(code, created, err)
	}
	defer sess.Close()
	if in.Model != "" && sess.Model() != in.Model {
		return nil, an.modelMismatch(code, sess) // the create snapshot is read before any prompt.submit: nothing was spent
	}
	v, flags, repaired, errCode := an.converse(sess, in)
	if errCode != "" {
		return nil, errCode // the session stays recorded: kept 7 days for debugging
	}
	res := resultFrom(in, v, flags, repaired, sess.Model(), an.svc.now(), time.Since(began))
	an.keep(code, res)
	an.deleteSession(sess, "stocks: analysis session not deleted; the sweep retries", "code", code)
	return &res, ""
}

// deleteSession deletes a finished session and forgets it; when the delete fails it logs msg (plus attrs and the
// error code) and leaves the session recorded for the sweep.
func (an *analyzer) deleteSession(sess AnalystSession, msg string, attrs ...any) {
	dctx, cancel := context.WithTimeout(context.Background(), analysisCleanup)
	defer cancel()
	if err := sess.Delete(dctx); err != nil {
		an.svc.log.Warn(msg, append(attrs, "err", hermesErrorCode(err))...)
		return
	}
	an.forgetSession(sess.ID())
}

// modelMismatch aborts a run whose session does not run the pinned model (Hermes ignored the pin): the session is
// deleted unsent (kept for the sweep when the delete fails) and only the code is logged, never a model name or text.
func (an *analyzer) modelMismatch(code string, sess AnalystSession) string {
	an.deleteSession(sess, "stocks: analysis session with the wrong model not deleted; the sweep retries", "code", code)
	an.svc.log.Warn("stocks: analysis aborted: model_mismatch", "code", code)
	return "model_error"
}

// openFailed maps a failed Open. A session deleted unused (tools present) is forgotten; one Open could not
// delete stays recorded. When the job stopped while the create was in flight, Hermes may hold a hidden
// session whose id never reached us: that is logged, with the id when known.
func (an *analyzer) openFailed(code, created string, err error) string {
	switch {
	case created != "" && errors.Is(err, hermes.ErrToolsPresent) && !errors.Is(err, hermes.ErrSessionKept):
		an.forgetSession(created)
	case created != "" && errors.Is(err, hermes.ErrSessionKept):
		an.svc.log.Warn("stocks: analysis session with tools not deleted; the sweep retries", "code", code, "session", created)
	case created == "" && errors.Is(err, hermes.ErrBadAnswer):
		an.svc.log.Warn("stocks: Hermes gave an unusable answer to the session create; a hidden session may be left", "code", code)
	case errors.Is(err, context.Canceled):
		an.svc.log.Warn("stocks: analysis stopped while its Hermes session was being created; a hidden session may be left",
			"code", code, "session", cmp.Or(created, "unknown"))
	}
	return hermesErrorCode(err)
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
	if len(rejects) == 0 {
		v, flags = capNewsOnly(v, flags)
	}
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
		Trigger: cmp.Or(in.Trigger, TriggerManual),
		Verdict: v.Verdict, Confidence: v.Confidence, EntryLow: v.EntryLow, EntryHigh: v.EntryHigh, Target: v.Target, Stop: v.Stop,
		Summary: v.Summary, Reasons: v.Reasons, Flags: flags, Repaired: repaired, Price: in.Ind.Price, ARA: in.Ind.ARA, ARB: in.Ind.ARB,
		Indicators: in.indicatorIDs(), Levels: in.levelIDs(), News: news}
}

// hermesErrorCode maps an analysis failure to the job's error code (protocol §20).
func hermesErrorCode(err error) string {
	var te *hermes.TurnError
	var re *hermes.RPCError
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
	case errors.As(err, &re), errors.Is(err, hermes.ErrBadAnswer):
		return "hermes_refused"
	default:
		return "hermes_lost" // socket, dial or ticket failure
	}
}

// kept is every kept result (a copy, code order).
func (an *analyzer) kept() []AnalysisResult {
	an.mu.Lock()
	defer an.mu.Unlock()
	out := make([]AnalysisResult, 0, len(an.st.Analyses))
	for _, c := range sortedKeys(an.st.Analyses) {
		out = append(out, an.st.Analyses[c])
	}
	return out
}

// keptOne is code's kept result.
func (an *analyzer) keptOne(code string) (AnalysisResult, bool) {
	an.mu.Lock()
	defer an.mu.Unlock()
	r, ok := an.st.Analyses[code]
	return r, ok
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

func (an *analyzer) finish(job *analysisJob, errCode string) {
	an.mu.Lock()
	defer an.mu.Unlock()
	an.running--
	job.FinishedAt = an.svc.now()
	job.State, job.Error = jobState(errCode), errCode
}

// recordSession records a session for the sweep (P7: failed sessions stay 7 days for debugging).
func (an *analyzer) recordSession(id string) {
	an.mu.Lock()
	defer an.mu.Unlock()
	an.st.Failed = append(an.st.Failed, failedSession{SessionID: id, FailedAt: *rfc(an.svc.now())})
	if n := len(an.st.Failed); n > maxFailedSessions {
		an.st.Failed = slices.Clone(an.st.Failed[n-maxFailedSessions:])
	}
	an.saveLocked()
}

// forgetSession drops the record of a session that is gone from Hermes.
func (an *analyzer) forgetSession(id string) {
	an.mu.Lock()
	defer an.mu.Unlock()
	an.st.Failed = slices.DeleteFunc(an.st.Failed, func(f failedSession) bool { return f.SessionID == id })
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

// sweep deletes the records older than 7 days. A refused delete is skipped and retried the next day;
// when Hermes cannot be reached at all the rest wait too. A record whose failed_at does not parse is
// kept (and logged) rather than swept, so a damaged file never deletes a session early.
func (an *analyzer) sweep() {
	an.mu.Lock()
	cutoff := an.svc.now().Add(-failedSessionTTL)
	var due []string
	unreadable := 0
	for _, f := range an.st.Failed {
		t, err := time.Parse(time.RFC3339, f.FailedAt)
		switch {
		case err != nil:
			unreadable++
		case t.Before(cutoff):
			due = append(due, f.SessionID)
		}
	}
	an.mu.Unlock()
	if unreadable > 0 {
		an.svc.log.Warn("stocks: analysis session records with an unreadable failed_at are kept, not swept", "file", analysesFile, "count", unreadable)
	}
	gone := map[string]bool{}
	left, lastErr := 0, error(nil)
	for i, id := range due {
		ctx, cancel := context.WithTimeout(an.ctx, sweepDeleteTimeout)
		err := an.analyst.DeleteSession(ctx, id)
		timedOut := ctx.Err() != nil && an.ctx.Err() == nil // this delete ran out of time; the job is not stopping
		cancel()
		var re *hermes.RPCError
		switch {
		case err == nil || errors.Is(err, hermes.ErrSessionNotFound):
			gone[id] = true
			continue
		case errors.As(err, &re), timedOut:
			left++ // Hermes refused this one or was slow on it: the others may still go
			lastErr = err
			continue
		}
		left += len(due) - i // unreachable or stopping: every later delete would fail the same way
		lastErr = err
		break
	}
	if left > 0 {
		an.svc.log.Warn("stocks: old analysis sessions not deleted; retried tomorrow", "count", left, "err", hermesErrorCode(lastErr))
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
