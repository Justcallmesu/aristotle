package terminal

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
)

func TestGrantsCRUDPersistsAtomically(t *testing.T) {
	dir := t.TempDir()
	g, err := OpenGrants(dir)
	if err != nil {
		t.Fatal(err)
	}
	if err := g.Allowed("dev_a"); !errors.Is(err, ErrDisabled) {
		t.Fatalf("fresh: %v, want disabled", err)
	}
	var hooks atomic.Int32
	g.OnChange(func() { hooks.Add(1) })
	changes(t)(g.SetEnabled(true))
	if err := g.Allowed("dev_a"); !errors.Is(err, ErrNotGranted) {
		t.Fatalf("enabled, ungranted: %v", err)
	}
	changes(t)(g.Grant("dev_a"))
	changes(t)(g.Grant("dev_b"))
	if changed, err := g.Grant("dev_a"); changed || err != nil {
		t.Fatalf("second grant: %v %v", changed, err)
	}
	if err := g.Allowed("dev_a"); err != nil {
		t.Fatal(err)
	}
	changes(t)(g.Ungrant("dev_b"))
	if hooks.Load() != 4 {
		t.Fatalf("%d hook runs, want 4 (one per change)", hooks.Load())
	}

	info, err := os.Stat(filepath.Join(dir, GrantsFile))
	if err != nil || info.Mode().Perm() != 0o600 {
		t.Fatalf("file mode %v %v", info, err)
	}
	entries, _ := os.ReadDir(dir)
	for _, e := range entries {
		if strings.Contains(e.Name(), ".tmp") {
			t.Fatalf("temp file left: %s", e.Name())
		}
	}
	again, err := OpenGrants(dir)
	if err != nil {
		t.Fatal(err)
	}
	if on, ids := again.Status(); !on || len(ids) != 1 || ids[0] != "dev_a" {
		t.Fatalf("reloaded: %v %v", on, ids)
	}
	changes(t)(again.SetEnabled(false))
	if third, _ := OpenGrants(dir); third.Allowed("dev_a") == nil {
		t.Fatal("disable not saved")
	}
}

// A failed save never widens access, but a restrictive change (disable, ungrant) still applies.
func TestGrantsSaveFailure(t *testing.T) {
	if os.Getuid() == 0 {
		t.Skip("root ignores directory permissions")
	}
	dir := t.TempDir()
	g, _ := OpenGrants(dir)
	changes(t)(g.SetEnabled(true))
	changes(t)(g.Grant("dev_a"))
	if err := os.Chmod(dir, 0o500); err != nil {
		t.Fatal(err)
	}
	defer os.Chmod(dir, 0o700)
	if changed, err := g.Grant("dev_b"); changed || err == nil || g.Granted("dev_b") {
		t.Fatalf("grant with a failed save: changed=%v err=%v", changed, err)
	}
	changed, err := g.SetEnabled(false)
	if !changed || err == nil || !strings.Contains(err.Error(), "not saved") {
		t.Fatalf("disable with a failed save: changed=%v err=%v", changed, err)
	}
	if err := g.Allowed("dev_a"); !errors.Is(err, ErrDisabled) {
		t.Fatalf("disable did not apply: %v", err)
	}
}

func TestGrantsFileRefusedWhenUnsafeOrCorrupt(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, GrantsFile)
	if err := os.WriteFile(path, []byte(`{"enabled":true,"devices":["dev_a"],"future":1}`), 0o600); err != nil {
		t.Fatal(err)
	}
	if g, err := OpenGrants(dir); err != nil || g.Allowed("dev_a") != nil {
		t.Fatalf("unknown field: %v", err)
	}
	if err := os.WriteFile(path, []byte(`{"enabled":tru`), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := OpenGrants(dir); err == nil {
		t.Fatal("corrupt file accepted")
	}
	if err := os.WriteFile(path, []byte(`{"enabled":true}`), 0o644); err != nil {
		t.Fatal(err)
	}
	os.Chmod(path, 0o644)
	if _, err := OpenGrants(dir); err == nil {
		t.Fatal("group-readable file accepted")
	}
}

// T19a review m4: an unusable terminal.json leaves the terminal disabled with no grants and says why;
// enable and grant are refused (the file is not overwritten) until it loads again.
func TestGrantsFailClosed(t *testing.T) {
	for name, bad := range map[string]struct {
		data string
		mode os.FileMode
	}{
		"corrupt":         {`{"enabled":tru`, 0o600},
		"group-readable":  {`{"enabled":true,"devices":["dev_a"]}`, 0o640},
		"world-readable":  {`{"enabled":true,"devices":["dev_a"]}`, 0o644},
		"not json object": {`[]`, 0o600},
	} {
		t.Run(name, func(t *testing.T) {
			dir := t.TempDir()
			path := filepath.Join(dir, GrantsFile)
			if err := os.WriteFile(path, []byte(bad.data), 0o600); err != nil {
				t.Fatal(err)
			}
			if err := os.Chmod(path, bad.mode); err != nil {
				t.Fatal(err)
			}
			g, err := OpenGrantsFailClosed(dir)
			if err == nil || g == nil {
				t.Fatalf("g=%v err=%v", g, err)
			}
			if p := g.Problem(); p == nil || p.Error() != err.Error() {
				t.Fatalf("problem %v, want %v", p, err)
			}
			if err := g.Allowed("dev_a"); !errors.Is(err, ErrDisabled) {
				t.Fatalf("allowed: %v", err)
			}
			if on, ids := g.Status(); on || len(ids) != 0 {
				t.Fatalf("status %v %v", on, ids)
			}
			for _, widen := range []func() (bool, error){func() (bool, error) { return g.SetEnabled(true) },
				func() (bool, error) { return g.Grant("dev_b") }} {
				if changed, err := widen(); changed || err == nil || !strings.Contains(err.Error(), "stays disabled") {
					t.Fatalf("widening change while unusable: %v %v", changed, err)
				}
			}
			if changed, err := g.SetEnabled(false); changed || err != nil {
				t.Fatalf("disable: %v %v", changed, err)
			}
			if data, _ := os.ReadFile(path); string(data) != bad.data {
				t.Fatalf("file overwritten: %s", data)
			}
			// Fixed on the PC: the next enable loads it again.
			if err := os.Chmod(path, 0o600); err != nil {
				t.Fatal(err)
			}
			if err := os.WriteFile(path, []byte(`{"enabled":false,"devices":["dev_a"]}`), 0o600); err != nil {
				t.Fatal(err)
			}
			changes(t)(g.SetEnabled(true))
			if g.Problem() != nil || g.Allowed("dev_a") != nil {
				t.Fatalf("after the fix: problem %v, allowed %v", g.Problem(), g.Allowed("dev_a"))
			}
		})
	}
	g, err := OpenGrantsFailClosed(t.TempDir()) // no file: fine, disabled
	if err != nil || g.Problem() != nil {
		t.Fatalf("missing file: %v %v", err, g.Problem())
	}
}

// changes fails the test unless the grants call it wraps changed something without error.
func changes(t *testing.T) func(bool, error) {
	return func(changed bool, err error) {
		t.Helper()
		if err != nil || !changed {
			t.Fatalf("changed=%v err=%v", changed, err)
		}
	}
}
