// Package devices stores paired devices and authenticates their tokens.
//
// A token is 32 random bytes, base64url without padding. Only its SHA-256 is
// kept, in <state>/devices.json (0600, replaced atomically).
package devices

import (
	"bytes"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"log/slog"
	"slices"
	"sync"
	"time"

	"github.com/justcallmesu/aristotle/server/internal/statefile"
)

const (
	FileName = "devices.json"
	// lastSeenEvery bounds how often a request rewrites devices.json just to
	// move last_seen_at forward.
	lastSeenEvery = time.Minute
)

// ErrNotFound is returned when revoking an unknown id.
var ErrNotFound = errors.New("no such device")

// Device is what the laptop knows about a paired phone.
type Device struct {
	ID         string    `json:"id"`
	Name       string    `json:"name"`
	TokenHash  string    `json:"token_sha256"`
	CreatedAt  time.Time `json:"created_at"`
	LastSeenAt time.Time `json:"last_seen_at"`
}

// Public is a device without its token hash, for listings.
type Public struct {
	ID         string    `json:"id"`
	Name       string    `json:"name"`
	CreatedAt  time.Time `json:"created_at"`
	LastSeenAt time.Time `json:"last_seen_at"`
}

func (d Device) public() Public {
	return Public{ID: d.ID, Name: d.Name, CreatedAt: d.CreatedAt, LastSeenAt: d.LastSeenAt}
}

type fileFormat struct {
	Devices []Device `json:"devices"`
}

// Store is the device list, loaded once and written through on change.
type Store struct {
	dir string
	now func() time.Time
	log *slog.Logger

	mu        sync.Mutex
	devices   []Device
	persisted map[string]time.Time // last_seen_at as last written
}

// Open loads <dir>/devices.json (missing file = no devices).
func Open(dir string, now func() time.Time, log *slog.Logger) (*Store, error) {
	if now == nil {
		now = time.Now
	}
	s := &Store{dir: dir, now: now, log: log, persisted: map[string]time.Time{}}
	data, err := statefile.Read(dir, FileName)
	if errors.Is(err, fs.ErrNotExist) {
		return s, nil
	}
	if err != nil {
		return nil, err
	}
	var f fileFormat
	dec := json.NewDecoder(bytes.NewReader(data))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&f); err != nil {
		return nil, fmt.Errorf("%s: %w", FileName, err)
	}
	s.devices = f.Devices
	for _, d := range s.devices {
		s.persisted[d.ID] = d.LastSeenAt
	}
	return s, nil
}

// Add creates a device and returns it with its token. The token is not
// stored and cannot be recovered.
func (s *Store) Add(name string) (Public, string, error) {
	token, err := randomString(32, base64.RawURLEncoding.EncodeToString)
	if err != nil {
		return Public{}, "", err
	}
	id, err := randomString(6, func(b []byte) string { return "dev_" + hex.EncodeToString(b) })
	if err != nil {
		return Public{}, "", err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	now := s.now().UTC()
	d := Device{ID: id, Name: name, TokenHash: hashToken(token), CreatedAt: now, LastSeenAt: now}
	next := append(slices.Clone(s.devices), d)
	if err := s.save(next); err != nil {
		return Public{}, "", err
	}
	s.devices = next
	return d.public(), token, nil
}

// Authenticate returns the device whose token matches. Hashes are compared
// in constant time against every stored device.
func (s *Store) Authenticate(token string) (Public, bool) {
	if token == "" {
		return Public{}, false
	}
	want := []byte(hashToken(token))
	s.mu.Lock()
	defer s.mu.Unlock()
	idx := -1
	for i, d := range s.devices {
		if subtle.ConstantTimeCompare(want, []byte(d.TokenHash)) == 1 {
			idx = i
		}
	}
	if idx < 0 {
		return Public{}, false
	}
	s.touch(idx)
	return s.devices[idx].public(), true
}

// touch moves last_seen_at forward, writing the file at most once per
// lastSeenEvery per device. A failed write is logged, not fatal.
func (s *Store) touch(idx int) {
	now := s.now().UTC()
	s.devices[idx].LastSeenAt = now
	id := s.devices[idx].ID
	if now.Sub(s.persisted[id]) < lastSeenEvery {
		return
	}
	if err := s.save(s.devices); err != nil {
		if s.log != nil {
			s.log.Warn("could not record last_seen_at", "device_id", id, "err", err)
		}
	}
}

// List returns the devices, oldest first.
func (s *Store) List() []Public {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]Public, 0, len(s.devices))
	for _, d := range s.devices {
		out = append(out, d.public())
	}
	return out
}

// Revoke deletes a device; its token stops working on the next request.
func (s *Store) Revoke(id string) (Public, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	i := slices.IndexFunc(s.devices, func(d Device) bool { return d.ID == id })
	if i < 0 {
		return Public{}, ErrNotFound
	}
	gone := s.devices[i]
	next := slices.Delete(slices.Clone(s.devices), i, i+1)
	if err := s.save(next); err != nil {
		return Public{}, err
	}
	s.devices = next
	delete(s.persisted, id)
	return gone.public(), nil
}

// save writes list to disk. Caller holds s.mu.
func (s *Store) save(list []Device) error {
	if list == nil {
		list = []Device{}
	}
	data, err := json.MarshalIndent(fileFormat{Devices: list}, "", "  ")
	if err != nil {
		return err
	}
	if err := statefile.WriteAtomic(s.dir, FileName, append(data, '\n')); err != nil {
		return err
	}
	for _, d := range list {
		s.persisted[d.ID] = d.LastSeenAt
	}
	return nil
}

func hashToken(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}

func randomString(n int, encode func([]byte) string) (string, error) {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return encode(b), nil
}
