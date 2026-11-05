package stocks

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/justcallmesu/aristotle/server/internal/hermes"
)

// fakeAnalyst stands in for Hermes. Each session answers the scripted replies in order (an error
// entry fails that Ask; "AUTO" is a valid buy_today for the prompt's code); gate, when set, makes Ask
// wait for a value (or the job's ctx).
type fakeAnalyst struct {
	mu        sync.Mutex
	checkErr  error
	openErr   error // Open fails before a session exists
	createErr error // Open fails after the session was created (its id reached the analyzer)
	replies   []any // string or error, consumed across sessions
	gate      chan struct{}
	prompts   []string
	opened    int
	deleted   []string // sessions deleted after a good verdict
	swept     []string // DeleteSession calls
	sweepErrs map[string]error
	slowSweep string // DeleteSession for this id waits for its ctx to end
	deleteErr error
}

func (f *fakeAnalyst) Check(context.Context) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.checkErr
}

func (f *fakeAnalyst) Open(_ context.Context, created func(string)) (AnalystSession, error) {
	f.mu.Lock()
	if f.openErr != nil {
		defer f.mu.Unlock()
		return nil, f.openErr
	}
	f.opened++
	s := &fakeSession{f: f, id: fmt.Sprintf("local-%d", f.opened)}
	createErr := f.createErr
	f.mu.Unlock()
	created(s.id)
	if createErr != nil {
		return nil, createErr
	}
	return s, nil
}

func (f *fakeAnalyst) DeleteSession(ctx context.Context, id string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.swept = append(f.swept, id)
	if id == f.slowSweep {
		f.mu.Unlock()
		<-ctx.Done()
		f.mu.Lock()
		return ctx.Err()
	}
	return f.sweepErrs[id]
}

func (f *fakeAnalyst) snapshot() (prompts, deleted, swept []string, opened int) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]string(nil), f.prompts...), append([]string(nil), f.deleted...), append([]string(nil), f.swept...), f.opened
}

type fakeSession struct {
	f  *fakeAnalyst
	id string
}

func (s *fakeSession) ID() string    { return s.id }
func (s *fakeSession) Model() string { return "claude-opus-5-5" }
func (s *fakeSession) Close()        {}

func (s *fakeSession) Ask(ctx context.Context, prompt string) (string, error) {
	s.f.mu.Lock()
	s.f.prompts = append(s.f.prompts, prompt)
	gate := s.f.gate
	s.f.mu.Unlock()
	if gate != nil {
		select {
		case <-gate:
		case <-ctx.Done():
			return "", ctx.Err()
		}
	}
	s.f.mu.Lock()
	defer s.f.mu.Unlock()
	if len(s.f.replies) == 0 {
		return "", hermes.ErrConnLost
	}
	next := s.f.replies[0]
	s.f.replies = s.f.replies[1:]
	if err, ok := next.(error); ok {
		return "", err
	}
	if next == "AUTO" {
		_, rest, _ := strings.Cut(prompt, "TASK: ")
		return goodReply(rest[:4]), nil
	}
	return next.(string), nil
}

func (s *fakeSession) Delete(context.Context) error {
	s.f.mu.Lock()
	defer s.f.mu.Unlock()
	if s.f.deleteErr != nil {
		return s.f.deleteErr
	}
	s.f.deleted = append(s.f.deleted, s.id)
	return nil
}

// analyzerHarness: TSTA/TSTB/TSTC with rising60 daily bars, a 1600 quote and three 5-minute bars;
// NOBR has only today's partial bar.
func analyzerHarness(t *testing.T, fa *fakeAnalyst) *harness {
	t.Helper()
	h := &harness{t: t, prov: newFakeProvider(), clock: newClock("2026-11-05T10:00:00+07:00"), dir: t.TempDir(), logs: &bytes.Buffer{}}
	h.prov.rangeCharts = map[string]Chart{}
	intra := []Bar{
		{T: wib("2026-11-05 09:00").Unix(), O: 1595, H: 1605, L: 1590, C: 1600, V: 100},
		{T: wib("2026-11-05 09:05").Unix(), O: 1600, H: 1610, L: 1600, C: 1605, V: 200},
		{T: wib("2026-11-05 09:10").Unix(), O: 1605, H: 1605, L: 1595, C: 1600, V: 100},
	}
	for _, c := range []string{"TSTA", "TSTB", "TSTC"} {
		h.prov.rangeCharts[c+"|1y"] = Chart{Code: c, Bars: rising60(), Name: c + " Abadi Tbk"}
		h.prov.rangeCharts[c+"|1d"] = Chart{Code: c, Bars: intra}
		h.prov.quotes[c] = Quote{Code: c, Price: 1600, PrevClose: i64(1590), Volume: i64(500), MarketTime: strp("2026-11-05T09:50:00+07:00")}
	}
	h.prov.rangeCharts["NOBR|1y"] = Chart{Code: "NOBR", Bars: rising60()[60:]}
	if fa != nil {
		h.analyst = fa
	}
	h.start()
	return h
}

// goodReply is a valid buy_today at 1600 for code (the harness has no news for it, so no news:* id).
func goodReply(code string) string {
	r := strings.Replace(reply(nil), `"code":"TSTA"`, `"code":"`+code+`"`, 1)
	return strings.Replace(r, `"news:1"`, `"ind:vol_ratio"`, 1)
}

// waitState polls GET /analysis until the state is not running.
func (h *harness) waitState(code string) map[string]any {
	h.t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		got := h.expect("GET", "/v1/stocks/analysis/"+code, "", 200, "")
		if got["state"] != JobRunning {
			return got
		}
		time.Sleep(5 * time.Millisecond)
	}
	h.t.Fatalf("%s still running", code)
	return nil
}

func TestAnalyzeSuccessKeepsResult(t *testing.T) {
	fa := &fakeAnalyst{replies: []any{goodReply("TSTA")}}
	h := analyzerHarness(t, fa)
	if got := h.expect("GET", "/v1/stocks/analysis/tsta", "", 200, ""); got["state"] != JobIdle || got["result"] != nil || got["job_id"] != nil {
		t.Fatalf("before = %v", got)
	}
	got := h.expect("POST", "/v1/stocks/analyze/tsta", `{"lang":"id"}`, 202, "")
	jobID, _ := got["job_id"].(string)
	if !regexp.MustCompile(`^an_[0-9a-f]{12}$`).MatchString(jobID) || got["state"] != JobRunning || got["started_at"] != "2026-11-05T10:00:00+07:00" {
		t.Fatalf("POST = %v", got)
	}
	got = h.waitState("TSTA")
	res, _ := got["result"].(map[string]any)
	if got["state"] != JobDone || got["job_id"] != jobID || got["error"] != nil || res == nil {
		t.Fatalf("GET = %v", got)
	}
	want := map[string]any{"code": "TSTA", "name": "TSTA Abadi Tbk", "held": false, "lang": "id", "verdict": "buy_today", "confidence": "med",
		"entry_low": 1595.0, "entry_high": 1600.0, "target": 1650.0, "stop": 1570.0, "data_as_of": "2026-11-05T09:50:00+07:00",
		"session_date": "2026-11-05", "market_state": "open", "model": "claude-opus-5-5", "repaired": false, "price": 1600.0,
		"ara": 1985.0, "arb": 1480.0, "analyzed_at": "2026-11-05T10:00:00+07:00"}
	for k, v := range want {
		if res[k] != v {
			t.Errorf("result[%s] = %v, want %v", k, res[k], v)
		}
	}
	if len(res["reasons"].([]any)) != 3 || len(res["flags"].([]any)) != 0 || res["indicators"].(map[string]any)["ind:vwap"] != 1602.0 {
		t.Fatalf("result = %v", res)
	}
	prompts, deleted, _, opened := fa.snapshot()
	if opened != 1 || len(prompts) != 1 || fmt.Sprint(deleted) != "[local-1]" {
		t.Fatalf("opened %d prompts %d deleted %v", opened, len(prompts), deleted)
	}
	for _, w := range []string{"Bahasa Indonesia", "Held: no. Verdicts allowed: buy_today|wait|avoid", `"as_of":"2026-11-05T09:50:00+07:00"`, `"ind:vwap":1602`} {
		if !strings.Contains(prompts[0], w) {
			t.Errorf("prompt lacks %q", w)
		}
	}
	// Logs: job, code, state, duration; never the prompt or the verdict text.
	logs := h.logs.String()
	if !strings.Contains(logs, "job="+jobID) || !strings.Contains(logs, "code=TSTA") || !strings.Contains(logs, "state=done") {
		t.Fatalf("logs = %s", logs)
	}
	for _, secret := range []string{"Uptrend above MA20", "RSI is high", "buy_today", "SYSTEM:"} {
		if strings.Contains(logs, secret) {
			t.Fatalf("log leaks %q: %s", secret, logs)
		}
	}
	// Kept on disk (0600) and reopened after a restart without a new run.
	info, err := os.Stat(filepath.Join(h.dir, analysesFile))
	if err != nil || info.Mode().Perm() != 0o600 {
		t.Fatalf("file: %v %v", info, err)
	}
	h.start()
	got = h.expect("GET", "/v1/stocks/analysis/TSTA", "", 200, "")
	if got["state"] != JobIdle || got["result"].(map[string]any)["verdict"] != "buy_today" {
		t.Fatalf("after restart = %v", got)
	}
	if _, _, _, opened := fa.snapshot(); opened != 1 {
		t.Fatal("reopening must not run the analysis again")
	}
}

func TestAnalyzeHeldRepairsOnce(t *testing.T) {
	hold := reply(map[string]any{"verdict": "hold", "entry": nil, "target": 1700, "stop": 1570, "reasons": []map[string]any{
		{"text": "Above cost", "sources": []string{"hold:pl_pct"}}, {"text": "Trend up", "sources": []string{"ind:trend"}},
		{"text": "Pivot holds", "sources": []string{"lvl:pivot"}}}})
	fa := &fakeAnalyst{replies: []any{goodReply("TSTA"), hold}}
	h := analyzerHarness(t, fa)
	h.expect("PUT", "/v1/stocks/holdings/TSTA", `{"lots":10,"avg_price":1500}`, 200, "")
	h.expect("POST", "/v1/stocks/analyze/TSTA", `{"lang":"en"}`, 202, "")
	got := h.waitState("TSTA")
	res := got["result"].(map[string]any)
	if got["state"] != JobDone || res["verdict"] != "hold" || res["held"] != true || res["repaired"] != true || res["entry_low"] != nil {
		t.Fatalf("GET = %v", got)
	}
	prompts, _, _, _ := fa.snapshot()
	if len(prompts) != 2 || !strings.Contains(prompts[0], "Verdicts allowed: add|hold|sell") || !strings.Contains(prompts[0], `"hold:lots":10`) ||
		!strings.HasPrefix(prompts[1], "Your reply failed validation: verdict must be one of add|hold|sell") {
		t.Fatalf("prompts = %q", prompts)
	}
	if strings.Contains(h.logs.String(), "1500") {
		t.Fatal("holding values reached the log")
	}
}

func TestAnalyzeFailuresKeepLastResult(t *testing.T) {
	offTick := strings.Replace(goodReply("TSTA"), `"target":1650`, `"target":1652`, 1)
	cases := []struct {
		name    string
		replies []any
		openErr error
		want    string
		kept    bool // the Hermes session is kept for the sweep
	}{
		{"rejected twice", []any{offTick, offTick}, nil, "verdict_rejected", true},
		{"invalid twice", []any{"no json", "still no json"}, nil, "invalid_verdict", true},
		{"repair turn fails", []any{offTick, hermes.ErrTurnTimeout}, nil, "timeout", true},
		{"timeout", []any{fmt.Errorf("x: %w", hermes.ErrTurnTimeout)}, nil, "timeout", true},
		{"unexpected prompt", []any{hermes.ErrUnexpectedPrompt}, nil, "unexpected_prompt", true},
		{"interrupted", []any{hermes.ErrTurnInterrupted}, nil, "interrupted", true},
		{"model error", []any{&hermes.TurnError{Reason: "rate_limit"}}, nil, "model_error", true},
		{"socket drop", []any{hermes.ErrConnLost}, nil, "hermes_lost", true},
		{"refused", []any{&hermes.RPCError{Method: "prompt.submit", Code: 4001, Reason: "session_busy"}}, nil, "hermes_refused", true},
		{"bad answer", []any{fmt.Errorf("%w: prompt.submit", hermes.ErrBadAnswer)}, nil, "hermes_refused", true},
		{"tools present", nil, fmt.Errorf("%w: 3", hermes.ErrToolsPresent), "tools_present", false},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			fa := &fakeAnalyst{replies: []any{goodReply("TSTA")}}
			h := analyzerHarness(t, fa)
			h.expect("POST", "/v1/stocks/analyze/TSTA", `{"lang":"en"}`, 202, "")
			first := h.waitState("TSTA")["result"].(map[string]any)["analyzed_at"]

			fa.mu.Lock()
			fa.replies, fa.openErr = c.replies, c.openErr
			fa.mu.Unlock()
			h.clock.Add(time.Hour)
			h.expect("POST", "/v1/stocks/analyze/TSTA", `{"lang":"en"}`, 202, "")
			got := h.waitState("TSTA")
			if got["state"] != JobFailed || got["error"] != c.want || got["finished_at"] != "2026-11-05T11:00:00+07:00" {
				t.Fatalf("GET = %v, want failed %s", got, c.want)
			}
			if got["result"].(map[string]any)["analyzed_at"] != first {
				t.Fatal("a failed job must not replace the kept result")
			}
			_, deleted, _, _ := fa.snapshot()
			st, _ := loadAnalyses(h.dir)
			if fmt.Sprint(deleted) != "[local-1]" || (len(st.Failed) == 1) != c.kept {
				t.Fatalf("deleted %v, failed sessions %v (kept %v)", deleted, st.Failed, c.kept)
			}
			if c.kept && (st.Failed[0].SessionID != "local-2" || st.Failed[0].FailedAt != "2026-11-05T11:00:00+07:00") {
				t.Fatalf("failed = %+v", st.Failed)
			}
		})
	}
}

func TestAnalyzePreflightErrors(t *testing.T) {
	fa := &fakeAnalyst{replies: []any{goodReply("TSTA")}}
	h := analyzerHarness(t, fa)
	h.expect("POST", "/v1/stocks/analyze/TST1", `{"lang":"en"}`, 400, "bad_code")
	for _, body := range []string{``, `{}`, `{"lang":"fr"}`, `{"lang":"en","x":1}`} {
		h.expect("POST", "/v1/stocks/analyze/TSTA", body, 400, "bad_request")
	}
	h.expect("POST", "/v1/stocks/analyze/NOBR", `{"lang":"en"}`, 422, "no_data")
	h.expect("POST", "/v1/stocks/analyze/ZZZQ", `{"lang":"en"}`, 422, "unknown_symbol")
	fa.mu.Lock()
	fa.checkErr = fmt.Errorf("%w: dial", hermes.ErrHermesNotRunning)
	fa.mu.Unlock()
	h.expect("POST", "/v1/stocks/analyze/TSTA", `{"lang":"en"}`, 503, "hermes_unavailable")
	fa.mu.Lock()
	fa.checkErr = fmt.Errorf("%w: aristotle-stocks", hermes.ErrUnknownProfile)
	fa.mu.Unlock()
	h.expect("POST", "/v1/stocks/analyze/TSTA", `{"lang":"en"}`, 503, "analyzer_not_set_up")
	if got := h.expect("GET", "/v1/stocks/analysis/TSTA", "", 200, ""); got["state"] != JobIdle {
		t.Fatalf("a refused start leaves no job: %v", got)
	}
	fa.mu.Lock()
	fa.checkErr = nil
	fa.mu.Unlock()
	h.expect("POST", "/v1/stocks/analyze/TSTA", `{"lang":"en"}`, 202, "") // slots were released
	if got := h.waitState("TSTA"); got["state"] != JobDone {
		t.Fatalf("after errors = %v", got)
	}
	if _, _, _, opened := fa.snapshot(); opened != 1 {
		t.Fatalf("refused starts must not open sessions (opened %d)", opened)
	}

	off := analyzerHarness(t, nil)
	off.expect("POST", "/v1/stocks/analyze/TSTA", `{"lang":"en"}`, 503, "analyzer_not_set_up")
	off.expect("GET", "/v1/stocks/analysis/TST1", "", 400, "bad_code")
}

func TestAnalyzeOneJobPerCodeTwoInTotal(t *testing.T) {
	fa := &fakeAnalyst{replies: []any{"AUTO", "AUTO", "AUTO"}, gate: make(chan struct{})}
	h := analyzerHarness(t, fa)
	h.expect("POST", "/v1/stocks/analyze/TSTA", `{"lang":"en"}`, 202, "")
	h.expect("POST", "/v1/stocks/analyze/TSTA", `{"lang":"en"}`, 409, "analysis_running")
	h.expect("POST", "/v1/stocks/analyze/TSTB", `{"lang":"en"}`, 202, "")
	h.expect("POST", "/v1/stocks/analyze/TSTC", `{"lang":"en"}`, 429, "busy")
	if got := h.expect("GET", "/v1/stocks/analysis/TSTA", "", 200, ""); got["state"] != JobRunning || got["result"] != nil {
		t.Fatalf("running = %v", got)
	}
	fa.gate <- struct{}{}
	fa.gate <- struct{}{}
	for _, c := range []string{"TSTA", "TSTB"} {
		if got := h.waitState(c); got["state"] != JobDone {
			t.Fatalf("%s = %v", c, got)
		}
	}
	close(fa.gate)
	h.expect("POST", "/v1/stocks/analyze/TSTC", `{"lang":"en"}`, 202, "")
	if got := h.waitState("TSTC"); got["state"] != JobDone {
		t.Fatalf("TSTC = %v", got)
	}
}

func TestAnalyzeCloseInterruptsRunningJob(t *testing.T) {
	fa := &fakeAnalyst{gate: make(chan struct{})}
	h := analyzerHarness(t, fa)
	h.expect("POST", "/v1/stocks/analyze/TSTA", `{"lang":"en"}`, 202, "")
	for {
		if prompts, _, _, _ := fa.snapshot(); len(prompts) == 1 {
			break
		}
		time.Sleep(time.Millisecond)
	}
	if st, _ := loadAnalyses(h.dir); len(st.Failed) != 1 || st.Failed[0].SessionID != "local-1" {
		t.Fatalf("the session is on file while its turn runs: %v", st.Failed)
	}
	h.svc.Close()
	h.expect("POST", "/v1/stocks/analyze/TSTB", `{"lang":"en"}`, 503, "shutting_down")
	st, _ := loadAnalyses(h.dir)
	if len(st.Failed) != 1 || h.svc.analyzer.status("TSTA").Error == nil || *h.svc.analyzer.status("TSTA").Error != "interrupted" {
		t.Fatalf("after close: failed %v status %+v", st.Failed, h.svc.analyzer.status("TSTA"))
	}
}

// TestAnalyzeRecordsSessionUntilDeleted: the id is on file from session.create until a delete succeeds,
// whatever fails in between (TA1 review MINOR-2).
func TestAnalyzeRecordsSessionUntilDeleted(t *testing.T) {
	kept := fmt.Errorf("%w: tool_count 3: %w: refused", hermes.ErrToolsPresent, hermes.ErrSessionKept)
	cases := []struct {
		name               string
		openErr, createErr error
		deleteErr          error
		want               string // job error, "" = done
		recorded           bool
		log                string
	}{
		{"deleted after the verdict", nil, nil, nil, "", false, ""},
		{"delete after the verdict fails", nil, nil, hermes.ErrConnLost, "", true, "analysis session not deleted"},
		{"tools present, deleted unused", nil, fmt.Errorf("%w: tool_count 3", hermes.ErrToolsPresent), nil, "tools_present", false, ""},
		{"tools present, delete failed", nil, kept, nil, "tools_present", true, "session=local-1"},
		{"stopped after create", nil, context.Canceled, nil, "interrupted", true, "session=local-1"},
		{"stopped during create", context.Canceled, nil, nil, "interrupted", false, "session=unknown"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			fa := &fakeAnalyst{replies: []any{goodReply("TSTA")}, openErr: c.openErr, createErr: c.createErr, deleteErr: c.deleteErr}
			h := analyzerHarness(t, fa)
			h.expect("POST", "/v1/stocks/analyze/TSTA", `{"lang":"en"}`, 202, "")
			got := h.waitState("TSTA")
			if e, _ := got["error"].(string); e != c.want || got["state"] != jobState(c.want) {
				t.Fatalf("GET = %v, want error %q", got, c.want)
			}
			st, _ := loadAnalyses(h.dir)
			if (len(st.Failed) == 1 && st.Failed[0].SessionID == "local-1") != c.recorded || len(st.Failed) > 1 {
				t.Fatalf("records %v, want recorded %v", st.Failed, c.recorded)
			}
			if c.log != "" && !strings.Contains(h.logs.String(), c.log) {
				t.Fatalf("log lacks %q: %s", c.log, h.logs)
			}
		})
	}
}

func TestAnalyzeRefusesNewJobsOnceClosing(t *testing.T) {
	fa := &fakeAnalyst{replies: []any{"AUTO", "AUTO", "AUTO", "AUTO"}}
	h := analyzerHarness(t, fa)
	var wg sync.WaitGroup
	for _, c := range []string{"TSTA", "TSTB", "TSTC"} {
		wg.Go(func() {
			for range 20 {
				switch status, body, _ := h.do("POST", "/v1/stocks/analyze/"+c, `{"lang":"en"}`); {
				case status == 202, status == 409, status == 429:
				case status == 503 && body["error"] == "shutting_down":
					return
				default:
					t.Errorf("POST %s = %d %v", c, status, body)
					return
				}
			}
		})
	}
	h.svc.Close() // races the POSTs: -race and the WaitGroup must stay quiet
	wg.Wait()
	h.expect("POST", "/v1/stocks/analyze/TSTA", `{"lang":"en"}`, 503, "shutting_down")
}

func TestSweepDeletesFailedSessionsAfterSevenDays(t *testing.T) {
	dir := t.TempDir()
	st := emptyAnalyses()
	st.Failed = []failedSession{
		{SessionID: "local-old", FailedAt: "2026-10-28T09:00:00+07:00"},  // 8 days
		{SessionID: "local-gone", FailedAt: "2026-10-20T09:00:00+07:00"}, // already deleted in Hermes
		{SessionID: "local-new", FailedAt: "2026-11-04T09:00:00+07:00"},  // 1 day: kept for debugging
	}
	writeAnalyses(t, dir, st)
	fa := &fakeAnalyst{sweepErrs: map[string]error{"local-gone": hermes.ErrSessionNotFound}}
	ticks := make(chan time.Time)
	svc := New(Options{StateDir: dir, Provider: newFakeProvider(), Now: newClock("2026-11-05T10:00:00+07:00").Now, Analyst: fa,
		FetchFeed: fixtureFetch(t, "", nil, nil), NewsAfter: func(time.Duration) <-chan time.Time { return nil },
		SweepAfter: func(time.Duration) <-chan time.Time { return ticks }})
	defer svc.Close()
	waitFor(t, func() bool { st, _ := loadAnalyses(dir); return len(st.Failed) == 1 })
	st, _ = loadAnalyses(dir)
	if _, _, swept, _ := fa.snapshot(); st.Failed[0].SessionID != "local-new" || len(swept) != 2 {
		t.Fatalf("after start sweep: failed %v swept %v", st.Failed, swept)
	}
	// A Hermes failure keeps the record for the next daily sweep.
	fa.mu.Lock()
	fa.sweepErrs = map[string]error{"local-new": hermes.ErrHermesNotRunning}
	fa.mu.Unlock()
	svc.analyzer.mu.Lock()
	svc.analyzer.st.Failed[0].FailedAt = "2026-10-01T09:00:00+07:00"
	svc.analyzer.mu.Unlock()
	ticks <- time.Now()
	waitFor(t, func() bool { _, _, swept, _ := fa.snapshot(); return len(swept) == 3 })
	ticks <- time.Now() // the loop is back waiting: the failed sweep finished
	if st := svc.analyzer.status("X"); st.State != JobIdle {
		t.Fatal(st)
	}
	svc.analyzer.mu.Lock()
	n := len(svc.analyzer.st.Failed)
	svc.analyzer.mu.Unlock()
	if n != 1 {
		t.Fatalf("failed sessions after a refused sweep = %d", n)
	}
}

// TestSweepSkipsRefusedAndUnreadableRecords: a refused delete does not stall the later ones (TA1 review
// MINOR-3); an unreadable failed_at is kept and logged, never swept; an unreachable Hermes stops the pass.
func TestSweepSkipsRefusedAndUnreadableRecords(t *testing.T) {
	dir := t.TempDir()
	st := emptyAnalyses()
	st.Failed = []failedSession{
		{SessionID: "local-a", FailedAt: "2026-10-20T09:00:00+07:00"},
		{SessionID: "local-refused", FailedAt: "2026-10-21T09:00:00+07:00"},
		{SessionID: "local-b", FailedAt: "2026-10-22T09:00:00+07:00"},
		{SessionID: "local-bad-time", FailedAt: "yesterday"},
	}
	writeAnalyses(t, dir, st)
	fa := &fakeAnalyst{sweepErrs: map[string]error{"local-refused": &hermes.RPCError{Method: "session.mutate", Code: 4001, Reason: "not_owner"}}}
	logs := &bytes.Buffer{}
	ticks, parked := make(chan time.Time), make(chan struct{}, 4)
	svc := New(Options{StateDir: dir, Provider: newFakeProvider(), Now: newClock("2026-11-05T10:00:00+07:00").Now, Analyst: fa,
		Log: slog.New(slog.NewTextHandler(logs, nil)), FetchFeed: fixtureFetch(t, "", nil, nil),
		NewsAfter:  func(time.Duration) <-chan time.Time { return nil },
		SweepAfter: func(time.Duration) <-chan time.Time { parked <- struct{}{}; return ticks }}) // called once a pass is over
	defer svc.Close()
	<-parked
	st, _ = loadAnalyses(dir)
	if _, _, swept, _ := fa.snapshot(); fmt.Sprint(st.Failed) != "[{local-refused 2026-10-21T09:00:00+07:00} {local-bad-time yesterday}]" ||
		fmt.Sprint(swept) != "[local-a local-refused local-b]" {
		t.Fatalf("after the start sweep: failed %v swept %v", st.Failed, swept)
	}
	for _, want := range []string{"unreadable failed_at are kept, not swept\" file=stocks-analyses.json count=1",
		"retried tomorrow\" count=1 err=hermes_refused", "old analysis sessions deleted\" count=2"} {
		if !strings.Contains(logs.String(), want) {
			t.Fatalf("log lacks %q: %s", want, logs)
		}
	}
	// Hermes unreachable: the pass stops at the first record instead of trying every one.
	svc.analyzer.mu.Lock()
	svc.analyzer.st.Failed = append(svc.analyzer.st.Failed, failedSession{SessionID: "local-c", FailedAt: "2026-10-23T09:00:00+07:00"})
	svc.analyzer.mu.Unlock()
	fa.mu.Lock()
	fa.sweepErrs, fa.swept = map[string]error{"local-refused": fmt.Errorf("%w: dial", hermes.ErrHermesNotRunning)}, nil
	fa.mu.Unlock()
	ticks <- time.Now()
	<-parked
	if _, _, swept, _ := fa.snapshot(); fmt.Sprint(swept) != "[local-refused]" || !strings.Contains(logs.String(), "count=2 err=hermes_lost") {
		t.Fatalf("unreachable: swept %v, logs %s", swept, logs)
	}
}

func TestKeptResultsCappedNewestWins(t *testing.T) {
	h := analyzerHarness(t, &fakeAnalyst{})
	an := h.svc.analyzer
	for i := 0; i < maxAnalyses; i++ {
		c := code4(i)
		an.keep(c, AnalysisResult{Code: c, AnalyzedAt: fmt.Sprintf("2026-11-04T%02d:%02d:00+07:00", 10+i/60, i%60)})
	}
	an.keep("AAAA", AnalysisResult{Code: "AAAA", AnalyzedAt: "2026-11-05T10:00:00+07:00"}) // code4(0) again: replaced, nothing dropped
	an.keep("ZZZZ", AnalysisResult{Code: "ZZZZ", AnalyzedAt: "2026-11-05T10:01:00+07:00"})
	st, _ := loadAnalyses(h.dir)
	_, hasOldest := st.Analyses[code4(1)]
	if len(st.Analyses) != maxAnalyses || hasOldest || st.Analyses["AAAA"].AnalyzedAt != "2026-11-05T10:00:00+07:00" {
		t.Fatalf("kept %d, oldest kept %v, AAAA %v", len(st.Analyses), hasOldest, st.Analyses["AAAA"])
	}
}

func TestUnusableAnalysesFileIsLeftAlone(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, analysesFile)
	if err := os.WriteFile(path, []byte(`{"version":9}`), 0o600); err != nil {
		t.Fatal(err)
	}
	h := &harness{t: t, prov: newFakeProvider(), clock: newClock("2026-11-05T10:00:00+07:00"), dir: dir, logs: &bytes.Buffer{}, analyst: &fakeAnalyst{}}
	h.start()
	h.svc.analyzer.keep("TSTA", AnalysisResult{Code: "TSTA", AnalyzedAt: "x"})
	if b, _ := os.ReadFile(path); string(b) != `{"version":9}` {
		t.Fatalf("file overwritten: %s", b)
	}
	if got := h.expect("GET", "/v1/stocks/analysis/TSTA", "", 200, ""); got["result"] == nil {
		t.Fatal("results stay in memory")
	}
}

// TestNewerAnalysesFileIsReadOnly: after a downgrade the newer file is read (unknown fields ignored) and
// served, but never written.
func TestNewerAnalysesFileIsReadOnly(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, analysesFile)
	newer := `{"version":2,"analyses":{"TSTA":{"code":"TSTA","verdict":"wait","analyzed_at":"2026-11-04T10:00:00+07:00","extra":1}},` +
		`"failed_sessions":[],"settings":{}}`
	if err := os.WriteFile(path, []byte(newer), 0o600); err != nil {
		t.Fatal(err)
	}
	h := &harness{t: t, prov: newFakeProvider(), clock: newClock("2026-11-05T10:00:00+07:00"), dir: dir, logs: &bytes.Buffer{}, analyst: &fakeAnalyst{}}
	h.start()
	if got := h.expect("GET", "/v1/stocks/analysis/TSTA", "", 200, ""); got["result"].(map[string]any)["verdict"] != "wait" {
		t.Fatalf("GET = %v", got)
	}
	h.svc.analyzer.keep("TSTB", AnalysisResult{Code: "TSTB", AnalyzedAt: "x"})
	h.svc.analyzer.recordSession("local-9")
	if b, _ := os.ReadFile(path); string(b) != newer {
		t.Fatalf("file overwritten: %s", b)
	}
	if logs := h.logs.String(); !strings.Contains(logs, "written by a newer server") || !strings.Contains(logs, "version=2") || strings.Contains(logs, "unusable") {
		t.Fatalf("logs = %s", logs)
	}
}

func writeAnalyses(t *testing.T, dir string, st analysesState) {
	t.Helper()
	b, _ := json.Marshal(st)
	if err := os.WriteFile(filepath.Join(dir, analysesFile), b, 0o600); err != nil {
		t.Fatal(err)
	}
}
