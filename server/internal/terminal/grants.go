package terminal

import (
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"slices"
	"sync"

	"github.com/justcallmesu/aristotle/server/internal/statefile"
)

// GrantsFile holds the terminal switch and the granted device ids, in the state dir (0600, replaced
// atomically). It is separate from devices.json, which older binaries decode strictly, and from the
// config file, which the server never writes (spec Q35, Q53; T19 red-team B1).
const GrantsFile = "terminal.json"

// Refusals: why a device may not open a terminal. The text is also the 403 body and the 4403 reason.
var (
	ErrDisabled   = errors.New("terminal_disabled")
	ErrNotGranted = errors.New("terminal_not_granted")
)

type grantsFile struct {
	Enabled bool     `json:"enabled"`
	Devices []string `json:"devices"`
}

// Grants is the terminal switch and the per-device grants. Changes are written through before they
// count, except that a restrictive change (disable, ungrant) also counts when the write fails: the
// server must never keep a terminal open that the user turned off.
type Grants struct {
	dir string

	mu       sync.Mutex
	state    grantsFile
	problem  error // terminal.json could not be used: disabled, no grants, until it loads (OpenGrantsFailClosed)
	onChange []func()
}

// OpenGrants loads <dir>/terminal.json; a missing file means disabled with no grants. Unknown fields
// are ignored so that a downgrade keeps working; anything else unreadable is an error (fail closed).
func OpenGrants(dir string) (*Grants, error) {
	state, err := loadGrants(dir)
	if err != nil {
		return nil, err
	}
	return &Grants{dir: dir, state: state}, nil
}

// OpenGrantsFailClosed is OpenGrants for the server: when terminal.json is corrupt, has loose
// permissions or cannot be read, it still returns Grants, disabled with no grants, along with the
// error, so that the server (and its relay) keeps running without a terminal (T19a review m4).
// Problem keeps reporting the error; the file is not overwritten. Enable and grant load the file
// again first and are refused while it is still unusable.
func OpenGrantsFailClosed(dir string) (*Grants, error) {
	state, err := loadGrants(dir)
	if err != nil {
		return &Grants{dir: dir, problem: err}, err
	}
	return &Grants{dir: dir, state: state}, nil
}

func loadGrants(dir string) (grantsFile, error) {
	var state grantsFile
	data, err := statefile.Read(dir, GrantsFile)
	if errors.Is(err, fs.ErrNotExist) {
		return state, nil
	}
	if err != nil {
		return state, err
	}
	if err := json.Unmarshal(data, &state); err != nil {
		return grantsFile{}, fmt.Errorf("%s: %w", GrantsFile, err)
	}
	return state, nil
}

// Problem returns why terminal.json could not be used (the terminal is then disabled), or nil.
func (g *Grants) Problem() error {
	g.mu.Lock()
	defer g.mu.Unlock()
	return g.problem
}

// Status returns the switch and a copy of the granted ids.
func (g *Grants) Status() (enabled bool, granted []string) {
	g.mu.Lock()
	defer g.mu.Unlock()
	return g.state.Enabled, slices.Clone(g.state.Devices)
}

// Allowed returns nil when the terminal is enabled and id is granted, else ErrDisabled or ErrNotGranted.
func (g *Grants) Allowed(id string) error {
	g.mu.Lock()
	defer g.mu.Unlock()
	switch {
	case !g.state.Enabled:
		return ErrDisabled
	case !slices.Contains(g.state.Devices, id):
		return ErrNotGranted
	}
	return nil
}

// Granted reports whether id holds a grant (whatever the switch).
func (g *Grants) Granted(id string) bool {
	g.mu.Lock()
	defer g.mu.Unlock()
	return slices.Contains(g.state.Devices, id)
}

// OnChange registers fn to run after every change, on the changing goroutine, once the new state is
// in effect (Allowed already answers with it). fn must not block.
func (g *Grants) OnChange(fn func()) {
	g.mu.Lock()
	defer g.mu.Unlock()
	g.onChange = append(g.onChange, fn)
}

// SetEnabled turns the terminal on or off and reports whether that changed anything.
func (g *Grants) SetEnabled(on bool) (bool, error) {
	return g.update(!on, func(s *grantsFile) bool {
		changed := s.Enabled != on
		s.Enabled = on
		return changed
	})
}

// Grant lets device id open terminals (while the switch is on).
func (g *Grants) Grant(id string) (bool, error) {
	return g.update(false, func(s *grantsFile) bool {
		if slices.Contains(s.Devices, id) {
			return false
		}
		s.Devices = append(s.Devices, id)
		return true
	})
}

// Ungrant removes id's grant.
func (g *Grants) Ungrant(id string) (bool, error) {
	return g.update(true, func(s *grantsFile) bool {
		i := slices.Index(s.Devices, id)
		if i < 0 {
			return false
		}
		s.Devices = slices.Delete(s.Devices, i, i+1)
		return true
	})
}

// update applies change to a copy, saves it and makes it current, then runs the hooks. When the save
// fails a restrictive change still becomes current (and the hooks run); the error is returned. While
// terminal.json is unusable, a widening change first loads it again and is refused if it still fails.
func (g *Grants) update(restrictive bool, change func(*grantsFile) bool) (bool, error) {
	g.mu.Lock()
	if g.problem != nil && !restrictive {
		state, err := loadGrants(g.dir)
		if err != nil {
			g.problem = err
			g.mu.Unlock()
			return false, fmt.Errorf("the terminal stays disabled until %s is fixed or removed: %w", GrantsFile, err)
		}
		g.state, g.problem = state, nil
	}
	next := grantsFile{Enabled: g.state.Enabled, Devices: slices.Clone(g.state.Devices)}
	if !change(&next) {
		g.mu.Unlock()
		return false, nil
	}
	err := g.save(next)
	if err != nil && !restrictive {
		g.mu.Unlock()
		return false, err
	}
	g.state = next
	hooks := slices.Clone(g.onChange)
	g.mu.Unlock()
	for _, fn := range hooks {
		fn()
	}
	if err != nil {
		return true, fmt.Errorf("applied until the server stops, but not saved: %w", err)
	}
	return true, nil
}

func (g *Grants) save(s grantsFile) error {
	if s.Devices == nil {
		s.Devices = []string{}
	}
	data, err := json.MarshalIndent(s, "", "  ")
	if err != nil {
		return err
	}
	return statefile.WriteAtomic(g.dir, GrantsFile, append(data, '\n'))
}
