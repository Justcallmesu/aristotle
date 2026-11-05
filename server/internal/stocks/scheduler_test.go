package stocks

import (
	"bytes"
	"context"
	"errors"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

// wibs parses "2006-01-02 15:04:05" in WIB.
func wibs(s string) time.Time {
	t, err := time.ParseInLocation("2006-01-02 15:04:05", s, WIB)
	if err != nil {
		panic(err)
	}
	return t
}

// runLog records the runs of a test job.
type runLog struct {
	mu   sync.Mutex
	runs []JobRun
	err  error
}

func (r *runLog) fn(_ context.Context, run JobRun) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.runs = append(r.runs, run)
	return r.err
}

func (r *runLog) take() []JobRun {
	r.mu.Lock()
	defer r.mu.Unlock()
	out := r.runs
	r.runs = nil
	return out
}

type schedHarness struct {
	t    *testing.T
	dir  string
	logs *bytes.Buffer
	sc   *scheduler
}

func newSchedHarness(t *testing.T, dir string) *schedHarness {
	t.Helper()
	h := &schedHarness{t: t, dir: dir, logs: &bytes.Buffer{}}
	h.sc = newScheduler(dir, time.Now, nil, slog.New(slog.NewTextHandler(h.logs, nil)))
	t.Cleanup(h.sc.close)
	return h
}

// tickAt ticks at now and waits for the jobs it started.
func (h *schedHarness) tickAt(now string) {
	h.sc.tick(wibs(now))
	h.sc.wait()
}

func (h *schedHarness) view(id string, now string) JobView {
	h.t.Helper()
	h.sc.mu.Lock()
	defer h.sc.mu.Unlock()
	return h.sc.jobs[id].viewLocked(wibs(now))
}

func expectRuns(t *testing.T, r *runLog, want ...string) {
	t.Helper()
	got := r.take()
	var g []string
	for _, x := range got {
		s := x.Due.In(WIB).Format("2006-01-02 15:04")
		if x.Late {
			s += " late"
		}
		g = append(g, s)
	}
	if strings.Join(g, ",") != strings.Join(want, ",") {
		t.Fatalf("runs = %v, want %v", g, want)
	}
}

func atJob(id, at string, c CatchUp, r *runLog) JobSpec {
	return JobSpec{ID: id, At: at, TradingDays: true, CatchUp: c, Editable: []string{EditOn, EditAt}, Run: r.fn}
}

func TestSchedulerAtJobOnTimeOncePerTradingDay(t *testing.T) {
	h := newSchedHarness(t, t.TempDir())
	r := &runLog{}
	h.sc.register(atJob("bars", "16:20", CatchUpAlways, r))
	h.tickAt("2026-11-05 16:19:50") // Thu: yesterday's slot is the latest; it was never run (first boot) -> catch-up
	expectRuns(t, r, "2026-11-04 16:20 late")
	h.tickAt("2026-11-05 16:20:10")
	expectRuns(t, r, "2026-11-05 16:20")
	h.tickAt("2026-11-05 16:20:40")
	h.tickAt("2026-11-05 23:59:59")
	expectRuns(t, r)
	if v := h.view("bars", "2026-11-05 17:00:00"); *v.NextRun != "2026-11-06T16:20:00+07:00" || *v.LastStatus != JobStatusOK || v.Late {
		t.Fatalf("view = %+v", v)
	}
	// Friday runs, the weekend does not, Monday does.
	h.tickAt("2026-11-06 16:20:30")
	h.tickAt("2026-11-07 16:20:30")
	h.tickAt("2026-11-08 16:20:30")
	expectRuns(t, r, "2026-11-06 16:20")
	if v := h.view("bars", "2026-11-06 17:00:00"); *v.NextRun != "2026-11-09T16:20:00+07:00" {
		t.Fatalf("next after Friday = %v", *v.NextRun)
	}
	h.tickAt("2026-11-09 16:21:00")
	expectRuns(t, r, "2026-11-09 16:20")
}

func TestSchedulerHolidaysSkipped(t *testing.T) {
	h := newSchedHarness(t, t.TempDir())
	r := &runLog{}
	h.sc.register(atJob("bars", "16:20", CatchUpAlways, r))
	h.tickAt("2026-12-23 16:20:05") // Wed
	expectRuns(t, r, "2026-12-23 16:20")
	if v := h.view("bars", "2026-12-23 17:00:00"); *v.NextRun != "2026-12-28T16:20:00+07:00" {
		t.Fatalf("next over Christmas = %v", *v.NextRun) // 24th and 25th are IDX holidays, then the weekend
	}
	h.tickAt("2026-12-24 16:20:05")
	h.tickAt("2026-12-25 16:20:05")
	h.tickAt("2026-12-27 16:20:05")
	expectRuns(t, r)
	h.tickAt("2026-12-28 16:20:05")
	expectRuns(t, r, "2026-12-28 16:20")
}

// A laptop asleep over the slot: the wall clock jumps; the next tick catches up the latest slot only.
func TestSchedulerSuspendJumpCatchesUpLatestSlotOnce(t *testing.T) {
	h := newSchedHarness(t, t.TempDir())
	bars, brief := &runLog{}, &runLog{}
	h.sc.register(atJob("bars", "16:20", CatchUpAlways, bars))
	h.sc.register(atJob("brief", "08:30", CatchUpBeforeClose, brief))
	h.tickAt("2026-11-05 08:30:05")
	h.tickAt("2026-11-05 16:20:05")
	expectRuns(t, bars, "2026-11-04 16:20 late", "2026-11-05 16:20")
	expectRuns(t, brief, "2026-11-05 08:30")
	// Asleep from Thu 16:25 to Mon 10:00: Friday's bars run once, late; Monday's brief runs late (before 16:00).
	h.tickAt("2026-11-09 10:00:00")
	expectRuns(t, bars, "2026-11-06 16:20 late")
	expectRuns(t, brief, "2026-11-09 08:30 late")
	if v := h.view("brief", "2026-11-09 10:00:01"); !v.Late || *v.LastRun != "2026-11-09T10:00:00+07:00" || *v.LastDue != "2026-11-09T08:30:00+07:00" {
		t.Fatalf("brief view = %+v", v)
	}
	// Asleep from Mon 10:00 to Tue 16:30: the brief's slot passed its day's close -> missed, not run.
	h.tickAt("2026-11-10 16:30:00")
	expectRuns(t, brief)
	expectRuns(t, bars, "2026-11-10 16:20 late")
	if v := h.view("brief", "2026-11-10 16:30:01"); *v.LastStatus != JobStatusMissed || v.LastError != nil ||
		*v.LastDue != "2026-11-10T08:30:00+07:00" || *v.NextRun != "2026-11-11T08:30:00+07:00" {
		t.Fatalf("missed view = %+v", v)
	}
	if !strings.Contains(h.logs.String(), "scheduled job missed") {
		t.Fatalf("logs: %s", h.logs)
	}
	h.tickAt("2026-11-10 16:30:30") // a missed slot is not retried
	expectRuns(t, brief)
	h.tickAt("2026-11-11 08:31:00")
	expectRuns(t, brief, "2026-11-11 08:30")
}

// Catch-up at boot (Q31) survives restarts: the handled date is in stocks-jobs.json.
func TestSchedulerBootCatchUpAcrossRestarts(t *testing.T) {
	dir := t.TempDir()
	h := newSchedHarness(t, dir)
	r := &runLog{}
	h.sc.register(atJob("bars", "16:20", CatchUpAlways, r))
	h.tickAt("2026-11-05 16:20:05")
	expectRuns(t, r, "2026-11-05 16:20")
	h.sc.close()

	h2 := newSchedHarness(t, dir) // restart the same evening: nothing to do
	h2.sc.register(atJob("bars", "16:20", CatchUpAlways, r))
	h2.tickAt("2026-11-05 21:00:00")
	h2.tickAt("2026-11-06 09:00:00") // Friday morning: Friday's slot has not come
	expectRuns(t, r)
	h2.sc.close()

	h3 := newSchedHarness(t, dir) // PC off from Friday morning to Monday 07:00
	h3.sc.register(atJob("bars", "16:20", CatchUpAlways, r))
	h3.tickAt("2026-11-09 07:00:00")
	expectRuns(t, r, "2026-11-06 16:20 late")
	b, err := os.ReadFile(filepath.Join(dir, jobsFile))
	if err != nil || !strings.Contains(string(b), `"handled": "2026-11-06"`) || !strings.Contains(string(b), `"late": true`) {
		t.Fatalf("jobs file = %s, %v", b, err)
	}
	if fi, _ := os.Stat(filepath.Join(dir, jobsFile)); fi.Mode().Perm() != 0o600 {
		t.Fatalf("mode = %v", fi.Mode())
	}
}

func TestSchedulerOffJobNeverCatchesUp(t *testing.T) {
	h := newSchedHarness(t, t.TempDir())
	r := &runLog{}
	h.sc.register(atJob("bars", "16:20", CatchUpAlways, r))
	h.tickAt("2026-11-04 16:20:05")
	expectRuns(t, r, "2026-11-04 16:20")
	off, on := false, true
	if _, err := h.sc.update("bars", jobPatch{On: &off}); err != nil {
		t.Fatal(err)
	}
	if v := h.view("bars", "2026-11-05 10:00:00"); v.On || v.NextRun != nil {
		t.Fatalf("off view = %+v", v)
	}
	h.tickAt("2026-11-05 16:20:05")
	expectRuns(t, r)
	if _, err := h.sc.update("bars", jobPatch{On: &on}); err != nil {
		t.Fatal(err)
	}
	h.tickAt("2026-11-05 18:00:00") // turned on again after the slot: today's slot passed while off
	expectRuns(t, r)
	h.tickAt("2026-11-06 16:20:05")
	expectRuns(t, r, "2026-11-06 16:20")
}

func TestSchedulerTimeEditRunsOncePerDay(t *testing.T) {
	h := newSchedHarness(t, t.TempDir())
	r := &runLog{}
	h.sc.register(atJob("bars", "16:20", CatchUpAlways, r))
	h.tickAt("2026-11-05 16:20:05")
	expectRuns(t, r, "2026-11-05 16:20")
	at := "18:00"
	v, err := h.sc.update("bars", jobPatch{At: &at})
	if err != nil || *v.At != "18:00" {
		t.Fatalf("update = %+v, %v", v, err)
	}
	h.tickAt("2026-11-05 18:00:05") // already ran today
	expectRuns(t, r)
	h.tickAt("2026-11-06 16:20:05")
	h.tickAt("2026-11-06 18:00:05")
	expectRuns(t, r, "2026-11-06 18:00")
}

// sessionWindows are a scan-like job's windows: the two sessions of the day (Friday's are shorter).
func sessionWindows(day time.Time) []Window {
	var out []Window
	for _, s := range daySessions(day.Weekday()) {
		out = append(out, Window{Start: at(day, s.start), End: at(day, s.end)})
	}
	return out
}

func TestSchedulerIntervalJob(t *testing.T) {
	h := newSchedHarness(t, t.TempDir())
	r := &runLog{}
	h.sc.register(JobSpec{ID: "scan", EveryMin: 10, Windows: sessionWindows, TradingDays: true,
		Editable: []string{EditOn, EditEveryMin}, Run: r.fn})
	h.tickAt("2026-11-05 08:59:50")
	h.tickAt("2026-11-05 09:00:10")
	h.tickAt("2026-11-05 09:00:40")
	h.tickAt("2026-11-05 09:05:00")
	h.tickAt("2026-11-05 09:10:20")
	expectRuns(t, r, "2026-11-05 09:00", "2026-11-05 09:10")
	// Asleep 09:10 -> 11:47: no burst, the 11:40 slot is too old; the job resumes at 11:50.
	h.tickAt("2026-11-05 11:47:00")
	expectRuns(t, r)
	if v := h.view("scan", "2026-11-05 11:47:00"); *v.NextRun != "2026-11-05T11:50:00+07:00" || *v.EveryMin != 10 || v.At != nil {
		t.Fatalf("view = %+v", v)
	}
	h.tickAt("2026-11-05 11:50:05")
	h.tickAt("2026-11-05 12:00:05") // the break
	h.tickAt("2026-11-05 13:30:05")
	expectRuns(t, r, "2026-11-05 11:50", "2026-11-05 13:30")
	if v := h.view("scan", "2026-11-05 15:55:00"); *v.NextRun != "2026-11-06T09:00:00+07:00" {
		t.Fatalf("next after the last slot = %v", *v.NextRun)
	}
	// Friday: session 1 ends 11:30, session 2 starts 14:00.
	h.tickAt("2026-11-06 11:30:05")
	h.tickAt("2026-11-06 13:30:05")
	h.tickAt("2026-11-06 14:00:05")
	expectRuns(t, r, "2026-11-06 14:00")
	// Weekends and holidays: nothing.
	h.tickAt("2026-11-07 10:00:05")
	h.tickAt("2026-12-24 10:00:05")
	expectRuns(t, r)
	every := 5
	if v, err := h.sc.update("scan", jobPatch{EveryMin: &every}); err != nil || *v.EveryMin != 5 {
		t.Fatalf("update = %+v, %v", v, err)
	}
	h.tickAt("2026-11-09 09:05:05")
	expectRuns(t, r, "2026-11-09 09:05")
}

func TestSchedulerNoOverlapAndOutcomes(t *testing.T) {
	h := newSchedHarness(t, t.TempDir())
	release := make(chan struct{})
	started := make(chan struct{}, 4)
	h.sc.register(JobSpec{ID: "slow", EveryMin: 5, Windows: sessionWindows, TradingDays: true, Run: func(ctx context.Context, _ JobRun) error {
		started <- struct{}{}
		<-release
		return &JobError{Code: "codes_failed", Partial: true}
	}})
	h.sc.register(JobSpec{ID: "boom", At: "09:00", TradingDays: true, Run: func(context.Context, JobRun) error { panic("x") }})
	h.sc.tick(wibs("2026-11-05 09:00:05"))
	<-started
	h.sc.tick(wibs("2026-11-05 09:05:05")) // still running: not started twice
	if v := h.view("slow", "2026-11-05 09:05:06"); !v.Running {
		t.Fatalf("view = %+v", v)
	}
	close(release)
	h.sc.wait()
	if len(started) != 0 {
		t.Fatal("overlapping run")
	}
	if v := h.view("slow", "2026-11-05 09:06:00"); v.Running || *v.LastStatus != JobStatusPartial || *v.LastError != "codes_failed" {
		t.Fatalf("slow view = %+v", v)
	}
	if v := h.view("boom", "2026-11-05 09:06:00"); *v.LastStatus != JobStatusFailed || *v.LastError != "internal_error" {
		t.Fatalf("boom view = %+v", v)
	}
	for _, tt := range []struct {
		err          error
		status, code string
	}{
		{nil, JobStatusOK, ""},
		{&BlockedError{RetryAfter: time.Minute}, JobStatusFailed, "upstream_blocked"},
		{context.Canceled, JobStatusFailed, "interrupted"},
		{errState, JobStatusFailed, "state_error"},
		{&JobError{Code: "upstream_blocked", Partial: true}, JobStatusPartial, "upstream_blocked"},
		{errors.New("secret detail"), JobStatusFailed, "internal_error"},
	} {
		if s, c := jobOutcome(tt.err); s != tt.status || c != tt.code {
			t.Errorf("jobOutcome(%v) = %s %s", tt.err, s, c)
		}
	}
}

func TestSchedulerStoredSettings(t *testing.T) {
	dir := t.TempDir()
	write := func(s string) {
		if err := os.WriteFile(filepath.Join(dir, jobsFile), []byte(s), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	write(`{"version":1,"jobs":{"bars":{"on":false,"at":"17:05"},"scan":{"every_min":99},"later":{"on":true}}}`)
	h := newSchedHarness(t, dir)
	r := &runLog{}
	h.sc.register(atJob("bars", "16:20", CatchUpAlways, r))
	h.sc.register(JobSpec{ID: "scan", EveryMin: 10, Windows: sessionWindows, Editable: []string{EditEveryMin}, Run: r.fn})
	if v := h.view("bars", "2026-11-05 10:00:00"); v.On || *v.At != "17:05" {
		t.Fatalf("bars = %+v", v)
	}
	if v := h.view("scan", "2026-11-05 10:00:00"); *v.EveryMin != 10 {
		t.Fatalf("scan = %+v (stored 99 is out of bounds)", v)
	}
	on := true
	if _, err := h.sc.update("bars", jobPatch{On: &on}); err != nil {
		t.Fatal(err)
	}
	b, _ := os.ReadFile(filepath.Join(dir, jobsFile))
	if !strings.Contains(string(b), `"later"`) { // a job this build does not know is kept
		t.Fatalf("file = %s", b)
	}

	// An unusable file is left alone: jobs run on defaults and edits answer state_error.
	write(`{"version":7}`)
	h2 := newSchedHarness(t, dir)
	h2.sc.register(atJob("bars", "16:20", CatchUpAlways, r))
	if _, err := h2.sc.update("bars", jobPatch{On: &on}); err != errState {
		t.Fatalf("update on a bad file = %v", err)
	}
	h2.tickAt("2026-11-05 16:20:05")
	expectRuns(t, r, "2026-11-05 16:20")
	if b, _ := os.ReadFile(filepath.Join(dir, jobsFile)); string(b) != `{"version":7}` {
		t.Fatalf("bad file rewritten: %s", b)
	}
	if !strings.Contains(h2.logs.String(), "jobs file unusable") {
		t.Fatalf("logs: %s", h2.logs)
	}
}

// The loop ticks at once (boot catch-up) and then on every timer, reading the wall clock each time.
func TestSchedulerLoopReadsWallClock(t *testing.T) {
	clock := newClock("2026-11-05T16:00:00+07:00")
	ticks := make(chan time.Time)
	r := &runLog{}
	sc := newScheduler(t.TempDir(), clock.Now, func(d time.Duration) <-chan time.Time {
		if d != schedTick {
			t.Errorf("tick = %v", d)
		}
		return ticks
	}, slog.New(slog.DiscardHandler))
	sc.register(atJob("bars", "16:20", CatchUpAlways, r))
	sc.start()
	defer sc.close()
	waitFor(t, func() bool { return len(r.take()) == 1 }) // yesterday's slot, caught up at boot
	waitFor(t, func() bool {                              // the boot run has finished: a tick while it runs would skip the slot
		sc.mu.Lock()
		defer sc.mu.Unlock()
		return !sc.jobs["bars"].running
	})
	clock.Add(20*time.Minute + 10*time.Second) // e.g. a resume from suspend
	ticks <- time.Now()
	waitFor(t, func() bool {
		sc.mu.Lock()
		defer sc.mu.Unlock()
		return sc.jobs["bars"].rec.LastDue == "2026-11-05T16:20:00+07:00" && !sc.jobs["bars"].running
	})
}

func TestJobsHTTP(t *testing.T) {
	h := newHarness(t)
	got := h.expect("GET", "/v1/stocks/jobs", "", 200, "")
	jobs := got["jobs"].([]any)
	if len(jobs) != 1 {
		t.Fatalf("jobs = %v", jobs)
	}
	j := jobs[0].(map[string]any)
	if j["id"] != BarsJobID || j["on"] != true || j["at"] != "16:20" || j["every_min"] != nil || j["last_run"] != nil ||
		j["next_run"] != "2026-11-05T16:20:00+07:00" || j["running"] != false || len(j["editable"].([]any)) != 2 {
		t.Fatalf("job = %v", j)
	}
	for _, body := range []string{`{}`, `{"at":"04:59"}`, `{"at":"23:01"}`, `{"at":"16:14"}`, `{"at":"7:00"}`, `{"every_min":10}`, `{"on":1}`, `{"x":1}`} {
		h.expect("PUT", "/v1/stocks/jobs/"+BarsJobID, body, 400, "bad_request")
	}
	h.expect("PUT", "/v1/stocks/jobs/nope", `{"on":true}`, 404, "unknown_job")
	got = h.expect("PUT", "/v1/stocks/jobs/"+BarsJobID, `{"on":false,"at":"23:00"}`, 200, "")
	if got["on"] != false || got["at"] != "23:00" || got["next_run"] != nil {
		t.Fatalf("put = %v", got)
	}
	got = h.expect("PUT", "/v1/stocks/jobs/"+BarsJobID, `{"on":true}`, 200, "")
	if got["next_run"] != "2026-11-05T23:00:00+07:00" {
		t.Fatalf("put = %v", got)
	}
	b, err := os.ReadFile(filepath.Join(h.dir, jobsFile))
	if err != nil || !strings.Contains(string(b), `"at": "23:00"`) {
		t.Fatalf("file = %s, %v", b, err)
	}
}

// PUT while the job runs must not race with the run goroutine (the update changes fields in place).
// PUTs race the run goroutines with no synchronisation between them: instant job bodies are launched by a tick
// loop while another goroutine PUTs in a loop. Any unlocked read of job state in the goroutine is reported by -race.
func TestSchedulerPutDuringRunIsRaceFree(t *testing.T) {
	h := newSchedHarness(t, t.TempDir())
	h.sc.register(JobSpec{ID: "scan", EveryMin: 1, Editable: []string{EditOn, EditEveryMin},
		Windows: func(day time.Time) []Window {
			d := time.Date(day.Year(), day.Month(), day.Day(), 0, 0, 0, 0, WIB)
			return []Window{{Start: d, End: d.Add(24 * time.Hour)}}
		},
		Run: func(context.Context, JobRun) error { return nil }})
	stop := make(chan struct{})
	done := make(chan struct{})
	go func() {
		defer close(done)
		for i := 0; ; i++ {
			select {
			case <-stop:
				return
			default:
			}
			on, every := i%2 == 0, 5+i%5
			if _, err := h.sc.update("scan", jobPatch{On: &on, EveryMin: &every}); err != nil {
				t.Errorf("update: %v", err)
				return
			}
			h.sc.views()
		}
	}()
	base := wibs("2026-11-05 10:00:30")
	deadline := time.Now().Add(200 * time.Millisecond)
	for i := 0; time.Now().Before(deadline); i++ {
		h.sc.tick(base.Add(time.Duration(i) * 10 * time.Minute))
	}
	close(stop)
	<-done
	h.sc.wait()
}

func TestSchedulerIntervalLastSlotPersists(t *testing.T) {
	dir := t.TempDir()
	reg := func(h *schedHarness, r *runLog) {
		h.sc.register(JobSpec{ID: "scan", EveryMin: 10, TradingDays: true, Editable: []string{EditOn, EditEveryMin}, Windows: sessionWindows, Run: r.fn})
	}
	h := newSchedHarness(t, dir)
	r := &runLog{}
	reg(h, r)
	h.tickAt("2026-11-05 10:00:30")
	expectRuns(t, r, "2026-11-05 10:00")
	h.sc.close()

	h2 := newSchedHarness(t, dir) // restart within the grace of the same slot
	reg(h2, r)
	h2.tickAt("2026-11-05 10:01:00")
	expectRuns(t, r)
	h2.tickAt("2026-11-05 10:10:10")
	expectRuns(t, r, "2026-11-05 10:10")
}

// An unusable jobs file: the handled slot stays in memory, so the catch-up runs once per process.
func TestSchedulerUnusableFileCatchUpOncePerProcess(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, jobsFile), []byte("{not json"), 0o600); err != nil {
		t.Fatal(err)
	}
	h := newSchedHarness(t, dir)
	r := &runLog{}
	h.sc.register(atJob("bars", "16:20", CatchUpAlways, r))
	h.tickAt("2026-11-05 10:00:00")
	expectRuns(t, r, "2026-11-04 16:20 late")
	h.tickAt("2026-11-05 10:00:30")
	h.tickAt("2026-11-05 12:00:00")
	expectRuns(t, r)
	if !strings.Contains(h.logs.String(), "kept in memory only") {
		t.Fatalf("logs: %s", h.logs)
	}
}

func TestSchedulerMinAt(t *testing.T) {
	h := newSchedHarness(t, t.TempDir())
	r := &runLog{}
	spec := atJob("bars", "16:20", CatchUpAlways, r)
	spec.MinAt = "16:15"
	h.sc.register(spec)
	for _, at := range []string{"16:14", "10:00", "05:00", "23:01"} {
		if _, err := h.sc.update("bars", jobPatch{At: &at}); err != errBadRequest {
			t.Fatalf("at %s: err = %v", at, err)
		}
	}
	for _, at := range []string{"16:15", "23:00"} {
		if _, err := h.sc.update("bars", jobPatch{At: &at}); err != nil {
			t.Fatalf("at %s: err = %v", at, err)
		}
	}
}
