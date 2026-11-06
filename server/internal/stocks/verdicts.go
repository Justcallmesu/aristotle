package stocks

import (
	"cmp"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"slices"
	"sync"

	"github.com/justcallmesu/aristotle/server/internal/statefile"
)

// The verdict history (spec P10, protocol §20.5): stocks-verdicts.json keeps one compact record per kept verdict,
// appended by the analyzer's keep (stocks-analyses.json keeps only the last per code, Q99). Records are never
// rewritten except to attach their score (P13); the track.score job writes the scores. Seeded once, at the first
// start, from the results stocks-analyses.json holds (not while that file is unusable: the seed waits). A newer-version
// file is read but never written; any other unusable file is left alone and the history lives in memory (both logged).

const (
	verdictsFile         = "stocks-verdicts.json"
	verdictsVersion      = 1
	maxVerdictsFileBytes = 8 << 20 // ~1.1 MB a year at 10 calls a day (TR0 §6)
)

// VerdictRecord is one kept verdict in the history: the fields scoring needs, no reasons, summary, news or
// indicators. Score is null until track.score scored the record's session.
type VerdictRecord struct {
	ID          string   `json:"id"`
	Code        string   `json:"code"`
	Held        bool     `json:"held"`
	AnalyzedAt  string   `json:"analyzed_at"`
	SessionDate string   `json:"session_date"`
	MarketState string   `json:"market_state"`
	Model       string   `json:"model"`
	Trigger     string   `json:"trigger"`
	Verdict     string   `json:"verdict"`
	Confidence  string   `json:"confidence"`
	EntryLow    *int64   `json:"entry_low"`
	EntryHigh   *int64   `json:"entry_high"`
	Target      *int64   `json:"target"`
	Stop        *int64   `json:"stop"`
	Price       int64    `json:"price"`
	Flags       []string `json:"flags"`
	Score       *Score   `json:"score"`
}

// verdictsDoc is the file, exactly version 1 as c74df38 wrote it (a rolled-back binary decodes it strictly). Failures
// is only read: the TR1d build wrote the failure dates here, they now live in stocks-track-failures.json; a leftover
// is moved there at load and never written again.
type verdictsDoc struct {
	Version  int                `json:"version"`
	Records  []VerdictRecord    `json:"records"`
	Failures map[string]failRun `json:"failures,omitempty"`
}

// verdictHistory is the in-memory history. Records are appended; a score is attached by replacing the record's
// Score pointer (never changing a Score in place), so snapshots stay valid without a deep copy.
type verdictHistory struct {
	dir string
	log logger

	mu      sync.Mutex
	recs    []VerdictRecord // oldest first
	seen    map[string]bool // code|analyzed_at
	persist bool            // false: the file was newer or unusable; it is left alone
	fails   *failureLog     // the failure dates of track.score (their own file)
}

// loadVerdictHistory reads stocks-verdicts.json; when there is none yet it seeds the history from seed (the
// results kept in stocks-analyses.json) and writes it. seedOK false (the analyses file is unusable): no file is
// created, so a later start with the fixed file still seeds; new verdicts stay in memory meanwhile. Records that
// can never be scored are kept as they are, logged once here, and skipped by track.score.
func loadVerdictHistory(dir string, log logger, seed []AnalysisResult, seedOK bool) *verdictHistory {
	h := &verdictHistory{dir: dir, log: log, seen: map[string]bool{}, persist: true}
	data, err := statefile.Read(dir, verdictsFile)
	if errors.Is(err, fs.ErrNotExist) {
		if !seedOK {
			h.persist = false
			h.fails = loadFailureLog(dir, log, nil)
			log.Warn("stocks: verdict history not created while the analyses file is unusable (the seed waits); new verdicts stay in memory",
				"file", verdictsFile)
			return h
		}
		slices.SortStableFunc(seed, func(a, b AnalysisResult) int { return cmp.Compare(a.AnalyzedAt, b.AnalyzedAt) })
		n := 0
		for _, r := range seed {
			if h.appendLocked(r) {
				n++
			}
		}
		h.saveLocked()
		h.fails = loadFailureLog(dir, log, nil)
		log.Info("stocks: verdict history created", "file", verdictsFile, "seeded", n)
		return h
	}
	var recs []VerdictRecord
	var legacy map[string]failRun
	if err == nil {
		recs, legacy, err = parseVerdicts(data)
	}
	if err != nil {
		legacy = nil // only a version-1 file's leftover counts
	}
	var newer *newerFileError
	switch {
	case errors.As(err, &newer):
		h.persist = false
		log.Warn("stocks: verdict history was written by a newer server; it is read but never written, new verdicts stay in memory",
			"file", verdictsFile, "version", newer.version, "supported", verdictsVersion)
	case err != nil:
		h.persist = false
		log.Error("stocks: verdict history unusable; it is left alone and new verdicts are kept in memory", "file", verdictsFile, "err", err)
		recs = nil
	}
	h.fails = loadFailureLog(dir, log, legacy)
	for _, r := range recs {
		if !scorable(r) {
			log.Warn("stocks: verdict record cannot be scored; it is skipped", "code", r.Code)
		}
		h.recs = append(h.recs, r)
		h.seen[r.Code+"|"+r.AnalyzedAt] = true
	}
	return h
}

// parseVerdicts reads the file: version 1 strictly; a newer version leniently (with a *newerFileError). Either way
// every record needs an id and a code (else the file is unusable); scorability is checked per record by the caller.
func parseVerdicts(data []byte) ([]VerdictRecord, map[string]failRun, error) {
	if len(data) > maxVerdictsFileBytes {
		return nil, nil, fmt.Errorf("%s: %d bytes, over the cap", verdictsFile, len(data))
	}
	var doc verdictsDoc
	err := decodeVersioned(verdictsFile, data, verdictsVersion, &doc)
	var newer *newerFileError
	if err != nil && !errors.As(err, &newer) {
		return nil, nil, err
	}
	for _, r := range doc.Records {
		if r.ID == "" || !codeRe.MatchString(r.Code) {
			return nil, nil, fmt.Errorf("%s: bad record", verdictsFile)
		}
	}
	if err != nil {
		return doc.Records, nil, err
	}
	return doc.Records, doc.Failures, nil
}

// add appends a kept result (once per code and analyzed_at) and saves.
func (h *verdictHistory) add(res AnalysisResult) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.appendLocked(res) {
		h.saveLocked()
	}
}

// appendLocked appends res unless it is already there or can never be scored (a verdict without the levels its type
// needs or an unreadable time: the analyzer's checks never keep one, so only a damaged seed has it). The caller
// holds mu.
func (h *verdictHistory) appendLocked(res AnalysisResult) bool {
	key := res.Code + "|" + res.AnalyzedAt
	if h.seen[key] {
		return false
	}
	flags := slices.Clone(res.Flags)
	if flags == nil {
		flags = []string{}
	}
	rec := VerdictRecord{ID: "vr_" + randomHex(6), Code: res.Code, Held: res.Held, AnalyzedAt: res.AnalyzedAt,
		SessionDate: res.SessionDate, MarketState: res.MarketState, Model: res.Model, Trigger: res.Trigger, Verdict: res.Verdict,
		Confidence: res.Confidence, EntryLow: res.EntryLow, EntryHigh: res.EntryHigh, Target: res.Target, Stop: res.Stop,
		Price: res.Price, Flags: flags}
	if !scorable(rec) {
		h.log.Warn("stocks: verdict not added to the history (no scorable verdict)", "code", res.Code)
		return false
	}
	h.seen[key] = true
	h.recs = append(h.recs, rec)
	return true
}

// snapshot is a copy of the records, oldest first.
func (h *verdictHistory) snapshot() []VerdictRecord {
	h.mu.Lock()
	defer h.mu.Unlock()
	return slices.Clone(h.recs)
}

// apply attaches scores by record id: a record without a score takes the whole score; a scored record only takes
// a missing info_3d (P13: a score is never recomputed). It saves once and reports the save error.
func (h *verdictHistory) apply(updates map[string]Score) error {
	h.mu.Lock()
	defer h.mu.Unlock()
	changed := false
	for i := range h.recs {
		u, ok := updates[h.recs[i].ID]
		switch {
		case !ok:
		case h.recs[i].Score == nil:
			h.recs[i].Score, changed = &u, true
		case h.recs[i].Score.Info3dPct == nil && u.Info3dPct != nil:
			sc := *h.recs[i].Score
			sc.Info3dPct = u.Info3dPct
			h.recs[i].Score, changed = &sc, true
		}
	}
	if !changed {
		return nil
	}
	return h.saveLocked()
}

// saveLocked writes the file (compact JSON); the caller holds mu. A history kept in memory is not written.
func (h *verdictHistory) saveLocked() error {
	if !h.persist {
		return nil
	}
	recs := h.recs
	if recs == nil {
		recs = []VerdictRecord{}
	}
	data, err := json.Marshal(verdictsDoc{Version: verdictsVersion, Records: recs})
	if err == nil {
		err = statefile.WriteAtomic(h.dir, verdictsFile, append(data, '\n'))
	}
	if err != nil {
		h.log.Error("stocks: saving the verdict history failed", "err", err)
	}
	return err
}
