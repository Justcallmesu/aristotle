package stocks

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"net/http"
	"regexp"
	"slices"
	"sync"
	"time"

	"github.com/justcallmesu/aristotle/server/internal/statefile"
)

// The stocks scheduler (spec Q20/Q31, TSC0 design §2, protocol §20.2). One loop compares the WALL clock to
// each job's due time every 30 s: Go timers stop while a laptop is suspended, so a timer set for 16:20 would
// fire late by the length of the sleep; a short tick that re-reads the clock notices the jump within 30 s.
// Jobs are registered by the features that own them (bars.daily here; scan, brief, alerts later).
//
// Two kinds of job:
//   - "at" jobs run once per (trading) day at HH:MM WIB. A slot that was not run on time (PC off or asleep)
//     is caught up per Q31: by default it runs late while it is still before 16:00 of its day, else it is
//     recorded as missed; a job may opt to always catch up (bars.daily: the store must not stay behind).
//     Only the latest slot counts: three missed days give one catch-up run, not three.
//   - interval jobs run every N minutes inside windows of a trading day. A slot runs only within 2 minutes
//     of its time: after a sleep the job resumes at the next slot, nothing bursts.
//
// A job never overlaps itself. Settings (on/off, time, interval) and the last outcome are kept in
// stocks-jobs.json; the date of the last at-slot run or missed is kept too, so a restart never repeats it.

const (
	jobsFile         = "stocks-jobs.json"
	jobsVersion      = 1
	schedTick        = 30 * time.Second
	onTimeGrace      = 2 * time.Minute
	catchUpLookback  = 15 // days searched back for the latest at-slot (longest IDX closure is ~9 days)
	jobStopWait      = 5 * time.Second
	minJobAt         = 5 * 60  // 05:00, minutes after midnight
	maxJobAt         = 23 * 60 // 23:00
	minJobEveryMin   = 5
	maxJobEveryMin   = 30
	lateCutoffHour   = 16 // Q31: a missed slot still runs before the market close of its day
	maxJobErrorBytes = 80
)

// Job settings a PUT may change (JobSpec.Editable).
const (
	EditOn       = "on"
	EditAt       = "at"
	EditEveryMin = "every_min"
)

// Job outcomes (last_status).
const (
	JobStatusOK      = "ok"
	JobStatusPartial = "partial" // ran, but part of the work failed (last_error says what)
	JobStatusFailed  = "failed"
	JobStatusMissed  = "missed" // the slot passed while the PC was off or asleep and was not caught up
)

var (
	errUnknownJob = &apiError{status: http.StatusNotFound, code: "unknown_job"}
	hhmmRe        = regexp.MustCompile(`^([01][0-9]|2[0-3]):[0-5][0-9]$`)
)

// CatchUp is what an at-job does with a slot it did not run on time.
type CatchUp int

const (
	// CatchUpBeforeClose (Q31, the default): run late until 16:00 WIB of the slot's day, else missed.
	CatchUpBeforeClose CatchUp = iota
	// CatchUpAlways: run late whenever the server notices (bars.daily).
	CatchUpAlways
)

// Window is one active span of an interval job, [Start, End).
type Window struct{ Start, End time.Time }

// JobRun tells a job which slot it runs for.
type JobRun struct {
	Due  time.Time // the slot time
	Late bool      // a catch-up: more than 2 minutes after the slot
}

// JobFunc is a job body. It returns nil (ok), a *JobError (partial or failed with a code) or any error
// (failed; mapped to a code by jobErrorCode).
type JobFunc func(ctx context.Context, run JobRun) error

// JobError is a job outcome with a short code for last_error. Partial means the job did part of its work.
type JobError struct {
	Code    string
	Partial bool
}

func (e *JobError) Error() string { return e.Code }

// JobSpec registers a job. Exactly one of At ("HH:MM") and EveryMin is set; EveryMin needs Windows.
type JobSpec struct {
	ID          string
	At          string
	EveryMin    int
	Windows     func(day time.Time) []Window // interval jobs: the day's windows (day is 00:00 WIB)
	TradingDays bool                         // run only on IDX trading days
	CatchUp     CatchUp                      // at-jobs only
	DefaultOff  bool
	Editable    []string // subset of EditOn, EditAt, EditEveryMin
	Run         JobFunc
}

// jobRecord is one job in stocks-jobs.json. Settings are stored only once the user set them, so a later
// default change reaches everyone who never edited the job.
type jobRecord struct {
	On         *bool  `json:"on,omitempty"`
	At         string `json:"at,omitempty"`
	EveryMin   int    `json:"every_min,omitempty"`
	Handled    string `json:"handled,omitempty"` // at-jobs: WIB date of the last slot run or missed
	LastDue    string `json:"last_due,omitempty"`
	LastRun    string `json:"last_run,omitempty"`
	LastStatus string `json:"last_status,omitempty"`
	LastError  string `json:"last_error,omitempty"`
	Late       bool   `json:"late,omitempty"`
}

type jobsState struct {
	Version int                  `json:"version"`
	Jobs    map[string]jobRecord `json:"jobs"`
}

type job struct {
	spec     JobSpec
	rec      jobRecord
	on       bool
	at       clock // at-jobs
	every    int   // interval jobs, minutes
	running  bool
	lastSlot time.Time // interval jobs: the last slot run or skipped (memory only)
}

func (j *job) interval() bool { return j.spec.EveryMin > 0 }

// scheduler owns the jobs and the loop.
type scheduler struct {
	dir   string
	now   func() time.Time
	after func(time.Duration) <-chan time.Time
	log   logger

	ctx    context.Context
	cancel context.CancelFunc
	wg     sync.WaitGroup

	mu      sync.Mutex
	order   []string
	jobs    map[string]*job
	others  map[string]jobRecord // records of jobs this build does not register (kept on save)
	persist bool
	started bool
	closing bool
}

// logger is the slice of *slog.Logger the scheduler uses.
type logger interface {
	Info(msg string, args ...any)
	Warn(msg string, args ...any)
	Error(msg string, args ...any)
}

func newScheduler(dir string, now func() time.Time, after func(time.Duration) <-chan time.Time, log logger) *scheduler {
	ctx, cancel := context.WithCancel(context.Background())
	sc := &scheduler{dir: dir, now: now, after: after, log: log, ctx: ctx, cancel: cancel,
		jobs: map[string]*job{}, others: map[string]jobRecord{}, persist: true}
	if sc.after == nil {
		sc.after = time.After
	}
	st, err := loadJobs(dir)
	if err != nil {
		sc.persist = false
		log.Error("stocks: jobs file unusable; jobs run with their defaults and settings cannot be saved until it is fixed or removed",
			"file", jobsFile, "err", err)
	}
	for id, r := range st.Jobs {
		sc.others[id] = r // moved to its job by register
	}
	return sc
}

func loadJobs(dir string) (jobsState, error) {
	empty := jobsState{Version: jobsVersion, Jobs: map[string]jobRecord{}}
	data, err := statefile.Read(dir, jobsFile)
	if errors.Is(err, fs.ErrNotExist) {
		return empty, nil
	}
	if err != nil {
		return empty, err
	}
	var st jobsState
	dec := json.NewDecoder(bytes.NewReader(data))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&st); err != nil {
		return empty, fmt.Errorf("%s: %w", jobsFile, err)
	}
	if st.Version != jobsVersion {
		return empty, fmt.Errorf("%s: version %d, want %d", jobsFile, st.Version, jobsVersion)
	}
	if st.Jobs == nil {
		st.Jobs = map[string]jobRecord{}
	}
	return st, nil
}

// saveLocked writes stocks-jobs.json; the caller holds mu.
func (sc *scheduler) saveLocked() error {
	if !sc.persist {
		return errState
	}
	st := jobsState{Version: jobsVersion, Jobs: map[string]jobRecord{}}
	for id, r := range sc.others {
		st.Jobs[id] = r
	}
	for id, j := range sc.jobs {
		st.Jobs[id] = j.rec
	}
	data, err := json.MarshalIndent(st, "", "  ")
	if err == nil {
		err = statefile.WriteAtomic(sc.dir, jobsFile, append(data, '\n'))
	}
	if err != nil {
		sc.log.Error("stocks: saving jobs failed", "err", err)
		return errState
	}
	return nil
}

// save is saveLocked for the loop: a jobs file that cannot be written is already logged.
func (sc *scheduler) saveQuiet() { _ = sc.saveLocked() }

// register adds a job; a bad spec is a programming error and panics. Stored settings that are out of bounds
// are logged and replaced by the defaults.
func (sc *scheduler) register(spec JobSpec) {
	if spec.ID == "" || spec.Run == nil || (spec.At == "") == (spec.EveryMin <= 0) || (spec.EveryMin > 0 && spec.Windows == nil) {
		panic("stocks: bad job spec " + spec.ID)
	}
	j := &job{spec: spec, on: !spec.DefaultOff, every: spec.EveryMin}
	if spec.At != "" {
		c, ok := parseHHMM(spec.At)
		if !ok {
			panic("stocks: bad job time " + spec.At)
		}
		j.at = c
	}
	sc.mu.Lock()
	defer sc.mu.Unlock()
	if _, dup := sc.jobs[spec.ID]; dup {
		panic("stocks: job registered twice: " + spec.ID)
	}
	if r, ok := sc.others[spec.ID]; ok {
		delete(sc.others, spec.ID)
		j.rec = r
		if r.On != nil {
			j.on = *r.On
		}
		if r.At != "" {
			if c, ok := parseHHMM(r.At); ok && !j.interval() && atInBounds(c) {
				j.at = c
			} else {
				sc.log.Warn("stocks: stored job time ignored", "job", spec.ID, "at", r.At)
				j.rec.At = ""
			}
		}
		if r.EveryMin != 0 {
			if j.interval() && r.EveryMin >= minJobEveryMin && r.EveryMin <= maxJobEveryMin {
				j.every = r.EveryMin
			} else {
				sc.log.Warn("stocks: stored job interval ignored", "job", spec.ID, "every_min", r.EveryMin)
				j.rec.EveryMin = 0
			}
		}
	}
	sc.jobs[spec.ID] = j
	sc.order = append(sc.order, spec.ID)
}

// start runs the loop: a tick at once (the boot catch-up), then every 30 s. Called once.
func (sc *scheduler) start() {
	sc.mu.Lock()
	defer sc.mu.Unlock()
	if sc.started || sc.closing {
		return
	}
	sc.started = true
	sc.wg.Go(func() {
		for {
			sc.tick(sc.now())
			select {
			case <-sc.after(schedTick):
			case <-sc.ctx.Done():
				return
			}
		}
	})
}

// close stops the loop and cancels running jobs, waiting for them (bounded).
func (sc *scheduler) close() {
	sc.mu.Lock()
	sc.closing = true
	sc.mu.Unlock()
	sc.cancel()
	done := make(chan struct{})
	go func() { sc.wg.Wait(); close(done) }()
	select {
	case <-done:
	case <-time.After(jobStopWait):
		sc.log.Warn("stocks: scheduled jobs did not stop in time")
	}
}

// wait blocks until no job runs (tests).
func (sc *scheduler) wait() { sc.wg.Wait() }

// tick starts every job that is due at now and records missed slots.
func (sc *scheduler) tick(now time.Time) {
	now = now.In(WIB)
	sc.mu.Lock()
	defer sc.mu.Unlock()
	if sc.closing {
		return
	}
	dirty := false
	for _, id := range sc.order {
		j := sc.jobs[id]
		if j.running {
			continue
		}
		if j.interval() {
			slot, ok := j.intervalSlot(now)
			if !ok || !slot.After(j.lastSlot) {
				continue
			}
			j.lastSlot = slot
			if j.on && now.Sub(slot) <= onTimeGrace {
				sc.launchLocked(j, slot, now, false)
				dirty = true
			}
			continue
		}
		slot, ok := j.latestAtSlot(now)
		if !ok || slot.Format(time.DateOnly) <= j.rec.Handled {
			continue
		}
		dirty = true
		j.rec.Handled = slot.Format(time.DateOnly)
		if !j.on {
			continue // a slot passed while off is never caught up when the job is turned on again
		}
		late := now.Sub(slot) > onTimeGrace
		if late && j.spec.CatchUp == CatchUpBeforeClose && !now.Before(at(slot, clock{lateCutoffHour, 0})) {
			j.rec.LastDue, j.rec.LastStatus, j.rec.LastError, j.rec.Late = *rfc(slot), JobStatusMissed, "", false
			sc.log.Warn("stocks: scheduled job missed (the PC was off or asleep)", "job", id, "due", *rfc(slot))
			continue
		}
		sc.launchLocked(j, slot, now, late)
	}
	if dirty {
		sc.saveQuiet()
	}
}

// launchLocked starts j for slot; the caller holds mu and saves.
func (sc *scheduler) launchLocked(j *job, slot, now time.Time, late bool) {
	j.running = true
	j.rec.LastDue, j.rec.LastRun, j.rec.Late = *rfc(slot), *rfc(now), late
	run := JobRun{Due: slot, Late: late}
	sc.wg.Go(func() {
		began := time.Now()
		err := sc.safeRun(j, run)
		status, code := jobOutcome(err)
		sc.log.Info("stocks: scheduled job finished", "job", j.spec.ID, "late", late, "status", status, "error", code,
			"duration_ms", time.Since(began).Milliseconds())
		if code == "internal_error" {
			sc.log.Error("stocks: scheduled job failed", "job", j.spec.ID, "err", err)
		}
		sc.mu.Lock()
		defer sc.mu.Unlock()
		j.running = false
		j.rec.LastStatus, j.rec.LastError = status, code
		sc.saveQuiet()
	})
}

// safeRun runs the body; a panic fails the run instead of the server.
func (sc *scheduler) safeRun(j *job, run JobRun) (err error) {
	defer func() {
		if r := recover(); r != nil {
			err = fmt.Errorf("panic: %v", r)
		}
	}()
	return j.spec.Run(sc.ctx, run)
}

// jobOutcome maps a body's result to last_status and last_error.
func jobOutcome(err error) (status, code string) {
	var je *JobError
	var be *BlockedError
	var ae *apiError
	switch {
	case err == nil:
		return JobStatusOK, ""
	case errors.As(err, &je):
		code = je.Code
		if len(code) > maxJobErrorBytes {
			code = code[:maxJobErrorBytes]
		}
		if je.Partial {
			return JobStatusPartial, code
		}
		return JobStatusFailed, code
	case errors.As(err, &be):
		return JobStatusFailed, "upstream_blocked"
	case errors.Is(err, context.Canceled):
		return JobStatusFailed, "interrupted"
	case errors.As(err, &ae):
		return JobStatusFailed, ae.code
	}
	return JobStatusFailed, "internal_error"
}

// latestAtSlot is the latest slot at or before now (on trading days when the job asks for them).
func (j *job) latestAtSlot(now time.Time) (time.Time, bool) {
	day := at(now, clock{0, 0})
	for i := range catchUpLookback {
		d := day.AddDate(0, 0, -i)
		if j.spec.TradingDays && !IsTradingDay(d) {
			continue
		}
		if s := at(d, j.at); !s.After(now) {
			return s, true
		}
	}
	return time.Time{}, false
}

// intervalSlot is the slot now falls in (inside a window of today), if any.
func (j *job) intervalSlot(now time.Time) (time.Time, bool) {
	day := at(now, clock{0, 0})
	if j.spec.TradingDays && !IsTradingDay(day) {
		return time.Time{}, false
	}
	every := time.Duration(j.every) * time.Minute
	for _, w := range j.spec.Windows(day) {
		if now.Before(w.Start) || !now.Before(w.End) {
			continue
		}
		return w.Start.Add(now.Sub(w.Start) / every * every), true
	}
	return time.Time{}, false
}

// nextRun is the next slot after now, nil when the job is off.
func (j *job) nextRun(now time.Time) *time.Time {
	if !j.on {
		return nil
	}
	day := at(now, clock{0, 0})
	every := time.Duration(j.every) * time.Minute
	for i := range catchUpLookback + 1 {
		d := day.AddDate(0, 0, i)
		if j.spec.TradingDays && !IsTradingDay(d) {
			continue
		}
		if !j.interval() {
			if s := at(d, j.at); s.After(now) && d.Format(time.DateOnly) > j.rec.Handled {
				return &s
			}
			continue
		}
		for _, w := range j.spec.Windows(d) {
			s := w.Start
			if !now.Before(s) {
				k := (now.Sub(w.Start) + every) / every
				s = w.Start.Add(k * every)
			}
			if s.Before(w.End) {
				return &s
			}
		}
	}
	return nil
}

func parseHHMM(s string) (clock, bool) {
	if !hhmmRe.MatchString(s) {
		return clock{}, false
	}
	return clock{int(s[0]-'0')*10 + int(s[1]-'0'), int(s[3]-'0')*10 + int(s[4]-'0')}, true
}

func atInBounds(c clock) bool {
	m := c.h*60 + c.m
	return m >= minJobAt && m <= maxJobAt
}

func (c clock) String() string { return fmt.Sprintf("%02d:%02d", c.h, c.m) }

// --- HTTP ---

// JobView is one row of GET /v1/stocks/jobs.
type JobView struct {
	ID         string   `json:"id"`
	On         bool     `json:"on"`
	At         *string  `json:"at"`
	EveryMin   *int     `json:"every_min"`
	Running    bool     `json:"running"`
	LastDue    *string  `json:"last_due"`
	LastRun    *string  `json:"last_run"`
	LastStatus *string  `json:"last_status"`
	LastError  *string  `json:"last_error"`
	Late       bool     `json:"late"`
	NextRun    *string  `json:"next_run"`
	Editable   []string `json:"editable"`
}

type jobsBody struct {
	Jobs []JobView `json:"jobs"`
}

func nonEmpty(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}

// viewLocked renders j; the caller holds mu.
func (j *job) viewLocked(now time.Time) JobView {
	v := JobView{ID: j.spec.ID, On: j.on, Running: j.running, Late: j.rec.Late, Editable: slices.Clone(j.spec.Editable),
		LastDue: nonEmpty(j.rec.LastDue), LastRun: nonEmpty(j.rec.LastRun), LastStatus: nonEmpty(j.rec.LastStatus),
		LastError: nonEmpty(j.rec.LastError)}
	if v.Editable == nil {
		v.Editable = []string{}
	}
	if j.interval() {
		e := j.every
		v.EveryMin = &e
	} else {
		a := j.at.String()
		v.At = &a
	}
	if t := j.nextRun(now); t != nil {
		v.NextRun = rfc(*t)
	}
	return v
}

func (sc *scheduler) views() []JobView {
	now := sc.now().In(WIB)
	sc.mu.Lock()
	defer sc.mu.Unlock()
	out := []JobView{}
	for _, id := range sc.order {
		out = append(out, sc.jobs[id].viewLocked(now))
	}
	return out
}

// jobPatch is the body of PUT /v1/stocks/jobs/{id}.
type jobPatch struct {
	On       *bool   `json:"on"`
	At       *string `json:"at"`
	EveryMin *int    `json:"every_min"`
}

// update applies p to job id (fields per the job's Editable, bounds as in §20.2) and saves.
func (sc *scheduler) update(id string, p jobPatch) (JobView, error) {
	if p.On == nil && p.At == nil && p.EveryMin == nil {
		return JobView{}, errBadRequest
	}
	sc.mu.Lock()
	defer sc.mu.Unlock()
	j, ok := sc.jobs[id]
	if !ok {
		return JobView{}, errUnknownJob
	}
	editable := func(f string) bool { return slices.Contains(j.spec.Editable, f) }
	n := *j
	if p.On != nil {
		if !editable(EditOn) {
			return JobView{}, errBadRequest
		}
		on := *p.On
		n.on, n.rec.On = on, &on
	}
	if p.At != nil {
		c, ok := parseHHMM(*p.At)
		if !editable(EditAt) || j.interval() || !ok || !atInBounds(c) {
			return JobView{}, errBadRequest
		}
		n.at, n.rec.At = c, c.String()
	}
	if p.EveryMin != nil {
		e := *p.EveryMin
		if !editable(EditEveryMin) || !j.interval() || e < minJobEveryMin || e > maxJobEveryMin {
			return JobView{}, errBadRequest
		}
		n.every, n.rec.EveryMin = e, e
	}
	old := *j
	*j = n
	if err := sc.saveLocked(); err != nil {
		*j = old
		return JobView{}, err
	}
	return j.viewLocked(sc.now().In(WIB)), nil
}

func (s *Service) getJobs(*http.Request) (int, any, error) {
	return http.StatusOK, jobsBody{Jobs: s.sched.views()}, nil
}

func (s *Service) putJob(r *http.Request) (int, any, error) {
	var p jobPatch
	if err := decodeBody(r, &p); err != nil {
		return 0, nil, err
	}
	v, err := s.sched.update(r.PathValue("id"), p)
	if err != nil {
		return 0, nil, err
	}
	return http.StatusOK, v, nil
}
