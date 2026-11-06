package stocks

import (
	"encoding/json"
	"errors"
	"io/fs"
	"maps"
	"sync"
	"time"

	"github.com/justcallmesu/aristotle/server/internal/statefile"
)

// The failure dates of track.score (spec P16b, protocol §20.5) live in their own file, stocks-track-failures.json, not
// in stocks-verdicts.json: that file is read strictly as version 1, so a field added there would make a rolled-back
// binary drop the whole history (the same reason stocks-levels.json is apart). An unusable file is logged and read as
// "no failures" and never overwritten; a newer-version file is read but never written.

const (
	trackFailuresFile    = "stocks-track-failures.json"
	trackFailuresVersion = 1
)

// failRun is one code's failures since its last success (P16b): the WIB date of the first of them (Since), of the
// first of the current streak of consecutive trading days (First) and of the latest (Last).
type failRun struct {
	Since string `json:"since"`
	First string `json:"first"`
	Last  string `json:"last"`
}

type trackFailuresDoc struct {
	Version  int                `json:"version"`
	Failures map[string]failRun `json:"failures"`
}

// priorFailure says how a code's failures before today's run look.
type priorFailure struct {
	streak bool // today's failure continues a streak that began on an earlier date (P16a)
	ever   bool // the code also failed on an earlier date since its last success (P16b's hard limit)
}

// failureLog is the saved failure dates of the failing codes.
type failureLog struct {
	dir string
	log logger

	mu      sync.Mutex
	runs    map[string]failRun
	persist bool // false: the file was newer or unusable; it is left alone
	dirty   bool // runs changed since the last save
}

// loadFailureLog reads the file. legacy holds the failure dates an earlier build wrote into stocks-verdicts.json: when
// there is no failures file yet they are moved here (the verdicts file drops them with its next write).
func loadFailureLog(dir string, log logger, legacy map[string]failRun) *failureLog {
	f := &failureLog{dir: dir, log: log, runs: map[string]failRun{}, persist: true}
	data, err := statefile.Read(dir, trackFailuresFile)
	if errors.Is(err, fs.ErrNotExist) {
		f.runs = validFailures(legacy)
		f.dirty = len(f.runs) > 0
		f.save()
		return f
	}
	var doc trackFailuresDoc
	if err == nil {
		err = decodeVersioned(trackFailuresFile, data, trackFailuresVersion, &doc)
	}
	var newer *newerFileError
	switch {
	case err == nil:
	case errors.As(err, &newer):
		f.persist = false
		log.Warn("stocks: track failures file was written by a newer server; it is read but never written",
			"file", trackFailuresFile, "version", newer.version, "supported", trackFailuresVersion)
	default:
		f.persist = false
		log.Error("stocks: track failures file unusable; it is left alone and read as no failures", "file", trackFailuresFile, "err", err)
		return f
	}
	f.runs = validFailures(doc.Failures)
	return f
}

// validFailures keeps the runs of valid codes with readable, ordered dates (the rest is dropped: a lost run only
// delays a give-up). A run without since (written before it existed) takes first.
func validFailures(in map[string]failRun) map[string]failRun {
	out := map[string]failRun{}
	for code, r := range in {
		if r.Since == "" {
			r.Since = r.First
		}
		_, okSince := parseSessionDay(r.Since)
		_, okFirst := parseSessionDay(r.First)
		_, okLast := parseSessionDay(r.Last)
		if codeRe.MatchString(code) && okSince && okFirst && okLast && r.Since <= r.First && r.First <= r.Last {
			out[code] = r
		}
	}
	return out
}

// note records that code failed in a run at now. A streak ends when its last failure is older than the previous
// trading day (P16b: failures weeks apart are not a streak); only a success (clear) or a give-up ends since.
func (f *failureLog) note(code string, now time.Time) priorFailure {
	day := now.In(WIB).Format(time.DateOnly)
	f.mu.Lock()
	defer f.mu.Unlock()
	r, ok := f.runs[code]
	switch {
	case !ok:
		r = failRun{Since: day, First: day, Last: day}
	case r.Last < previousTradingDay(now):
		r.First, r.Last = day, day
	case day > r.Last:
		r.Last = day
	}
	if f.runs[code] != r {
		f.runs[code], f.dirty = r, true
	}
	return priorFailure{streak: r.First < day, ever: r.Since < day}
}

// clear forgets code: it scored again or its records were given up.
func (f *failureLog) clear(code string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if _, ok := f.runs[code]; ok {
		delete(f.runs, code)
		f.dirty = true
	}
}

// snapshot is a copy of the runs.
func (f *failureLog) snapshot() map[string]failRun {
	f.mu.Lock()
	defer f.mu.Unlock()
	return maps.Clone(f.runs)
}

// flush writes the file when something changed.
func (f *failureLog) flush() {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.save()
}

// save writes the runs when they changed and the file may be written; the caller holds mu (or owns f).
func (f *failureLog) save() {
	if !f.persist || !f.dirty {
		return
	}
	data, err := json.Marshal(trackFailuresDoc{Version: trackFailuresVersion, Failures: f.runs})
	if err == nil {
		err = statefile.WriteAtomic(f.dir, trackFailuresFile, append(data, '\n'))
	}
	if err != nil {
		f.log.Error("stocks: saving the track failures failed", "err", err)
		return
	}
	f.dirty = false
}
