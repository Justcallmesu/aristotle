package devices

import (
	"encoding/base64"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

type clock struct {
	mu sync.Mutex
	t  time.Time
}

func (c *clock) now() time.Time { c.mu.Lock(); defer c.mu.Unlock(); return c.t }
func (c *clock) add(d time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.t = c.t.Add(d)
}

func open(t *testing.T, dir string, clk *clock) *Store {
	t.Helper()
	s, err := Open(dir, clk.now, nil)
	if err != nil {
		t.Fatal(err)
	}
	return s
}

func newClock() *clock { return &clock{t: time.Date(2026, 11, 3, 10, 0, 0, 0, time.UTC)} }

func TestAddAuthenticateRevoke(t *testing.T) {
	dir := t.TempDir()
	s := open(t, dir, newClock())
	dev, token, err := s.Add("poco")
	if err != nil {
		t.Fatal(err)
	}
	raw, err := base64.RawURLEncoding.DecodeString(token)
	if err != nil || len(raw) != 32 {
		t.Fatalf("token is not 32 bytes base64url: %d %v", len(raw), err)
	}
	got, ok := s.Authenticate(token)
	if !ok || got.ID != dev.ID || got.Name != "poco" {
		t.Fatalf("authenticate = %+v %v", got, ok)
	}
	for _, bad := range []string{"", token + "x", strings.ToUpper(token), "Bearer " + token} {
		if _, ok := s.Authenticate(bad); ok {
			t.Errorf("token %q accepted", bad)
		}
	}
	if _, err := s.Revoke(dev.ID); err != nil {
		t.Fatal(err)
	}
	if _, ok := s.Authenticate(token); ok {
		t.Fatal("revoked token accepted")
	}
	if _, err := s.Revoke(dev.ID); err != ErrNotFound {
		t.Fatalf("second revoke err = %v", err)
	}
}

func TestFileHasOnlyHashAndIs0600(t *testing.T) {
	dir := t.TempDir()
	s := open(t, dir, newClock())
	_, token, err := s.Add("poco")
	if err != nil {
		t.Fatal(err)
	}
	p := filepath.Join(dir, FileName)
	info, err := os.Stat(p)
	if err != nil {
		t.Fatal(err)
	}
	if info.Mode().Perm() != 0o600 {
		t.Fatalf("mode = %v", info.Mode().Perm())
	}
	data, _ := os.ReadFile(p)
	if strings.Contains(string(data), token) {
		t.Fatal("plain token written to devices.json")
	}
	if !strings.Contains(string(data), hashToken(token)) {
		t.Fatal("token hash missing from devices.json")
	}
	entries, _ := os.ReadDir(dir)
	if len(entries) != 1 {
		t.Fatalf("unexpected files (temp left behind?): %v", entries)
	}
}

func TestReloadKeepsDevicesAndRevocation(t *testing.T) {
	dir := t.TempDir()
	clk := newClock()
	s := open(t, dir, clk)
	keep, keepTok, _ := s.Add("keep")
	gone, goneTok, _ := s.Add("gone")
	if _, err := s.Revoke(gone.ID); err != nil {
		t.Fatal(err)
	}
	s2 := open(t, dir, clk)
	if d, ok := s2.Authenticate(keepTok); !ok || d.ID != keep.ID {
		t.Fatal("kept device lost on reload")
	}
	if _, ok := s2.Authenticate(goneTok); ok {
		t.Fatal("revoked device back after reload")
	}
	if l := s2.List(); len(l) != 1 || l[0].Name != "keep" {
		t.Fatalf("list = %+v", l)
	}
}

func TestLastSeenWrittenAtMostOncePerMinute(t *testing.T) {
	dir := t.TempDir()
	clk := newClock()
	s := open(t, dir, clk)
	_, tok, _ := s.Add("p")
	p := filepath.Join(dir, FileName)
	before, _ := os.ReadFile(p)
	clk.add(10 * time.Second)
	s.Authenticate(tok)
	if after, _ := os.ReadFile(p); string(after) != string(before) {
		t.Fatal("file rewritten within a minute")
	}
	clk.add(time.Minute)
	s.Authenticate(tok)
	s2 := open(t, dir, clk)
	if got := s2.List()[0].LastSeenAt; !got.Equal(clk.now()) {
		t.Fatalf("last_seen_at = %v, want %v", got, clk.now())
	}
}

func TestOpenRefusesLooseFile(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, FileName)
	if err := os.WriteFile(p, []byte(`{"devices":[]}`), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(p, 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := Open(dir, nil, nil); err == nil {
		t.Fatal("0644 devices.json accepted")
	}
}

func TestOpenRefusesUnknownFields(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, FileName), []byte(`{"devices":[{"id":"x","token":"plain"}]}`), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := Open(dir, nil, nil); err == nil {
		t.Fatal("unknown field accepted")
	}
}

func TestOnRevokeRunsAfterRemovalOnly(t *testing.T) {
	s := open(t, t.TempDir(), newClock())
	keep, _, _ := s.Add("keep")
	gone, _, _ := s.Add("gone")
	var got []string
	var hasDuringHook bool
	s.OnRevoke(func(id string) { got = append(got, id); hasDuringHook = s.Has(id) })
	s.OnRevoke(func(id string) { got = append(got, "second:"+id) })
	if !s.Has(gone.ID) || !s.Has(keep.ID) {
		t.Fatal("Has is false for a paired device")
	}
	if _, err := s.Revoke(gone.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Revoke("dev_unknown"); err != ErrNotFound {
		t.Fatalf("unknown revoke err = %v", err)
	}
	if len(got) != 2 || got[0] != gone.ID || got[1] != "second:"+gone.ID {
		t.Fatalf("hook calls = %v, want one per hook for %s only", got, gone.ID)
	}
	if hasDuringHook || s.Has(gone.ID) || !s.Has(keep.ID) {
		t.Fatalf("Has during hook=%v after=%v keep=%v", hasDuringHook, s.Has(gone.ID), s.Has(keep.ID))
	}
}
