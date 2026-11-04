package stocks

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"math"
	"regexp"
	"slices"

	"github.com/justcallmesu/aristotle/server/internal/statefile"
)

const (
	stateFile    = "stocks.json"
	stateVersion = 1

	// MaxWatchlist and MaxHoldings cap each list (409 limit_reached).
	MaxWatchlist = 50
	MaxHoldings  = 50
	// MaxLots caps one holding (1 lot = LotSize shares).
	MaxLots = 10_000_000
	// MaxAvgPrice caps an average price in IDR (no IDX share is near it).
	MaxAvgPrice = 10_000_000
	// MaxFeePct caps each broker fee (percent).
	MaxFeePct = 5.0

	defaultBuyFeePct  = 0.15
	defaultSellFeePct = 0.25
)

var codeRe = regexp.MustCompile(`^[A-Z]{4}$`)

// Entry is one watchlist row.
type Entry struct {
	Code string `json:"code"`
	Name string `json:"name"`
}

// Holding is one hand-entered position. AvgPrice is IDR per share (2 decimals at most are kept).
type Holding struct {
	Code     string  `json:"code"`
	Name     string  `json:"name"`
	Lots     int64   `json:"lots"`
	AvgPrice float64 `json:"avg_price"`
}

// Settings are the broker fees in percent (0.15 = 0.15 %).
type Settings struct {
	BuyFeePct  float64 `json:"buy_fee_pct"`
	SellFeePct float64 `json:"sell_fee_pct"`
}

// State is stocks.json.
type State struct {
	Version   int       `json:"version"`
	Watchlist []Entry   `json:"watchlist"`
	Holdings  []Holding `json:"holdings"`
	Settings  Settings  `json:"settings"`
}

// DefaultState is the state before the first write.
func DefaultState() State {
	return State{Version: stateVersion, Watchlist: []Entry{}, Holdings: []Holding{},
		Settings: Settings{BuyFeePct: defaultBuyFeePct, SellFeePct: defaultSellFeePct}}
}

func (s State) clone() State {
	c := s
	c.Watchlist = slices.Clone(s.Watchlist)
	c.Holdings = slices.Clone(s.Holdings)
	if c.Watchlist == nil {
		c.Watchlist = []Entry{}
	}
	if c.Holdings == nil {
		c.Holdings = []Holding{}
	}
	return c
}

// loadState reads stocks.json; a missing file is the default state. A file that is not valid
// version-1 state is an error and is never overwritten.
func loadState(dir string) (State, error) {
	data, err := statefile.Read(dir, stateFile)
	if errors.Is(err, fs.ErrNotExist) {
		return DefaultState(), nil
	}
	if err != nil {
		return State{}, err
	}
	var st State
	dec := json.NewDecoder(bytes.NewReader(data))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&st); err != nil {
		return State{}, fmt.Errorf("%s: %w", stateFile, err)
	}
	if st.Version != stateVersion {
		return State{}, fmt.Errorf("%s: version %d, want %d", stateFile, st.Version, stateVersion)
	}
	if err := st.validate(); err != nil {
		return State{}, fmt.Errorf("%s: %w", stateFile, err)
	}
	return st.clone(), nil
}

func saveState(dir string, st State) error {
	data, err := json.MarshalIndent(st, "", "  ")
	if err != nil {
		return err
	}
	return statefile.WriteAtomic(dir, stateFile, append(data, '\n'))
}

func (s State) validate() error {
	if len(s.Watchlist) > MaxWatchlist || len(s.Holdings) > MaxHoldings {
		return errors.New("too many entries")
	}
	seen := map[string]bool{}
	for _, e := range s.Watchlist {
		if !codeRe.MatchString(e.Code) || seen[e.Code] {
			return fmt.Errorf("bad or repeated watchlist code %q", e.Code)
		}
		seen[e.Code] = true
	}
	clear(seen)
	for _, h := range s.Holdings {
		if !codeRe.MatchString(h.Code) || seen[h.Code] {
			return fmt.Errorf("bad or repeated holding code %q", h.Code)
		}
		seen[h.Code] = true
		if err := checkPosition(h.Lots, h.AvgPrice); err != nil {
			return err
		}
	}
	return checkSettings(s.Settings)
}

func checkPosition(lots int64, avg float64) error {
	if lots < 1 || lots > MaxLots {
		return errors.New("lots out of range")
	}
	if math.IsNaN(avg) || avg < 0.01 || avg > MaxAvgPrice {
		return errors.New("avg_price out of range")
	}
	return nil
}

func checkSettings(s Settings) error {
	for _, v := range []float64{s.BuyFeePct, s.SellFeePct} {
		if math.IsNaN(v) || v < 0 || v > MaxFeePct {
			return errors.New("fee out of range")
		}
	}
	return nil
}

func (s State) watchIndex(code string) int {
	return slices.IndexFunc(s.Watchlist, func(e Entry) bool { return e.Code == code })
}

func (s State) holdingIndex(code string) int {
	return slices.IndexFunc(s.Holdings, func(h Holding) bool { return h.Code == code })
}

// addWatch appends e; a code already listed leaves the list as it is.
func (s State) addWatch(e Entry) (State, error) {
	if s.watchIndex(e.Code) >= 0 {
		return s, nil
	}
	if len(s.Watchlist) >= MaxWatchlist {
		return s, errLimit
	}
	n := s.clone()
	n.Watchlist = append(n.Watchlist, e)
	return n, nil
}

func (s State) removeWatch(code string) (State, error) {
	i := s.watchIndex(code)
	if i < 0 {
		return s, errNotInWatchlist
	}
	n := s.clone()
	n.Watchlist = slices.Delete(n.Watchlist, i, i+1)
	return n, nil
}

// reorderWatch puts the list in the order of codes, which must be the same set.
func (s State) reorderWatch(codes []string) (State, error) {
	if len(codes) != len(s.Watchlist) {
		return s, errBadRequest
	}
	n := s.clone()
	n.Watchlist = n.Watchlist[:0]
	seen := map[string]bool{}
	for _, c := range codes {
		i := s.watchIndex(c)
		if i < 0 || seen[c] {
			return s, errBadRequest
		}
		seen[c] = true
		n.Watchlist = append(n.Watchlist, s.Watchlist[i])
	}
	return n, nil
}

// putHolding adds h or replaces the holding with its code (idempotent).
func (s State) putHolding(h Holding) (State, error) {
	if err := checkPosition(h.Lots, h.AvgPrice); err != nil {
		return s, errBadRequest
	}
	n := s.clone()
	if i := s.holdingIndex(h.Code); i >= 0 {
		n.Holdings[i] = h
		return n, nil
	}
	if len(s.Holdings) >= MaxHoldings {
		return s, errLimit
	}
	n.Holdings = append(n.Holdings, h)
	return n, nil
}

func (s State) removeHolding(code string) (State, error) {
	i := s.holdingIndex(code)
	if i < 0 {
		return s, errNotHeld
	}
	n := s.clone()
	n.Holdings = slices.Delete(n.Holdings, i, i+1)
	return n, nil
}

func (s State) setSettings(set Settings) (State, error) {
	if err := checkSettings(set); err != nil {
		return s, errBadRequest
	}
	n := s.clone()
	n.Settings = set
	return n, nil
}
