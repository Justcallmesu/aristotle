package config

import (
	"io/fs"
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
	"time"
)

func TestDefaults(t *testing.T) {
	cfg, err := Load(filepath.Join(t.TempDir(), "missing.json"))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Addr() != "127.0.0.1:8760" || !cfg.LANEnabled || !cfg.TailscaleEnabled {
		t.Fatalf("defaults = %+v", cfg)
	}
}

func TestParseLANInterfaces(t *testing.T) {
	cfg, err := Parse([]byte(`{"lan_interfaces": ["wlan0"]}`))
	if err != nil {
		t.Fatal(err)
	}
	if len(cfg.LANInterfaces) != 1 || cfg.LANInterfaces[0] != "wlan0" {
		t.Fatalf("cfg = %+v", cfg)
	}
	if def := Default(); def.LANInterfaces != nil {
		t.Fatalf("default lan_interfaces = %v, want unset", def.LANInterfaces)
	}
	if def := Default(); def.Terminal.KeepEnv != nil {
		t.Fatalf("default terminal.keep_env = %v, want none", def.Terminal.KeepEnv)
	}
}

func TestParseTerminalKeepEnv(t *testing.T) {
	cfg, err := Parse([]byte(`{"terminal": {"keep_env": ["SSH_AUTH_SOCK"]}}`))
	if err != nil {
		t.Fatal(err)
	}
	if len(cfg.Terminal.KeepEnv) != 1 || cfg.Terminal.KeepEnv[0] != "SSH_AUTH_SOCK" {
		t.Fatalf("cfg = %+v", cfg)
	}
}

func TestParseListenerToggles(t *testing.T) {
	cfg, err := Parse([]byte(`{"lan_enabled": false}`))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.LANEnabled || !cfg.TailscaleEnabled {
		t.Fatalf("cfg = %+v", cfg)
	}
}

func TestParse(t *testing.T) {
	tests := []struct {
		name, in, wantErr string
		wantAddr          string
	}{
		{"port override", `{"port": 9000}`, "", "127.0.0.1:9000"},
		{"ipv6 loopback", `{"host":"::1"}`, "", "[::1]:8760"},
		{"invalid json", `{"port":`, "invalid config", ""},
		{"unknown key", `{"prot": 1}`, "unknown field", ""},
		{"trailing data", `{} {}`, "trailing", ""},
		{"wildcard refused", `{"host":"0.0.0.0"}`, "loopback", ""},
		{"empty host refused", `{"host":""}`, "loopback", ""},
		{"lan host refused", `{"host":"192.168.1.5"}`, "loopback", ""},
		{"upper-case key", `{"HOST":"127.0.0.1"}`, `"HOST"`, ""},
		{"mixed-case key", `{"Port":9001}`, `"Port"`, ""},
		{"duplicate key", `{"host":"127.0.0.1","host":"::1"}`, `duplicate key "host"`, ""},
		{"empty file", "  \n", "config file is empty", ""},
		{"bad port", `{"port": 70000}`, "out of range", ""},
		{"null file", `null`, "must be a JSON object", ""},
		{"array file", `[]`, "must be a JSON object", ""},
		{"null value", `{"lan_enabled": null}`, `"lan_enabled" must not be null`, ""},
		{"mixed-case toggle", `{"LAN_enabled": false}`, `"LAN_enabled"`, ""},
		{"toggle wrong type", `{"tailscale_enabled": "no"}`, "invalid config", ""},
		{"lan_interfaces list", `{"lan_interfaces": ["wlan0", "enp3s0"]}`, "", "127.0.0.1:8760"},
		{"lan_interfaces empty", `{"lan_interfaces": []}`, "", "127.0.0.1:8760"},
		{"lan_interfaces null", `{"lan_interfaces": null}`, `"lan_interfaces" must not be null`, ""},
		{"lan_interfaces string", `{"lan_interfaces": "wlan0"}`, "invalid config", ""},
		{"lan_interfaces empty name", `{"lan_interfaces": [""]}`, "not an interface name", ""},
		{"lan_interfaces path", `{"lan_interfaces": ["../wlan0"]}`, "not an interface name", ""},
		{"lan_interfaces space", `{"lan_interfaces": ["wl an0"]}`, "not an interface name", ""},
		{"lan_interfaces too long", `{"lan_interfaces": ["abcdefghijklmnop"]}`, "not an interface name", ""},
		{"lan_interfaces duplicate", `{"lan_interfaces": ["wlan0", "wlan0"]}`, "listed twice", ""},
		{"lan_interfaces mixed case key", `{"LAN_interfaces": ["wlan0"]}`, `"LAN_interfaces"`, ""},
		{"terminal keep_env", `{"terminal": {"keep_env": ["SSH_AUTH_SOCK", "DISPLAY"]}}`, "", "127.0.0.1:8760"},
		{"terminal empty", `{"terminal": {}}`, "", "127.0.0.1:8760"},
		{"terminal unknown key", `{"terminal": {"keep": []}}`, `unknown field "terminal.keep"`, ""},
		{"terminal mixed-case key", `{"terminal": {"Keep_Env": []}}`, `"terminal.Keep_Env" must be written exactly as "terminal.keep_env"`, ""},
		{"terminal duplicate key", `{"terminal": {"keep_env": [], "keep_env": []}}`, `duplicate key "terminal.keep_env"`, ""},
		{"terminal null", `{"terminal": null}`, `"terminal" must not be null`, ""},
		{"terminal not an object", `{"terminal": ["DISPLAY"]}`, "terminal must be a JSON object", ""},
		{"keep_env null", `{"terminal": {"keep_env": null}}`, `"terminal.keep_env" must not be null`, ""},
		{"keep_env bad name", `{"terminal": {"keep_env": ["A=B"]}}`, "not a variable name", ""},
		{"keep_env empty name", `{"terminal": {"keep_env": [""]}}`, "not a variable name", ""},
		{"keep_env duplicate", `{"terminal": {"keep_env": ["DISPLAY", "DISPLAY"]}}`, "listed twice", ""},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			cfg, err := Parse([]byte(tt.in))
			if tt.wantErr != "" {
				if err == nil || !strings.Contains(err.Error(), tt.wantErr) {
					t.Fatalf("err = %v, want contains %q", err, tt.wantErr)
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			if cfg.Addr() != tt.wantAddr {
				t.Fatalf("addr = %s, want %s", cfg.Addr(), tt.wantAddr)
			}
		})
	}
}

func TestLoadInvalidFileNamesPath(t *testing.T) {
	p := filepath.Join(t.TempDir(), "config.json")
	if err := os.WriteFile(p, []byte("nope"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := Load(p); err == nil || !strings.Contains(err.Error(), p) {
		t.Fatalf("err = %v", err)
	}
}

func TestPathsHonourXDG(t *testing.T) {
	root := t.TempDir()
	t.Setenv("XDG_CONFIG_HOME", filepath.Join(root, "c"))
	t.Setenv("XDG_STATE_HOME", filepath.Join(root, "s"))
	p, _ := ConfigPath()
	if p != filepath.Join(root, "c", "aristotle", "config.json") {
		t.Fatalf("config path %s", p)
	}
	dir, err := EnsureStateDir(nil)
	if err != nil {
		t.Fatal(err)
	}
	st, err := os.Stat(dir)
	if err != nil || st.Mode().Perm() != 0o700 {
		t.Fatalf("state dir %v %v", st, err)
	}
}

func TestEnsureDirNewIs0700(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "a", "state")
	if err := ensureDir(dir, nil); err != nil {
		t.Fatal(err)
	}
	assertMode(t, dir, 0o700)
}

func TestEnsureDirTightensExisting(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "state")
	if err := os.Mkdir(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := ensureDir(dir, nil); err != nil {
		t.Fatal(err)
	}
	assertMode(t, dir, 0o700)
}

func TestEnsureDirRefusesSymlink(t *testing.T) {
	root := t.TempDir()
	target := filepath.Join(root, "real")
	if err := os.Mkdir(target, 0o700); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(root, "link")
	if err := os.Symlink(target, link); err != nil {
		t.Fatal(err)
	}
	err := ensureDir(link, nil)
	if err == nil || !strings.Contains(err.Error(), "symlink") {
		t.Fatalf("err = %v, want symlink refusal", err)
	}
}

func assertMode(t *testing.T, path string, want os.FileMode) {
	t.Helper()
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if got := info.Mode().Perm(); got != want {
		t.Fatalf("mode = %v, want %v", got, want)
	}
}

func TestEnsureDirRefusesFileInPlace(t *testing.T) {
	p := filepath.Join(t.TempDir(), "state")
	if err := os.WriteFile(p, nil, 0o600); err != nil {
		t.Fatal(err)
	}
	if err := ensureDir(p, nil); err == nil {
		t.Fatal("want error for a regular file")
	}
}

// fakeInfo lets the other-owner check run without root (D3).
type fakeInfo struct {
	mode fs.FileMode
	sys  any
}

func (f fakeInfo) Name() string       { return "state" }
func (f fakeInfo) Size() int64        { return 0 }
func (f fakeInfo) Mode() fs.FileMode  { return f.mode }
func (f fakeInfo) ModTime() time.Time { return time.Time{} }
func (f fakeInfo) IsDir() bool        { return f.mode.IsDir() }
func (f fakeInfo) Sys() any           { return f.sys }

func TestCheckDirSafe(t *testing.T) {
	uid := uint32(os.Getuid())
	tests := []struct {
		name    string
		info    fakeInfo
		wantErr string
	}{
		{"own dir", fakeInfo{fs.ModeDir | 0o700, &syscall.Stat_t{Uid: uid}}, ""},
		{"other owner", fakeInfo{fs.ModeDir | 0o700, &syscall.Stat_t{Uid: uid + 1}}, "owned by uid"},
		{"symlink", fakeInfo{fs.ModeSymlink | 0o777, &syscall.Stat_t{Uid: uid}}, "symlink"},
		{"regular file", fakeInfo{0o600, &syscall.Stat_t{Uid: uid}}, "not a directory"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			err := checkDirSafe("/x/state", tt.info)
			if tt.wantErr == "" {
				if err != nil {
					t.Fatal(err)
				}
				return
			}
			if err == nil || !strings.Contains(err.Error(), tt.wantErr) {
				t.Fatalf("err = %v, want %q", err, tt.wantErr)
			}
		})
	}
}

func TestDiscoveryDefaultsOnAndCanBeDisabled(t *testing.T) {
	cfg, err := Parse([]byte(`{}`))
	if err != nil || !cfg.Discovery.Enabled {
		t.Fatalf("default: %+v %v", cfg, err)
	}
	cfg, err = Parse([]byte(`{"discovery":{"enabled":false}}`))
	if err != nil || cfg.Discovery.Enabled {
		t.Fatalf("off: %+v %v", cfg, err)
	}
	for _, bad := range []string{`{"discovery":{"enable":true}}`, `{"discovery":null}`, `{"discovery":{"enabled":null}}`, `{"Discovery":{}}`} {
		if _, err := Parse([]byte(bad)); err == nil {
			t.Errorf("%s accepted", bad)
		}
	}
}

func TestAppDir(t *testing.T) {
	t.Setenv("XDG_DATA_HOME", "/x/data")
	if got, err := AppDir(); err != nil || got != "/x/data/aristotle/app" {
		t.Errorf("AppDir = %q, %v", got, err)
	}
	t.Setenv("XDG_DATA_HOME", "")
	t.Setenv("HOME", "/h")
	if got, err := AppDir(); err != nil || got != "/h/.local/share/aristotle/app" {
		t.Errorf("default AppDir = %q, %v", got, err)
	}
}
