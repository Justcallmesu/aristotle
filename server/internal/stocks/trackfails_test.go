package stocks

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// --- stocks-track-failures.json (P16b amended) ---

func readFailuresFile(t *testing.T, dir string) map[string]failRun {
	t.Helper()
	b, err := os.ReadFile(filepath.Join(dir, trackFailuresFile))
	if err != nil {
		t.Fatal(err)
	}
	var doc trackFailuresDoc
	if err := json.Unmarshal(b, &doc); err != nil || doc.Version != 1 {
		t.Fatalf("failures file = %s (%v)", b, err)
	}
	return doc.Failures
}

func writeRaw(t *testing.T, dir, name, content string) {
	t.Helper()
	if err := os.WriteFile(filepath.Join(dir, name), []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
}

func failFile(t *testing.T, h *trackHarness) string {
	t.Helper()
	b, err := os.ReadFile(filepath.Join(h.dir, trackFailuresFile))
	if err != nil {
		return ""
	}
	return string(b)
}

// c74df38Verdicts decodes data the way c74df38 did: version 1 only, unknown fields refused, every record with an id
// and a code (versioned.go and parseVerdicts of that commit).
func c74df38Verdicts(data []byte) ([]VerdictRecord, error) {
	var doc struct {
		Version int             `json:"version"`
		Records []VerdictRecord `json:"records"`
	}
	dec := json.NewDecoder(bytes.NewReader(data))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&doc); err != nil {
		return nil, err
	}
	if doc.Version != 1 {
		return nil, os.ErrInvalid
	}
	for _, r := range doc.Records {
		if r.ID == "" || !codeRe.MatchString(r.Code) {
			return nil, os.ErrInvalid
		}
	}
	return doc.Records, nil
}

// TestVerdictsFileStaysC74df38Shape: whatever the failure log holds (failing runs, a give-up), stocks-verdicts.json
// decodes with the c74df38 strict decoder, so a rollback keeps the history.
func TestVerdictsFileStaysC74df38Shape(t *testing.T) {
	h := newTrackHarness(t, "2026-10-13T08:00:00+07:00", "")
	h.keepOld("FAIL", "GOOD")
	h.prov.periodErrs = map[string]error{"FAIL|5m": err403}
	h.prov.periods = map[string][]Bar{"GOOD|5m": sessionBars("2026-10-13"), "GOOD|1d": {dly("2026-10-13", 5110, 4920, 5080)}}
	for i, at := range []string{"2026-11-05T17:00:10+07:00", "2026-11-06T17:00:10+07:00"} {
		h.runAt(at)
		raw, _ := readVerdictsFile(t, h.dir)
		recs, err := c74df38Verdicts([]byte(raw))
		if err != nil || len(recs) != 2 || strings.Contains(raw, "failures") {
			t.Fatalf("run %d: the c74df38 decoder: %d records, %v: %s", i, len(recs), err, raw)
		}
	}
	if s := h.records()["FAIL@2026-10-13"].Score; s == nil || s.Outcome != OutcomeUnscorable {
		t.Fatalf("not given up: %+v", s)
	}
}

// TestFailuresLeftInVerdictsFileAreMoved: a failures field the TR1d build left in stocks-verdicts.json is read once into
// the new file and dropped from the verdicts file by its next write.
func TestFailuresLeftInVerdictsFileAreMoved(t *testing.T) {
	h := newTrackHarness(t, "2026-10-13T08:00:00+07:00", "")
	h.keepOld("FAIL")
	h.svc.Close()
	raw, _ := readVerdictsFile(t, h.dir)
	legacy := strings.TrimSuffix(strings.TrimSpace(raw), "}") + `,"failures":{"FAIL":{"first":"2026-11-05","last":"2026-11-05"}}}`
	writeRaw(t, h.dir, verdictsFile, legacy)
	os.Remove(filepath.Join(h.dir, trackFailuresFile))
	h.open()
	if f := readFailuresFile(t, h.dir)["FAIL"]; f.First != "2026-11-05" || f.Last != "2026-11-05" || f.Since != "2026-11-05" {
		t.Fatalf("moved = %+v", f)
	}
	if b, _ := os.ReadFile(filepath.Join(h.dir, verdictsFile)); string(b) != legacy {
		t.Fatalf("verdicts file rewritten at load: %s", b)
	}
	h.prov.periodErrs = map[string]error{"FAIL|5m": err403}
	h.runAt("2026-11-06T17:00:10+07:00") // the moved failure makes this the second failing day
	if s := h.records()["FAIL@2026-10-13"].Score; s == nil || s.Outcome != OutcomeUnscorable {
		t.Fatalf("legacy failure not honoured: %+v", s)
	}
	h.svc.analyzer.keep("TLKM", buyResult("TLKM", "2026-11-06T18:00:00+07:00", "2026-11-09"))
	if raw, _ := readVerdictsFile(t, h.dir); strings.Contains(raw, "failures") {
		t.Fatalf("leftover not dropped: %s", raw)
	}
}

// TestUnusableFailuresFileIsLeftAlone: a damaged failures file is logged and read as "no failures" (one failing run
// gives up nothing) and never overwritten.
func TestUnusableFailuresFileIsLeftAlone(t *testing.T) {
	dir := t.TempDir()
	bad := `{"version":1,"failures":[}`
	writeRaw(t, dir, trackFailuresFile, bad)
	h := newTrackHarness(t, "2026-10-13T08:00:00+07:00", dir)
	h.keepOld("FAIL")
	h.prov.periodErrs = map[string]error{"FAIL|5m": err403}
	h.runAt("2026-11-05T17:00:10+07:00")
	h.runAt("2026-11-06T17:00:10+07:00") // memory still counts: the second day gives up
	if b := failFile(t, h); b != bad {
		t.Fatalf("unusable file overwritten: %s", b)
	}
	if !strings.Contains(h.logs.String(), "track failures file unusable") {
		t.Fatalf("logs = %s", h.logs)
	}
}

// TestNewerFailuresFileReadNeverWritten: a failures file of a newer version is read (its dates count) but never
// written.
func TestNewerFailuresFileReadNeverWritten(t *testing.T) {
	dir := t.TempDir()
	newer := `{"version":2,"failures":{"FAIL":{"since":"2026-11-05","first":"2026-11-05","last":"2026-11-05","x":1}},"more":{}}`
	writeRaw(t, dir, trackFailuresFile, newer)
	h := newTrackHarness(t, "2026-10-13T08:00:00+07:00", dir)
	h.keepOld("FAIL")
	h.prov.periodErrs = map[string]error{"FAIL|5m": err403}
	h.runAt("2026-11-06T17:00:10+07:00")
	if s := h.records()["FAIL@2026-10-13"].Score; s == nil || s.Outcome != OutcomeUnscorable {
		t.Fatalf("newer file not read: %+v", s)
	}
	if b := failFile(t, h); b != newer {
		t.Fatalf("newer file written: %s", b)
	}
	if !strings.Contains(h.logs.String(), "track failures file was written by a newer server") {
		t.Fatalf("logs = %s", h.logs)
	}
}

// TestSuccessRemovesFailureEntry: a code that scores again leaves no entry in the file.
func TestSuccessRemovesFailureEntry(t *testing.T) {
	h := newTrackHarness(t, "2026-11-02T08:00:00+07:00", "")
	h.svc.analyzer.keep("ZZZZ", buyResult("ZZZZ", "2026-11-02T08:30:00+07:00", "2026-11-02"))
	h.prov.periodErrs = map[string]error{"ZZZZ|5m": err403}
	h.runAt("2026-11-02T17:00:10+07:00")
	if _, ok := readFailuresFile(t, h.dir)["ZZZZ"]; !ok {
		t.Fatal("failure not recorded")
	}
	h.prov.periodErrs = nil
	h.prov.periods = map[string][]Bar{"ZZZZ|5m": sessionBars("2026-11-02"), "ZZZZ|1d": {dly("2026-11-02", 5110, 4920, 5080)}}
	h.runAt("2026-11-03T17:00:10+07:00")
	if got := readFailuresFile(t, h.dir); len(got) != 0 {
		t.Fatalf("entry left after a success: %v", got)
	}
}

// TestGiveUpRemovesFailureEntry: when a code's records are all given up its entry goes; while one record waits it stays.
func TestGiveUpRemovesFailureEntry(t *testing.T) {
	h := newTrackHarness(t, "2026-10-13T08:00:00+07:00", "")
	h.keepOld("FAIL")
	h.svc.analyzer.keep("WAIT", buyResult("WAIT", "2026-10-13T08:30:00+07:00", "2026-10-13"))
	h.svc.analyzer.keep("WAIT", buyResult("WAIT", "2026-11-05T08:30:00+07:00", thuDay))
	h.prov.periodErrs = map[string]error{"FAIL|5m": err403, "WAIT|5m": err403}
	h.runAt("2026-11-05T17:00:10+07:00")
	h.runAt("2026-11-06T17:00:10+07:00")
	got := readFailuresFile(t, h.dir)
	if _, ok := got["FAIL"]; ok || len(got) != 1 {
		t.Fatalf("entries = %v (FAIL given up: gone; WAIT keeps a waiting record)", got)
	}
	if _, ok := got["WAIT"]; !ok {
		t.Fatalf("WAIT entry lost: %v", got)
	}
}

// --- boundaries and mutants ---

// TestFailureStreakOfThreeDaysCrossingTheLimit: the record turns 10 trading days old on the third failing day: the
// streak's last date must advance each day, or the third day restarts the streak and gives up nothing.
func TestFailureStreakOfThreeDaysCrossingTheLimit(t *testing.T) {
	h := newTrackHarness(t, "2026-10-13T08:00:00+07:00", "")
	h.keepOld("FAIL")
	h.prov.periodErrs = map[string]error{"FAIL|5m": err403}
	for i, at := range []string{"2026-10-23T17:00:10+07:00", "2026-10-26T17:00:10+07:00", "2026-10-27T17:00:10+07:00"} {
		h.reopen()
		h.runAt(at)
		s := h.records()["FAIL@2026-10-13"].Score
		if (i == 2) != (s != nil) {
			t.Fatalf("%s: score = %+v", at, s)
		}
	}
}

// TestHardLimitBoundary: with a failure on an earlier day (not the same streak), the record is given up at exactly 30
// trading days after its session, not at 29 and not later than 30.
func TestHardLimitBoundary(t *testing.T) {
	for _, c := range []struct {
		at   string // 11-05 is the first failing day; session 2026-10-13
		gone bool
	}{{"2026-11-23T17:00:10+07:00", false}, // 29 trading days
		{"2026-11-24T17:00:10+07:00", true},   // 30
		{"2026-11-25T17:00:10+07:00", true}} { // 31
		h := newTrackHarness(t, "2026-10-13T08:00:00+07:00", "")
		h.keepOld("FAIL")
		h.prov.periodErrs = map[string]error{"FAIL|5m": err403}
		h.runAt("2026-11-05T17:00:10+07:00")
		h.runAt(c.at)
		if s := h.records()["FAIL@2026-10-13"].Score; (s != nil) != c.gone {
			t.Fatalf("%s: score = %+v, want given up %v", c.at, s, c.gone)
		}
	}
}

// TestHardLimitNeedsAnEarlierFailure (the TR1d review's probe): seven weeks away, then ONE 403 on the first run gives
// up nothing, though the record is past 30 trading days.
func TestHardLimitNeedsAnEarlierFailure(t *testing.T) {
	h := newTrackHarness(t, "2026-10-13T08:00:00+07:00", "")
	h.keepOld("FAIL")
	h.prov.periodErrs = map[string]error{"FAIL|5m": err403}
	h.runAt("2026-12-02T17:00:10+07:00") // 32 trading days
	if s := h.records()["FAIL@2026-10-13"].Score; s != nil {
		t.Fatalf("one failure gave up %+v", s)
	}
	h.runAt("2026-12-03T17:00:10+07:00")
	if s := h.records()["FAIL@2026-10-13"].Score; s == nil || s.Outcome != OutcomeUnscorable {
		t.Fatalf("second failing day = %+v", s)
	}
}
