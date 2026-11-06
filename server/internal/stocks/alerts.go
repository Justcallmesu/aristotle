package stocks

import (
	"cmp"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"net/http"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/justcallmesu/aristotle/server/internal/statefile"
)

// Slice 3 alerts (spec Q18, Q87, Q104, Q107-Q109; TSC0 design §2; protocol §20.4). The engine turns new market
// data into alerts kept in a ring in stocks-alerts.json; the phone reads them by long-poll
// (GET /v1/stocks/alerts?after=<seq>&wait=50). Nothing is pushed: a phone that is not connected simply
// reads what it missed from the ring when it comes back (Q109).
//
//   - level: after each scan, a holding's hand-set stop/target (Q104) or a kept verdict for today
//     (entry zone, target, stop; Q18) touched by the scan's usable quotes.
//   - setup: bullish hits of the screener's hits log with origin scan or eod on a watched or held code; when such
//     a code has ≥ 2 bullish setups on one session date, an automatic analysis on Sonnet (Q107, Q110) within the
//     day's cap of 5 (Q87) and never twice per code per day; its result posts an analysis alert.
//   - news: after each news poll, a strong match on a watched/held code published 08:00-16:30 (Q108).
//   - brief: the 08:30 brief is ready (always notified).
//
// Dedupe: a key (day, kind, code, subject) fires once per WIB day. Caps: 3 per code and 30 per day are notified,
// later ones are kept silent (one summary alert says so). Outside 08:00-16:30 of a trading day every alert
// except the brief is kept silent (in the list, no notification). Logs carry kinds, codes and counts only:
// never an alert's text, a verdict or a holding value.

const (
	alertsFile        = "stocks-alerts.json"
	alertsVersion     = 1
	maxAlerts         = 300
	alertKeepDays     = 7
	maxAlertsPerCode  = 3
	maxAlertsPerDay   = 30
	AutoRunCap        = 5
	autoFreshVerdict  = 60 * time.Minute // a verdict for today younger than this: no automatic run
	autoMinSetups     = 2                // Q107: a code with at least this many bullish setups on one session date
	autoStartTimeout  = 30 * time.Second
	newsAlertMaxAge   = 60 * time.Minute // an article older than this at the poll is not breaking news
	maxAlertWait      = 50               // seconds, GET ?wait
	alertsListN       = 50
	maxAlertTitle     = 160
	AlertsJobID       = "alerts"
	NewsJobID         = "news"
	alertsEveryMin    = 5
	newsEveryMin      = 10
	defaultBriefLang  = analysisLangID // Q105: Indonesian until the phone has sent a language
	alertHoursFromMin = 8 * 60         // 08:00
	alertHoursToMin   = 16*60 + 30     // 16:30
)

// Alert kinds.
const (
	AlertLevel    = "level"
	AlertSetup    = "setup"
	AlertNews     = "news"
	AlertAnalysis = "analysis"
	AlertBrief    = "brief"
	AlertSummary  = "summary"
)

// Alert is one item of the ring (GET /v1/stocks/alerts).
type Alert struct {
	Seq      int64          `json:"seq"`
	ID       string         `json:"id"`
	Kind     string         `json:"kind"`
	Code     *string        `json:"code"`
	Title    string         `json:"title"`
	Text     string         `json:"text"`
	At       string         `json:"at"`
	DataAsOf *string        `json:"data_as_of"`
	Silent   bool           `json:"silent"` // kept for the list, not notified (quiet hours or over a cap)
	Detail   map[string]any `json:"detail"`
}

type autoRuns struct {
	Date  string   `json:"date"`
	Used  int      `json:"used"`
	Codes []string `json:"codes"`
}

// alertsDoc is stocks-alerts.json.
type alertsDoc struct {
	Version    int               `json:"version"`
	Seq        int64             `json:"seq"`
	Items      []Alert           `json:"items"`
	Fired      map[string]string `json:"fired"`       // dedupe key → WIB day
	HitsCursor *int64            `json:"hits_cursor"` // the last screener hit seq read (nil: start at the newest)
	AutoRuns   autoRuns          `json:"auto_runs"`
	Lang       string            `json:"lang,omitempty"` // the language the phone sent last (Q105)
}

type alertEngine struct {
	svc  *Service
	proc sync.Mutex // one evaluation at a time: dedupe checks and the auto-run cap stay consistent

	mu      sync.Mutex
	doc     alertsDoc
	persist bool
	noRuns  bool          // the "no automatic runs while the counter cannot be saved" reason was logged
	wake    chan struct{} // closed and replaced on every new item (long-poll wake-up)
	done    chan struct{} // closed by close
	closed  bool
}

func newAlertEngine(s *Service) *alertEngine {
	ae := &alertEngine{svc: s, persist: true, wake: make(chan struct{}), done: make(chan struct{})}
	doc, err := loadAlerts(s.dir)
	if err != nil {
		ae.persist = false
		s.log.Error("stocks: alerts file unusable; alerts are kept in memory until it is fixed or removed", "file", alertsFile, "err", err)
	}
	if doc.HitsCursor == nil {
		// First start (or a lost file): old hits are history, not news.
		s.screen.mu.Lock()
		seq := s.screen.doc.Seq
		s.screen.mu.Unlock()
		doc.HitsCursor = &seq
	}
	ae.doc = doc
	return ae
}

func emptyAlerts() alertsDoc {
	return alertsDoc{Version: alertsVersion, Items: []Alert{}, Fired: map[string]string{}, AutoRuns: autoRuns{Codes: []string{}}}
}

func loadAlerts(dir string) (alertsDoc, error) {
	data, err := statefile.Read(dir, alertsFile)
	if errors.Is(err, fs.ErrNotExist) {
		return emptyAlerts(), nil
	}
	if err != nil {
		return emptyAlerts(), err
	}
	var doc alertsDoc
	if err := decodeStrict(data, &doc); err != nil {
		return emptyAlerts(), fmt.Errorf("%s: %w", alertsFile, err)
	}
	if doc.Version != alertsVersion {
		return emptyAlerts(), fmt.Errorf("%s: version %d, want %d", alertsFile, doc.Version, alertsVersion)
	}
	if doc.Items == nil {
		doc.Items = []Alert{}
	}
	if doc.Fired == nil {
		doc.Fired = map[string]string{}
	}
	if doc.AutoRuns.Codes == nil {
		doc.AutoRuns.Codes = []string{}
	}
	if doc.Lang != analysisLangID && doc.Lang != analysisLangEN {
		doc.Lang = ""
	}
	return doc, nil
}

// saveLocked writes the alerts file and reports a failed write (nil when nothing is persisted).
func (ae *alertEngine) saveLocked() error {
	if !ae.persist {
		return nil
	}
	err := statefile.WriteAtomic(ae.svc.dir, alertsFile, mustJSON(ae.doc))
	if err != nil {
		ae.svc.log.Error("stocks: saving alerts failed", "err", err)
	}
	return err
}

func (ae *alertEngine) close() {
	ae.mu.Lock()
	defer ae.mu.Unlock()
	if !ae.closed {
		ae.closed = true
		close(ae.done)
	}
}

// on: the alerts job is switched on (Q20). Off = no alert and no automatic run.
func (ae *alertEngine) on() bool { return ae.svc.sched.isOn(AlertsJobID) }

// lang is the language for alert and brief texts: the phone's last, else Indonesian (Q105).
func (ae *alertEngine) lang() string {
	ae.mu.Lock()
	defer ae.mu.Unlock()
	return cmp.Or(ae.doc.Lang, defaultBriefLang)
}

// setLang records the language the phone sent (analyze body, alerts query, brief body).
func (ae *alertEngine) setLang(lang string) {
	if lang != analysisLangID && lang != analysisLangEN {
		return
	}
	ae.mu.Lock()
	defer ae.mu.Unlock()
	if ae.doc.Lang != lang {
		ae.doc.Lang = lang
		ae.saveLocked()
	}
}

// alertHours: 08:00-16:30 of a trading day (Q108). Outside, alerts are kept silent.
func alertHours(now time.Time) bool {
	now = now.In(WIB)
	m := now.Hour()*60 + now.Minute()
	return IsTradingDay(now) && m >= alertHoursFromMin && m < alertHoursToMin
}

// alertWindows / newsWindows are the alerts and news job windows (trading days).
func alertWindows(day time.Time) []Window {
	return []Window{{Start: day.Add(alertHoursFromMin * time.Minute), End: day.Add(alertHoursToMin * time.Minute)}}
}

func newsWindows(day time.Time) []Window {
	return []Window{{Start: day.Add(6 * time.Hour), End: day.Add(alertHoursToMin * time.Minute)}}
}

// alertDraft is an alert before dedupe and the caps.
type alertDraft struct {
	kind, code, subject string // subject completes the dedupe key: the level, the article id, "" for one per code
	title, text         string
	dataAsOf            *string
	detail              map[string]any
	loud                bool // never silent (the brief)
}

// emit dedupes d and appends it; it reports whether it was added. A brief alert is never capped or silenced.
func (ae *alertEngine) emit(now time.Time, d alertDraft) bool {
	now = now.In(WIB)
	day := dayString(now)
	key := day + "|" + d.kind + "|" + d.code + "|" + d.subject
	ae.mu.Lock()
	defer ae.mu.Unlock()
	if _, ok := ae.doc.Fired[key]; ok {
		return false
	}
	ae.doc.Fired[key] = day
	silent := !d.loud && !alertHours(now)
	var summary *Alert
	if !d.loud && !silent {
		perCode, perDay, summarized := 0, 0, false
		for _, it := range ae.doc.Items {
			if !strings.HasPrefix(it.At, day) || it.Silent {
				continue
			}
			switch it.Kind {
			case AlertBrief:
				continue
			case AlertSummary:
				summarized = true
				continue
			}
			perDay++
			if it.Code != nil && *it.Code == d.code {
				perCode++
			}
		}
		switch {
		case perDay >= maxAlertsPerDay:
			silent = true
			if !summarized {
				l := cmp.Or(ae.doc.Lang, defaultBriefLang)
				summary = &Alert{Kind: AlertSummary, Title: tr(l, "More alerts today", "Masih ada peringatan hari ini"),
					Text:   tr(l, "Later alerts today are in the list without a notification.", "Peringatan berikutnya hari ini ada di daftar tanpa notifikasi."),
					Detail: map[string]any{"cap": maxAlertsPerDay}}
			}
		case d.code != "" && perCode >= maxAlertsPerCode:
			silent = true
		}
	}
	if summary != nil {
		ae.appendLocked(now, *summary)
	}
	a := Alert{Kind: d.kind, Title: truncate(d.title, maxAlertTitle), Text: d.text, DataAsOf: d.dataAsOf, Silent: silent, Detail: d.detail}
	if d.code != "" {
		c := d.code
		a.Code = &c
	}
	if a.Detail == nil {
		a.Detail = map[string]any{}
	}
	ae.appendLocked(now, a)
	ae.pruneLocked(now)
	ae.saveLocked()
	ae.svc.log.Info("stocks: alert", "kind", d.kind, "code", d.code, "silent", silent, "seq", ae.doc.Seq)
	return true
}

func (ae *alertEngine) appendLocked(now time.Time, a Alert) {
	ae.doc.Seq++
	a.Seq, a.ID, a.At = ae.doc.Seq, "al_"+randomHex(6), *rfc(now)
	ae.doc.Items = append(ae.doc.Items, a)
	close(ae.wake)
	ae.wake = make(chan struct{})
}

// pruneLocked keeps 300 items of the last 7 days and the dedupe keys of today and yesterday.
func (ae *alertEngine) pruneLocked(now time.Time) {
	cut := *rfc(now.AddDate(0, 0, -alertKeepDays))
	items := slices.DeleteFunc(slices.Clone(ae.doc.Items), func(a Alert) bool { return a.At < cut })
	if n := len(items); n > maxAlerts {
		items = items[n-maxAlerts:]
	}
	ae.doc.Items = items
	keep := dayString(now.AddDate(0, 0, -1))
	for k, d := range ae.doc.Fired {
		if d < keep {
			delete(ae.doc.Fired, k)
		}
	}
}

// --- level alerts (after each scan) ---

// afterScan checks the scan's usable quotes against the holdings' stop/target and today's kept verdicts, then
// reads the new hits. A quote that is stale, old or not today's never alerts ("nothing while stale").
func (ae *alertEngine) afterScan(now time.Time, quotes map[string]Quote) {
	if ae.on() {
		ae.levelAlerts(now, quotes)
	}
	ae.afterHits() // also moves the hits cursor while alerts are off
}

func (ae *alertEngine) levelAlerts(now time.Time, quotes map[string]Quote) {
	ae.proc.Lock()
	defer ae.proc.Unlock()
	now = now.In(WIB)
	day := at(now, clock{0, 0})
	open := at(day, daySessions(now.Weekday())[0].start)
	l := ae.lang()
	rangeOf := func(q Quote, since string) (lo, hi int64) {
		lo, hi = q.Price, q.Price
		if t, err := time.Parse(time.RFC3339, since); err == nil && t.Before(open) {
			// Set before today's session: the whole day counts.
			if q.Low != nil {
				lo = min(lo, *q.Low)
			}
			if q.High != nil {
				hi = max(hi, *q.High)
			}
		}
		return lo, hi
	}
	levels := ae.svc.holdingLevels()
	for _, code := range sortedKeys(levels) {
		lv := levels[code]
		q, ok := quotes[code]
		if !ok || !usableQuote(q, day, now) {
			continue
		}
		lo, hi := rangeOf(q, lv.SetAt)
		if lv.Stop != nil && lo <= *lv.Stop {
			ae.emit(now, levelDraft(l, code, "hold_stop", q, *lv.Stop))
		}
		if lv.Target != nil && hi >= *lv.Target {
			ae.emit(now, levelDraft(l, code, "hold_target", q, *lv.Target))
		}
	}
	for _, res := range ae.svc.analyzer.kept() {
		q, ok := quotes[res.Code]
		if !ok || res.SessionDate != dayString(day) || !usableQuote(q, day, now) {
			continue
		}
		lo, hi := rangeOf(q, res.AnalyzedAt)
		switch res.Verdict {
		case "buy_today", "add":
			if res.EntryLow != nil && res.EntryHigh != nil && q.Price >= *res.EntryLow && q.Price <= *res.EntryHigh {
				d := levelDraft(l, res.Code, "entry", q, *res.EntryLow)
				d.detail["value_high"] = *res.EntryHigh
				d.text = fmt.Sprintf(tr(l, "Price %d in the entry zone %d-%d", "Harga %d di zona beli %d-%d"), q.Price, *res.EntryLow, *res.EntryHigh)
				ae.emit(now, d)
			}
			fallthrough
		case "hold":
			if res.Target != nil && hi >= *res.Target {
				ae.emit(now, levelDraft(l, res.Code, "target", q, *res.Target))
			}
			if res.Stop != nil && lo <= *res.Stop {
				ae.emit(now, levelDraft(l, res.Code, "stop", q, *res.Stop))
			}
		}
	}
}

func levelDraft(l, code, level string, q Quote, value int64) alertDraft {
	var title, text string
	switch level {
	case "hold_stop":
		title = code + tr(l, " hit your stop", " menyentuh stop Anda")
		text = fmt.Sprintf(tr(l, "Price %d, your stop %d", "Harga %d, stop Anda %d"), q.Price, value)
	case "hold_target":
		title = code + tr(l, " reached your target", " mencapai target Anda")
		text = fmt.Sprintf(tr(l, "Price %d, your target %d", "Harga %d, target Anda %d"), q.Price, value)
	case "entry":
		title = code + tr(l, " is in the entry zone", " di zona beli")
	case "target":
		title = code + tr(l, " reached the analysis target", " mencapai target analisis")
		text = fmt.Sprintf(tr(l, "Price %d, target %d", "Harga %d, target %d"), q.Price, value)
	case "stop":
		title = code + tr(l, " hit the analysis stop", " menyentuh stop analisis")
		text = fmt.Sprintf(tr(l, "Price %d, stop %d", "Harga %d, stop %d"), q.Price, value)
	}
	source := "analysis"
	if strings.HasPrefix(level, "hold_") {
		source = "holding"
	}
	return alertDraft{kind: AlertLevel, code: code, subject: level, title: title, text: text, dataAsOf: q.MarketTime,
		detail: map[string]any{"level": strings.TrimPrefix(level, "hold_"), "source": source, "price": q.Price, "value": value}}
}

// --- setup alerts and automatic runs (the hits log) ---

// afterHits reads the screener hits after the cursor: bullish scan/eod hits on watched or held codes become one
// setup alert per code per day, and may start an automatic analysis.
func (ae *alertEngine) afterHits() {
	ae.proc.Lock()
	defer ae.proc.Unlock()
	sc := ae.svc.screen
	ae.mu.Lock()
	cursor := *ae.doc.HitsCursor
	ae.mu.Unlock()
	sc.mu.Lock()
	all, seq := slices.Clone(sc.doc.Hits), sc.doc.Seq
	sc.mu.Unlock()
	if seq < cursor {
		cursor = 0 // the screener file was replaced (its seq restarted): read what it holds; dedupe stops repeats
	}
	var fresh []ScreenHit
	last := cursor
	for _, h := range all {
		if h.Seq > cursor {
			fresh = append(fresh, h)
			last = max(last, h.Seq)
		}
	}
	if last == cursor {
		return
	}
	ae.mu.Lock()
	ae.doc.HitsCursor = &last
	ae.saveLocked()
	ae.mu.Unlock()
	if !ae.on() {
		return
	}
	st, stErr := ae.svc.snapshot()
	followed := func(code string) bool {
		return stErr == nil && (st.watchIndex(code) >= 0 || st.holdingIndex(code) >= 0)
	}
	alertable := func(h ScreenHit) bool {
		return (h.Origin == HitScan || h.Origin == HitEOD) && h.Dir == DirUp && followed(h.Code)
	}
	byCode := map[string][]ScreenHit{}
	for _, h := range fresh {
		if alertable(h) {
			byCode[h.Code] = append(byCode[h.Code], h)
		}
	}
	now := ae.svc.now().In(WIB)
	l := ae.lang()
	for _, code := range sortedKeys(byCode) {
		hits := byCode[code]
		var labels []string
		var setups []map[string]any
		for _, h := range hits {
			labels = append(labels, setupLabel(l, h.Setup))
			setups = append(setups, map[string]any{"id": h.Setup, "dir": h.Dir, "date": h.Date, "origin": h.Origin})
		}
		last := hits[len(hits)-1]
		asOf := last.DataAsOf
		ae.emit(now, alertDraft{kind: AlertSetup, code: code, title: code + tr(l, ": setup fired", ": sinyal muncul"),
			text: strings.Join(labels, ", "), dataAsOf: &asOf, detail: map[string]any{"setups": setups, "score": last.Score}})
		// Q107: at least 2 bullish setups on one session date (scan/eod hits of the whole log).
		for _, date := range uniqueDates(hits) {
			ids := map[string]bool{}
			for _, h := range all {
				if h.Code == code && h.Date == date && alertable(h) {
					ids[h.Setup] = true
				}
			}
			if len(ids) >= autoMinSetups {
				ae.maybeAutoRun(now, code)
				break
			}
		}
	}
}

func uniqueDates(hits []ScreenHit) []string {
	var out []string
	for _, h := range hits {
		if !slices.Contains(out, h.Date) {
			out = append(out, h.Date)
		}
	}
	return out
}

// maybeAutoRun starts an automatic analysis of code on Sonnet when today's cap and the per-code rule allow it.
// The run counts when the job starts; a refusal (busy, Hermes down, not set up) is not counted.
func (ae *alertEngine) maybeAutoRun(now time.Time, code string) {
	an := ae.svc.analyzer
	if an.analyst == nil {
		return
	}
	today := dayString(now)
	ae.mu.Lock()
	ae.resetAutoLocked(today)
	used, done := ae.doc.AutoRuns.Used, slices.Contains(ae.doc.AutoRuns.Codes, code)
	lang := cmp.Or(ae.doc.Lang, defaultBriefLang)
	persist, logged := ae.persist, ae.noRuns
	ae.noRuns = ae.noRuns || !persist
	ae.mu.Unlock()
	if !persist {
		// The cap (Q87) must survive a restart: money is spent per run. Without a saved counter, no run starts.
		if !logged {
			ae.logRunsOff()
		}
		return
	}
	if done || used >= AutoRunCap {
		reason := "cap"
		if done {
			reason = "code_done"
		}
		ae.svc.log.Info("stocks: automatic analysis skipped", "code", code, "reason", reason)
		return
	}
	if res, ok := an.keptOne(code); ok && res.SessionDate == dayString(SessionDate(now)) {
		if t, err := time.Parse(time.RFC3339, res.AnalyzedAt); err == nil && now.Sub(t) < autoFreshVerdict {
			ae.svc.log.Info("stocks: automatic analysis skipped", "code", code, "reason", "fresh_verdict")
			return
		}
	}
	ctx, cancel := context.WithTimeout(context.Background(), autoStartTimeout)
	defer cancel()
	_, _, err := an.start(ctx, code, lang, runOptions{Trigger: TriggerAuto, Model: AutoModel, Done: func(res *AnalysisResult, errCode string) {
		ae.analysisDone(code, res, errCode)
	}})
	if err != nil {
		ae.svc.log.Info("stocks: automatic analysis refused", "code", code, "error", asAPIError(err).code)
		return
	}
	ae.mu.Lock()
	ae.resetAutoLocked(today)
	ae.doc.AutoRuns.Used++
	ae.doc.AutoRuns.Codes = append(ae.doc.AutoRuns.Codes, code)
	saveErr := ae.saveLocked()
	used = ae.doc.AutoRuns.Used
	if saveErr != nil { // the counter is not on disk: a restart would allow more runs, so stop them now
		ae.persist, ae.noRuns = false, true
	}
	ae.mu.Unlock()
	if saveErr != nil {
		ae.logRunsOff()
	}
	ae.svc.log.Info("stocks: automatic analysis started", "code", code, "used", used, "cap", AutoRunCap)
}

// logRunsOff logs (once per caller decision) why automatic analyses stopped.
func (ae *alertEngine) logRunsOff() {
	ae.svc.log.Warn("stocks: automatic analyses are off: the alerts state cannot be saved, so the daily cap could not survive a restart")
}

// resetAutoLocked starts a new day's counter at 00:00 WIB.
func (ae *alertEngine) resetAutoLocked(today string) {
	if ae.doc.AutoRuns.Date != today {
		ae.doc.AutoRuns = autoRuns{Date: today, Codes: []string{}}
	}
}

// analysisDone posts the analysis alert of an automatic run ("BBCA: Buy today, med"); a failed run posts none.
func (ae *alertEngine) analysisDone(code string, res *AnalysisResult, errCode string) {
	if res == nil {
		ae.svc.log.Info("stocks: automatic analysis failed", "code", code, "error", errCode)
		return
	}
	if !ae.on() {
		return
	}
	l := ae.lang()
	asOf := res.DataAsOf
	ae.emit(ae.svc.now(), alertDraft{kind: AlertAnalysis, code: code,
		title: fmt.Sprintf("%s: %s, %s", code, verdictLabel(l, res.Verdict), confidenceLabel(l, res.Confidence)),
		text:  tr(l, "Automatic analysis after a setup", "Analisis otomatis setelah sinyal"), dataAsOf: &asOf,
		detail: map[string]any{"verdict": res.Verdict, "confidence": res.Confidence, "trigger": res.Trigger, "model": res.Model,
			"analyzed_at": res.AnalyzedAt, "session_date": res.SessionDate}})
}

// --- news alerts (after each news poll) ---

// afterNews alerts on articles published 08:00-16:30 today and in the last hour with a strong match on a
// watched or held code; outside those hours news waits for the brief (Q108).
func (ae *alertEngine) afterNews(now time.Time) {
	now = now.In(WIB)
	if !ae.on() || !alertHours(now) {
		return
	}
	ae.proc.Lock()
	defer ae.proc.Unlock()
	day := at(now, clock{0, 0})
	from, to := day.Add(alertHoursFromMin*time.Minute), day.Add(alertHoursToMin*time.Minute)
	cands := ae.svc.candidates("")
	for _, a := range ae.svc.news.Snapshot() {
		p := a.Published.In(WIB)
		if p.Before(from) || !p.Before(to) || now.Sub(p) > newsAlertMaxAge {
			continue
		}
		for _, c := range cands {
			if MatchArticle(a.Title, a.Summary, []Candidate{c}).Strength != MatchStrong {
				continue
			}
			pub := *rfc(p)
			ae.emit(now, alertDraft{kind: AlertNews, code: c.Code, subject: a.ID, title: c.Code + " · " + a.Source,
				text: truncate(a.Title, maxAlertTitle), dataAsOf: &pub,
				detail: map[string]any{"article_id": a.ID, "source": a.Source, "link": a.Link, "published_at": pub}})
		}
	}
}

// --- jobs ---

// runNewsJob is the news job (every 10 min, 06:00-16:30 on trading days): poll the feeds, then the news alerts.
func (s *Service) runNewsJob(ctx context.Context, _ JobRun) error {
	s.news.Poll(ctx)
	if ctx.Err() != nil {
		return ctx.Err()
	}
	s.alerts.afterNews(s.now())
	return nil
}

// runAlertsJob drains the hits log every 5 min (08:00-16:30): it catches eod hits and anything a direct call
// after a scan missed. Its on/off is the switch of every alert and automatic run (Q20).
func (s *Service) runAlertsJob(context.Context, JobRun) error {
	s.alerts.afterHits()
	return nil
}

// --- texts ---

func tr(lang, en, id string) string {
	if lang == analysisLangEN {
		return en
	}
	return id
}

func setupLabel(l, id string) string {
	switch id {
	case SetupBreakout:
		return "Breakout"
	case SetupVolumeSpike:
		return tr(l, "Volume spike", "Lonjakan volume")
	case SetupMACross:
		return "MA20↑50"
	case SetupRSIRebound:
		return tr(l, "RSI rebound", "RSI memantul")
	case SetupGap:
		return tr(l, "Gap up", "Gap naik")
	}
	return id
}

func verdictLabel(l, v string) string {
	en := map[string]string{"buy_today": "Buy today", "wait": "Wait", "avoid": "Avoid", "add": "Add", "hold": "Hold", "sell": "Sell"}
	id := map[string]string{"buy_today": "Beli hari ini", "wait": "Tunggu", "avoid": "Hindari", "add": "Tambah", "hold": "Tahan", "sell": "Jual"}
	return cmp.Or(tr(l, en[v], id[v]), v)
}

func confidenceLabel(l, c string) string {
	id := map[string]string{"low": "rendah", "med": "sedang", "high": "tinggi"}
	return cmp.Or(tr(l, c, id[c]), c)
}

func mustJSON(v any) []byte {
	b, err := json.Marshal(v)
	if err != nil {
		panic(err) // only plain data is marshalled
	}
	return append(b, '\n')
}

func sortedKeys[V any](m map[string]V) []string {
	out := make([]string, 0, len(m))
	for k := range m {
		out = append(out, k)
	}
	slices.Sort(out)
	return out
}

// --- HTTP ---

// AutoRunsView is today's automatic analyses (GET /v1/stocks/jobs, /alerts, /alerts/status).
type AutoRunsView struct {
	Date  string   `json:"date"`
	Used  int      `json:"used"`
	Cap   int      `json:"cap"`
	Codes []string `json:"codes"`
}

func (ae *alertEngine) autoRunsView() AutoRunsView {
	today := dayString(ae.svc.now())
	ae.mu.Lock()
	defer ae.mu.Unlock()
	v := AutoRunsView{Date: today, Cap: AutoRunCap, Codes: []string{}}
	if ae.doc.AutoRuns.Date == today {
		v.Used, v.Codes = ae.doc.AutoRuns.Used, slices.Clone(ae.doc.AutoRuns.Codes)
	}
	return v
}

type alertsBody struct {
	Seq      int64        `json:"seq"`
	Items    []Alert      `json:"items"`
	AutoRuns AutoRunsView `json:"auto_runs"`
	Reset    bool         `json:"reset"`
}

// read answers after (nil = the newest 50): the items with a larger seq, oldest first; a cursor ahead of the
// ring (the file was lost) is a reset with the newest 50.
func (ae *alertEngine) read(after *int64) (alertsBody, <-chan struct{}) {
	ae.mu.Lock()
	defer ae.mu.Unlock()
	b := alertsBody{Seq: ae.doc.Seq, Items: []Alert{}}
	items := ae.doc.Items
	switch {
	case after == nil || *after > ae.doc.Seq:
		b.Reset = after != nil
		b.Items = append(b.Items, items[max(0, len(items)-alertsListN):]...)
	default:
		for _, a := range items {
			if a.Seq > *after {
				b.Items = append(b.Items, a)
			}
		}
	}
	return b, ae.wake
}

// getAlerts is the long-poll: with after and wait, it holds the request up to wait seconds until an item with
// seq > after exists (or the server stops).
func (s *Service) getAlerts(r *http.Request) (int, any, error) {
	q := r.URL.Query()
	var after *int64
	if raw := q.Get("after"); raw != "" {
		n, err := strconv.ParseInt(raw, 10, 64)
		if err != nil || n < 0 {
			return 0, nil, errBadRequest
		}
		after = &n
	}
	wait := 0
	if raw := q.Get("wait"); raw != "" {
		n, err := strconv.Atoi(raw)
		if err != nil || n < 0 || n > maxAlertWait {
			return 0, nil, errBadRequest
		}
		wait = n
	}
	if raw := q.Get("lang"); raw != "" {
		if raw != analysisLangID && raw != analysisLangEN {
			return 0, nil, errBadRequest
		}
		s.alerts.setLang(raw)
	}
	ae := s.alerts
	b, wake := ae.read(after)
	if after != nil && !b.Reset && len(b.Items) == 0 && wait > 0 {
		t := time.NewTimer(time.Duration(wait) * time.Second)
		defer t.Stop()
		select {
		case <-wake:
			b, _ = ae.read(after)
		case <-t.C:
		case <-r.Context().Done():
		case <-ae.done:
		}
	}
	b.AutoRuns = ae.autoRunsView()
	return http.StatusOK, b, nil
}

type alertsStatusBody struct {
	Seq      int64        `json:"seq"`
	AlertsOn bool         `json:"alerts_on"`
	Lang     string       `json:"lang"`
	AutoRuns AutoRunsView `json:"auto_runs"`
}

func (s *Service) getAlertsStatus(*http.Request) (int, any, error) {
	ae := s.alerts
	ae.mu.Lock()
	seq := ae.doc.Seq
	ae.mu.Unlock()
	return http.StatusOK, alertsStatusBody{Seq: seq, AlertsOn: ae.on(), Lang: ae.lang(), AutoRuns: ae.autoRunsView()}, nil
}
