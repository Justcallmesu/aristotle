package stocks

import (
	"cmp"
	"context"
	"errors"
	"net/http"
	"slices"
	"sync"
	"time"
)

// The track record (spec Q116-Q120b, P10-P14; protocol §20.5): the track.score job scores the verdict history
// against what the price did in each call's session, and GET /v1/stocks/track-record serves raw counts per verdict,
// model, trigger and confidence (the phone computes any rate) plus the newest calls. Logs carry codes, counts,
// states and error codes only.

const (
	TrackJobID = "track.score"
	trackJobAt = "17:00" // after the 16:20 bars run and the 16:10 closing-auction bars (TR0 §1, §7)
	// scoreReadyAfter: a session is scored from this long after its 00:00 WIB (16:30: its last 5-minute bar is 16:10).
	scoreReadyAfter = 16*time.Hour + 30*time.Minute
	// intradayMaxAge: sessions older than this are scored from daily bars without asking (Yahoo keeps 5-minute
	// bars ~60 days: 56 days answered, 70 days 422; TR0 §1).
	intradayMaxAge = 58 * 24 * time.Hour
	// intradayRetryAge: a 422 means "too old" only for a window starting with a session older than this (P16a); such a
	// window is asked once more without those sessions, so only they fall back to daily bars (P15a).
	intradayRetryAge = 50 * 24 * time.Hour
	// giveUpTradingDays: a record still unscored this many trading days after its session, its code failing again
	// after failing on an earlier day, is scored unscorable (P16, P16a).
	giveUpTradingDays = 10
	// info3dMaxAge: a scored record whose info_3d is still missing this long after its session is not asked again.
	info3dMaxAge = 30 * 24 * time.Hour
	// info3dEarliest: info_3d needs at least this long after the session (3 trading days are at least 3 days later).
	info3dEarliest = 3*24*time.Hour + scoreReadyAfter
	maxTrackCalls  = 50
)

// trackPlan is one code's work in a run: records to score and scored records missing info_3d.
type trackPlan struct {
	score []VerdictRecord
	info  []VerdictRecord
}

// span is the earliest and latest session day of recs whose day passes keep (zero times when none).
func span(recs []VerdictRecord, keep func(day time.Time) bool) (first, last time.Time) {
	for _, r := range recs {
		d, ok := parseSessionDay(r.SessionDate)
		if !ok || !keep(d) {
			continue
		}
		if first.IsZero() || d.Before(first) {
			first = d
		}
		if d.After(last) {
			last = d
		}
	}
	return first, last
}

func anyDay(time.Time) bool { return true }

// planTrack groups the records the run works on by code: unscored ones whose session has closed, and scored ones
// whose info_3d may now exist (never no_session or unscorable ones, P16). Records that can never be scored (logged
// once at load) are counted in skipped, never planned.
func planTrack(recs []VerdictRecord, now time.Time) (plans map[string]*trackPlan, skipped int) {
	plans = map[string]*trackPlan{}
	plan := func(code string) *trackPlan {
		if plans[code] == nil {
			plans[code] = &trackPlan{}
		}
		return plans[code]
	}
	for _, r := range recs {
		day, _ := parseSessionDay(r.SessionDate)
		switch {
		case !scorable(r):
			skipped++
		case r.Score == nil && !now.Before(day.Add(scoreReadyAfter)):
			plan(r.Code).score = append(plan(r.Code).score, r)
		case r.Score != nil && wantsInfo3d(*r.Score) && !now.Before(day.Add(info3dEarliest)) && now.Sub(day) <= info3dMaxAge:
			plan(r.Code).info = append(plan(r.Code).info, r)
		}
	}
	return plans, skipped
}

// wantsInfo3d: a score whose info_3d is missing and can exist (a no_session or unscorable record has none, P16).
func wantsInfo3d(sc Score) bool {
	return sc.Info3dPct == nil && sc.Outcome != OutcomeNoSession && sc.Outcome != OutcomeUnscorable
}

// tradingDaysAfter counts the IDX trading days after day up to now's date, stopping at limit.
func tradingDaysAfter(day, now time.Time, limit int) int {
	n := 0
	for d := day.AddDate(0, 0, 1); n < limit && !d.After(now); d = d.AddDate(0, 0, 1) {
		if IsTradingDay(d) {
			n++
		}
	}
	return n
}

// trackFetch is one run's Yahoo access: the calls made, what stopped the run, whether the current code failed.
type trackFetch struct {
	s      *Service
	ctx    context.Context
	now    time.Time
	asked  int
	stop   error
	failed bool // this code's data is not usable this run (a transient Yahoo failure)
}

// call asks Yahoo for a window, paced like the bars job (no wait before a run's first call).
func (f *trackFetch) call(code string, from, to time.Time, interval string) (Chart, error) {
	if f.asked > 0 {
		if err := f.s.barsPace(f.ctx); err != nil {
			return Chart{}, err
		}
	}
	f.asked++
	return f.s.market.ChartPeriodDirect(f.ctx, code, from, to, interval)
}

// isStop: a block, a network error (no HTTP answer, P16a) or the server stopping ends the run; the rest waits for the
// next.
func isStop(ctx context.Context, err error) bool {
	var be *BlockedError
	return errors.As(err, &be) || isNetwork(err) || ctx.Err() != nil
}

// stopCode is the run's last_error for what stopped it.
func stopCode(err error) string {
	if isNetwork(err) {
		return "network_error"
	}
	_, code := jobOutcome(err)
	return code
}

// tooOld: Yahoo's answer for a 5-minute window it no longer holds (422, P15a).
func tooOld(err error) bool {
	var ue *UpstreamError
	return errors.As(err, &ue) && ue.Status == http.StatusUnprocessableEntity
}

// intraday is the code's 5-minute bars for its unscored sessions still inside Yahoo's 5-minute range: one window
// over them. Sessions without 5-minute bars score from daily bars, which happens only when Yahoo said it has none
// (P15a): a 200 without bars, or a 422 for a window starting with a session older than intradayRetryAge (too old,
// P16a). Such a window is asked once more without those sessions, so only they fall back. unknown reports an unknown
// symbol (the caller trusts it only when Yahoo has no daily bars either). Any other failure, a 422 for recent
// sessions included, marks the code failed (its records wait for the next run); a block or a network error stops
// the run.
func (f *trackFetch) intraday(code string, p trackPlan) (bars []Bar, unknown bool) {
	first, last := span(p.score, func(d time.Time) bool { return f.now.Sub(d) <= intradayMaxAge })
	if last.IsZero() {
		return nil, false
	}
	ch, err := f.call(code, first, last.AddDate(0, 0, 1), Interval5m)
	if tooOld(err) && f.now.Sub(first) > intradayRetryAge {
		if first, last = span(p.score, func(d time.Time) bool { return f.now.Sub(d) <= intradayRetryAge }); last.IsZero() {
			return nil, false // every session asked is too old for 5-minute bars: they fall back
		}
		ch, err = f.call(code, first, last.AddDate(0, 0, 1), Interval5m)
	}
	switch {
	case err == nil:
		return ch.Bars, false
	case isStop(f.ctx, err):
		f.stop = cmp.Or(f.ctx.Err(), err)
	case errors.Is(err, ErrUnknownSymbol):
		return nil, true
	default:
		f.failed = true
	}
	return nil, false
}

// daily are the code's completed daily bars: from the bar store when it holds what the run needs, else one 1d
// window from Yahoo.
func (f *trackFetch) daily(code string, p trackPlan) []Bar {
	first, _ := span(append(slices.Clone(p.score), p.info...), anyDay)
	if sb, ok := f.s.bars.get(code); ok && len(sb.bars) > 0 && storeAnswers(sb.fetchedAt, p, f.now) {
		return sb.bars
	}
	ch, err := f.call(code, first, f.now, Interval1d)
	switch {
	case err == nil:
		return completedBars(ch.Bars, SessionDate(f.now))
	case isStop(f.ctx, err):
		f.stop = cmp.Or(f.ctx.Err(), err)
	case !errors.Is(err, ErrUnknownSymbol):
		f.failed = true
	}
	return nil
}

// storeAnswers: a store fetched at fetchedAt holds the bars p needs: fetched after the latest session to score had
// closed; for info_3d only, current (it holds every completed session, so a later session's bar is not missed when
// bars.daily is behind or off).
func storeAnswers(fetchedAt time.Time, p trackPlan, now time.Time) bool {
	if len(p.score) == 0 {
		return barsFresh(fetchedAt, now)
	}
	_, lastScore := span(p.score, anyDay)
	return !fetchedAt.Before(lastScore.Add(barsFreshAfter))
}

// trackRun counts one run's work for its log line and outcome.
type trackRun struct {
	scored, filled, failed, gaveUp, done int
}

// runTrackScore is the track.score job (P11): it scores every unscored record whose session has closed, then fills
// missing info_3d. At most one 5-minute request per code (one window over its sessions; a second one only after a
// 422, P15a), plus one daily window when the bar store cannot answer; calls paced like the bars job. A block stops
// the run (so does a network error, P16a): what was scored is kept. A code that fails again after failing on an
// earlier day gives up its records 10 trading days old (P16, P16a).
func (s *Service) runTrackScore(ctx context.Context, _ JobRun) error {
	now := s.now()
	hist := s.analyzer.history
	plans, skipped := planTrack(hist.snapshot(), now)
	codes := sortedKeys(plans)
	f := &trackFetch{s: s, ctx: ctx, now: now}
	updates := map[string]Score{}
	var n trackRun
	for _, code := range codes {
		p := *plans[code]
		f.failed = false
		var intraday, daily []Bar
		unknown := false
		if len(p.score) > 0 {
			intraday, unknown = f.intraday(code, p)
		}
		if f.stop == nil && !f.failed {
			daily = f.daily(code, p)
		}
		if f.stop != nil {
			break
		}
		// A 5-minute "unknown symbol" for a code Yahoo has daily bars of is not "Yahoo has none" (P15a): it waits.
		// With no daily bars either the code is unknown and its records score no_session.
		f.failed = f.failed || (unknown && len(daily) > 0)
		n.done++
		if f.failed {
			n.failed++
			n.gaveUp += s.giveUp(code, p.score, now, updates)
			continue
		}
		s.trackFails.clear(code)
		for _, r := range p.score {
			updates[r.ID] = scoreRecord(r, intraday, daily, now)
			n.scored++
		}
		n.filled += s.fillInfo3d(code, p.info, daily, updates)
	}
	saveErr := hist.apply(updates)
	s.log.Info("stocks: track-record run", "codes", len(codes), "scored", n.scored, "info_filled", n.filled, "failed", n.failed,
		"left", len(codes)-n.done, "requests", f.asked, "unscorable", n.gaveUp, "skipped", skipped)
	partial := n.scored+n.filled+n.gaveUp > 0
	switch {
	case saveErr != nil:
		return &JobError{Code: "state_error"}
	case f.stop != nil:
		return &JobError{Code: stopCode(f.stop), Partial: partial}
	case n.failed > 0:
		return &JobError{Code: "codes_failed", Partial: partial}
	}
	return nil
}

// giveUp scores unscorable the records of a failed code that are giveUpTradingDays trading days past their session,
// only when the code also failed on an earlier day's run (P16a: one failing run after an offline gap gives up
// nothing), and returns how many.
func (s *Service) giveUp(code string, recs []VerdictRecord, now time.Time, updates map[string]Score) int {
	if len(recs) == 0 || !s.trackFails.failedBefore(code, now) {
		return 0
	}
	n := 0
	for _, r := range recs {
		day, _ := parseSessionDay(r.SessionDate)
		if tradingDaysAfter(day, now, giveUpTradingDays) >= giveUpTradingDays {
			updates[r.ID] = unscorableScore(now)
			n++
		}
	}
	if n > 0 {
		s.log.Warn("stocks: track-record records unscorable (Yahoo kept failing)", "code", code, "count", n)
	}
	return n
}

// failStreaks keeps, per code, the WIB date of the first run of its current failing streak (a run that scored the code
// ends it). In memory only: a restart forgets it, which only delays a give-up by one failing day (P16a).
type failStreaks struct {
	mu    sync.Mutex
	since map[string]string
}

// failedBefore notes that code failed in the run at now and reports whether its streak began on an earlier date.
func (f *failStreaks) failedBefore(code string, now time.Time) bool {
	day := now.In(WIB).Format(time.DateOnly)
	f.mu.Lock()
	defer f.mu.Unlock()
	first, ok := f.since[code]
	if !ok {
		if f.since == nil {
			f.since = map[string]string{}
		}
		f.since[code] = day
	}
	return ok && first < day
}

// clear ends code's failing streak.
func (f *failStreaks) clear(code string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	delete(f.since, code)
}

// fillInfo3d sets the missing info_3d of scored records from daily and returns how many; a record whose session close
// moved in the refetched bars (a split) is left empty and logged (P16).
func (s *Service) fillInfo3d(code string, recs []VerdictRecord, daily []Bar, updates map[string]Score) int {
	n := 0
	for _, r := range recs {
		v, moved := laterInfo3d(r, daily)
		if moved {
			s.log.Info("stocks: info_3d skipped (session close moved: split or no bar)", "code", code)
			continue
		}
		if v != nil {
			sc := *r.Score
			sc.Info3dPct = v
			updates[r.ID] = sc
			n++
		}
	}
	return n
}

// --- HTTP ---

// trackGroup is one summary row: raw counts of the scored calls with this verdict, model, trigger and confidence.
type trackGroup struct {
	Verdict      string   `json:"verdict"`
	Model        string   `json:"model"`
	Trigger      string   `json:"trigger"`
	Confidence   string   `json:"confidence"`
	N            int      `json:"n"`
	Win          int      `json:"win"`
	Loss         int      `json:"loss"`
	Closed       int      `json:"closed"`
	NotTriggered int      `json:"not_triggered"`
	Right        int      `json:"right"`
	Wrong        int      `json:"wrong"`
	WouldHave    int      `json:"would_have"`
	NoSession    int      `json:"no_session"`
	Unscorable   int      `json:"unscorable"`
	ResultN      int      `json:"result_n"`
	ResultSum    float64  `json:"result_sum"`
	ResultAvg    *float64 `json:"result_avg"`
	Info3dN      int      `json:"info_3d_n"`
	Info3dSum    float64  `json:"info_3d_sum"`
	Info3dAvg    *float64 `json:"info_3d_avg"`
}

type trackBody struct {
	Summary       []trackGroup    `json:"summary"`
	Calls         []VerdictRecord `json:"calls"`
	ScoredThrough *string         `json:"scored_through"`
	Pending       int             `json:"pending"`
}

func (s *Service) getTrackRecord(r *http.Request) (int, any, error) {
	code := ""
	if raw := r.URL.Query().Get("code"); raw != "" {
		c, err := normCode(raw)
		if err != nil {
			return 0, nil, err
		}
		code = c
	}
	return http.StatusOK, trackRecord(s.analyzer.history.snapshot(), code), nil
}

// trackRecord builds the answer: the summary over every scored record, the newest calls (of code when given).
func trackRecord(recs []VerdictRecord, code string) trackBody {
	body := trackBody{Summary: summarize(recs), Calls: []VerdictRecord{}}
	through := ""
	for _, r := range recs {
		if r.Score == nil {
			body.Pending++
		} else if r.SessionDate > through {
			through = r.SessionDate
		}
	}
	body.ScoredThrough = nonEmpty(through)
	newest := slices.Clone(recs)
	slices.SortStableFunc(newest, func(a, b VerdictRecord) int { return cmp.Compare(b.AnalyzedAt, a.AnalyzedAt) })
	for _, r := range newest {
		if len(body.Calls) == maxTrackCalls {
			break
		}
		if code == "" || r.Code == code {
			body.Calls = append(body.Calls, r)
		}
	}
	return body
}

func summarize(recs []VerdictRecord) []trackGroup {
	groups := map[[4]string]*trackGroup{}
	for _, r := range recs {
		if r.Score == nil {
			continue
		}
		key := [4]string{r.Verdict, r.Model, r.Trigger, r.Confidence}
		g := groups[key]
		if g == nil {
			g = &trackGroup{Verdict: r.Verdict, Model: r.Model, Trigger: r.Trigger, Confidence: r.Confidence}
			groups[key] = g
		}
		g.add(*r.Score)
	}
	out := make([]trackGroup, 0, len(groups))
	for _, g := range groups {
		g.ResultSum, g.Info3dSum = *round2(g.ResultSum), *round2(g.Info3dSum)
		g.ResultAvg, g.Info3dAvg = avg(g.ResultSum, g.ResultN), avg(g.Info3dSum, g.Info3dN)
		out = append(out, *g)
	}
	slices.SortFunc(out, func(a, b trackGroup) int {
		return cmp.Or(cmp.Compare(a.Verdict, b.Verdict), cmp.Compare(a.Model, b.Model), cmp.Compare(a.Trigger, b.Trigger),
			cmp.Compare(a.Confidence, b.Confidence))
	})
	return out
}

func (g *trackGroup) add(sc Score) {
	g.N++
	counts := map[string]*int{OutcomeWin: &g.Win, OutcomeLoss: &g.Loss, OutcomeClosed: &g.Closed, OutcomeNotTriggered: &g.NotTriggered,
		OutcomeRight: &g.Right, OutcomeWrong: &g.Wrong, OutcomeWouldHave: &g.WouldHave, OutcomeNoSession: &g.NoSession,
		OutcomeUnscorable: &g.Unscorable}
	if c := counts[sc.Outcome]; c != nil {
		*c++
	}
	if sc.ResultPct != nil {
		g.ResultN++
		g.ResultSum += *sc.ResultPct
	}
	if sc.Info3dPct != nil {
		g.Info3dN++
		g.Info3dSum += *sc.Info3dPct
	}
}

func avg(sum float64, n int) *float64 {
	if n == 0 {
		return nil
	}
	return round2(sum / float64(n))
}
