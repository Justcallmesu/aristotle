package stocks

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"net/http"
	"os"
	"path/filepath"
	"slices"
	"time"

	"github.com/justcallmesu/aristotle/server/internal/statefile"
)

// Universe override (spec Q85 "editable on the PC", P8; protocol §20.2): an optional stocks-universe.json
// in the state dir gives the LQ45 and IDX80 members and their period. It is validated at start (a bad file
// is logged and left alone; the bundled list is used) and replaced by PUT /v1/stocks/universe. Names,
// aliases and search still come from the bundled list.

const (
	universeFile        = "stocks-universe.json"
	universeFileVersion = 1
	universeBundled     = "bundled"
	universeFromFile    = "file"
	maxUniverseCodes    = 200
	maxUniverseBody     = 16 << 10
)

// universeOverride is stocks-universe.json and the PUT body (without version and updated_at).
type universeOverride struct {
	Version   int      `json:"version"`
	ValidFrom string   `json:"valid_from,omitempty"`
	ValidTo   string   `json:"valid_to"`
	LQ45      []string `json:"lq45"`
	IDX80     []string `json:"idx80"`
	UpdatedAt string   `json:"updated_at,omitempty"`
}

// normalize upper-cases and de-duplicates the codes and checks the override: codes ^[A-Z]{4}$ (errBadCode),
// 1..200 distinct codes over both lists, valid_to a date, valid_from (optional) a date not after it.
func (o *universeOverride) normalize() error {
	for _, list := range []*[]string{&o.LQ45, &o.IDX80} {
		var out []string
		for _, raw := range *list {
			c, err := normCode(raw)
			if err != nil {
				return err
			}
			if !slices.Contains(out, c) {
				out = append(out, c)
			}
		}
		slices.Sort(out)
		if out == nil {
			out = []string{}
		}
		*list = out
	}
	n := len(o.IDX80)
	for _, c := range o.LQ45 {
		if !slices.Contains(o.IDX80, c) {
			n++
		}
	}
	if n < 1 || n > maxUniverseCodes {
		return errBadRequest
	}
	if _, err := time.Parse(time.DateOnly, o.ValidTo); err != nil {
		return errBadRequest
	}
	if o.ValidFrom != "" {
		if _, err := time.Parse(time.DateOnly, o.ValidFrom); err != nil || o.ValidFrom > o.ValidTo {
			return errBadRequest
		}
	}
	return nil
}

// withOverride is the bundled list with o's membership: each bundled stock tagged by o (search-only when in
// neither list) and o's codes the bundled list lacks kept as unnamed members.
func (u *Universe) withOverride(o universeOverride) *Universe {
	n := &Universe{ValidFrom: o.ValidFrom, ValidTo: o.ValidTo, Source: universeFromFile, byCode: make(map[string]Stock, len(u.Stocks))}
	for _, s := range u.Stocks {
		s.Tags = nil
		if slices.Contains(o.LQ45, s.Code) {
			s.Tags = append(s.Tags, "LQ45")
		}
		if slices.Contains(o.IDX80, s.Code) {
			s.Tags = append(s.Tags, "IDX80")
		}
		n.Stocks = append(n.Stocks, s)
		n.byCode[s.Code] = s
	}
	for _, c := range append(slices.Clone(o.LQ45), o.IDX80...) {
		if _, named := n.byCode[c]; !named && !slices.Contains(n.extra, c) {
			n.extra = append(n.extra, c)
		}
	}
	slices.Sort(n.extra)
	n.lq45, n.idx80 = slices.Clone(o.LQ45), slices.Clone(o.IDX80)
	return n
}

// Members are the screener universe codes (LQ45 ∪ IDX80), sorted.
func (u *Universe) Members() []string {
	out := slices.Clone(u.extra)
	for _, s := range u.Stocks {
		if s.InIndex() {
			out = append(out, s.Code)
		}
	}
	slices.Sort(out)
	return out
}

// InIndex reports whether code is a member of the screener universe.
func (u *Universe) InIndex(code string) bool {
	if s, ok := u.byCode[code]; ok {
		return s.InIndex()
	}
	return slices.Contains(u.extra, code)
}

// tagged lists the bundled stocks carrying tag, sorted.
func (u *Universe) tagged(tag string) []string {
	out := []string{}
	for _, s := range u.Stocks {
		if slices.Contains(s.Tags, tag) {
			out = append(out, s.Code)
		}
	}
	slices.Sort(out)
	return out
}

// parseOverride reads stocks-universe.json strictly (unknown fields refused).
func parseOverride(data []byte) (universeOverride, error) {
	var o universeOverride
	dec := json.NewDecoder(bytes.NewReader(data))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&o); err != nil {
		return o, fmt.Errorf("%s: %w", universeFile, err)
	}
	if _, err := dec.Token(); !errors.Is(err, io.EOF) {
		return o, fmt.Errorf("%s: data after the object", universeFile)
	}
	if o.Version != universeFileVersion {
		return o, fmt.Errorf("%s: version %d, want %d", universeFile, o.Version, universeFileVersion)
	}
	if err := o.normalize(); err != nil {
		return o, fmt.Errorf("%s: invalid lists or dates (codes ^[A-Z]{4}$, 1..%d codes, valid_to YYYY-MM-DD)", universeFile, maxUniverseCodes)
	}
	return o, nil
}

// universeState is the universe in use and why the file was not used ("" when it was, or there is none).
type universeState struct {
	u       *Universe
	fileErr string
}

// loadUniverse picks the override when the state dir has a valid one, else the bundled list.
func loadUniverse(dir string, bundled *Universe, log logger) universeState {
	data, err := statefile.Read(dir, universeFile)
	if errors.Is(err, fs.ErrNotExist) {
		return universeState{u: bundled}
	}
	var o universeOverride
	if err == nil {
		o, err = parseOverride(data)
	}
	if err != nil {
		log.Error("stocks: universe override unusable; the bundled LQ45/IDX80 list is used until it is fixed or replaced",
			"file", universeFile, "err", err)
		return universeState{u: bundled, fileErr: "invalid"}
	}
	return universeState{u: bundled.withOverride(o)}
}

// uni is the universe in use (replaced as a whole by PUT/DELETE /v1/stocks/universe).
func (s *Service) uni() *Universe { return s.universe.Load().u }

// --- HTTP ---

type universeBody struct {
	Source    string   `json:"source"`
	ValidFrom *string  `json:"valid_from"`
	ValidTo   string   `json:"valid_to"`
	Expired   bool     `json:"expired"`
	Count     int      `json:"count"`
	LQ45      []string `json:"lq45"`
	IDX80     []string `json:"idx80"`
	FileError *string  `json:"file_error"`
}

func (s *Service) universeView() universeBody {
	st := s.universe.Load()
	u := st.u
	b := universeBody{Source: u.Source, ValidFrom: nonEmpty(u.ValidFrom), ValidTo: u.ValidTo, Expired: u.Expired(s.now()),
		Count: len(u.Members()), LQ45: slices.Clone(u.lq45), IDX80: slices.Clone(u.idx80), FileError: nonEmpty(st.fileErr)}
	return b
}

func (s *Service) getUniverse(*http.Request) (int, any, error) {
	s.uniMu.Lock()
	defer s.uniMu.Unlock()
	return http.StatusOK, s.universeView(), nil
}

// putUniverse validates the lists, writes stocks-universe.json and switches to it.
func (s *Service) putUniverse(r *http.Request) (int, any, error) {
	var body struct {
		ValidFrom string    `json:"valid_from"`
		ValidTo   string    `json:"valid_to"`
		LQ45      *[]string `json:"lq45"`
		IDX80     *[]string `json:"idx80"`
	}
	if err := decodeBodyMax(r, &body, maxUniverseBody); err != nil || body.LQ45 == nil || body.IDX80 == nil {
		return 0, nil, errBadRequest
	}
	o := universeOverride{Version: universeFileVersion, ValidFrom: body.ValidFrom, ValidTo: body.ValidTo, LQ45: *body.LQ45, IDX80: *body.IDX80}
	if err := o.normalize(); err != nil {
		return 0, nil, err
	}
	o.UpdatedAt = *rfc(s.now())
	data, err := json.MarshalIndent(o, "", "  ")
	if err != nil {
		return 0, nil, errState
	}
	s.uniMu.Lock()
	defer s.uniMu.Unlock()
	if err := statefile.WriteAtomic(s.dir, universeFile, append(data, '\n')); err != nil {
		s.log.Error("stocks: saving the universe override failed", "err", err)
		return 0, nil, errState
	}
	s.universe.Store(&universeState{u: s.bundled.withOverride(o)})
	s.log.Info("stocks: universe override saved", "codes", len(s.uni().Members()), "valid_to", o.ValidTo)
	return http.StatusOK, s.universeView(), nil
}

// deleteUniverse removes the override: the bundled list is used again.
func (s *Service) deleteUniverse(*http.Request) (int, any, error) {
	s.uniMu.Lock()
	defer s.uniMu.Unlock()
	if err := os.Remove(filepath.Join(s.dir, universeFile)); err != nil && !errors.Is(err, fs.ErrNotExist) {
		s.log.Error("stocks: removing the universe override failed", "err", err)
		return 0, nil, errState
	}
	s.universe.Store(&universeState{u: s.bundled})
	return http.StatusOK, s.universeView(), nil
}
