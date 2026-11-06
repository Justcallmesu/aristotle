package stocks

import (
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"maps"

	"github.com/justcallmesu/aristotle/server/internal/statefile"
)

// Hand-set stop/target per holding (spec Q104) for the price alerts. They live in their own file,
// stocks-levels.json, not in stocks.json: that file is read strictly as version 1, so a new field there would
// switch off a rolled-back binary's watchlist and holdings (TS4 §0). stocks.json stays byte-for-byte what it was.
// Writes are ordered so a crash leaves at worst a level for a code that is no longer held, which is ignored.

const (
	levelsFile    = "stocks-levels.json"
	levelsVersion = 1
)

// HoldingLevels are a holding's optional stop and target (integer IDR on the tick grid). SetAt is when they
// last changed: a level set before today's session counts the day's high/low, one set during it only the
// prices seen after.
type HoldingLevels struct {
	Stop   *int64 `json:"stop,omitempty"`
	Target *int64 `json:"target,omitempty"`
	SetAt  string `json:"set_at"`
}

type levelsDoc struct {
	Version int                      `json:"version"`
	Levels  map[string]HoldingLevels `json:"levels"`
}

func loadLevels(dir string) (map[string]HoldingLevels, error) {
	data, err := statefile.Read(dir, levelsFile)
	if errors.Is(err, fs.ErrNotExist) {
		return map[string]HoldingLevels{}, nil
	}
	if err != nil {
		return map[string]HoldingLevels{}, err
	}
	var doc levelsDoc
	if err := decodeStrict(data, &doc); err != nil {
		return map[string]HoldingLevels{}, fmt.Errorf("%s: %w", levelsFile, err)
	}
	if doc.Version != levelsVersion {
		return map[string]HoldingLevels{}, fmt.Errorf("%s: version %d, want %d", levelsFile, doc.Version, levelsVersion)
	}
	out := map[string]HoldingLevels{}
	for c, l := range doc.Levels {
		if codeRe.MatchString(c) && validLevel(l.Stop) && validLevel(l.Target) && (l.Stop != nil || l.Target != nil) {
			out[c] = l
		}
	}
	return out, nil
}

func saveLevels(dir string, lv map[string]HoldingLevels) error {
	data, err := json.MarshalIndent(levelsDoc{Version: levelsVersion, Levels: lv}, "", "  ")
	if err != nil {
		return err
	}
	return statefile.WriteAtomic(dir, levelsFile, append(data, '\n'))
}

// validLevel: nil, or a positive price on the IDX tick grid (protocol §20 tick bands) within the avg cap.
func validLevel(p *int64) bool {
	return p == nil || (*p > 0 && *p <= MaxAvgPrice && *p%TickSize(*p) == 0)
}

// checkLevels validates a holding's levels against its average price: each on the tick grid, the stop below
// the average and below the target.
func checkLevels(stop, target *int64, avg float64) error {
	if !validLevel(stop) || !validLevel(target) {
		return errBadRequest
	}
	if stop != nil && (float64(*stop) >= avg || (target != nil && *stop >= *target)) {
		return errBadRequest
	}
	return nil
}

// holdingLevels are the levels of the codes held now (a copy).
func (s *Service) holdingLevels() map[string]HoldingLevels {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := map[string]HoldingLevels{}
	if s.stateErr != nil {
		return out
	}
	for c, l := range s.levels {
		if s.st.holdingIndex(c) >= 0 {
			out[c] = l
		}
	}
	return out
}

// putHoldingLevels adds or replaces h and sets its levels (nil: none). The levels file is written first: if
// the state save then fails the level belongs to a holding that is not stored and is ignored.
func (s *Service) putHoldingLevels(h Holding, stop, target *int64) (State, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.stateErr != nil {
		return State{}, errState
	}
	n, err := s.st.putHolding(h)
	if err != nil {
		return State{}, err
	}
	old, had := s.levels[h.Code]
	want := HoldingLevels{Stop: stop, Target: target, SetAt: *rfc(s.now())}
	if had && eqPtr(old.Stop, stop) && eqPtr(old.Target, target) {
		want.SetAt = old.SetAt
	}
	lv := maps.Clone(s.levels)
	if stop == nil && target == nil {
		delete(lv, h.Code)
	} else {
		lv[h.Code] = want
	}
	changed := had != (stop != nil || target != nil) || (had && want.SetAt != old.SetAt)
	if changed {
		if s.levelsErr != nil {
			return State{}, errState // the levels file is unusable: never overwritten
		}
		if err := saveLevels(s.dir, lv); err != nil {
			s.log.Error("stocks: saving holding levels failed", "err", err)
			return State{}, errState
		}
		s.levels = lv
	}
	if err := saveState(s.dir, n); err != nil {
		s.log.Error("stocks: saving state failed", "err", err)
		return State{}, errState
	}
	s.st = n
	return n.clone(), nil
}

// dropLevels forgets the levels of a code no longer held (after the state saved; a failure is logged only:
// the stale entry is ignored while the code is not held, and a new holding replaces it).
func (s *Service) dropLevels(code string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.levels[code]; !ok || s.levelsErr != nil {
		return
	}
	lv := maps.Clone(s.levels)
	delete(lv, code)
	if err := saveLevels(s.dir, lv); err != nil {
		s.log.Error("stocks: saving holding levels failed", "err", err)
		return
	}
	s.levels = lv
}

func eqPtr(a, b *int64) bool { return (a == nil) == (b == nil) && (a == nil || *a == *b) }
