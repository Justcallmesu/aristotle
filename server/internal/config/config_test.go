package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestDefaults(t *testing.T) {
	cfg, err := Load(filepath.Join(t.TempDir(), "missing.json"))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Addr() != "127.0.0.1:8760" {
		t.Fatalf("addr = %s", cfg.Addr())
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
		{"bad port", `{"port": 70000}`, "out of range", ""},
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
	dir, err := EnsureStateDir()
	if err != nil {
		t.Fatal(err)
	}
	st, err := os.Stat(dir)
	if err != nil || st.Mode().Perm() != 0o700 {
		t.Fatalf("state dir %v %v", st, err)
	}
}
