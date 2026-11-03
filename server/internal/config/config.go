// Package config loads and validates aristotle-server configuration and
// resolves the config and state directories.
package config

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"log/slog"
	"net"
	"os"
	"path/filepath"
	"strings"
	"syscall"
)

const (
	DefaultHost = "127.0.0.1"
	DefaultPort = 8760
	appDir      = "aristotle"
)

// Config is the on-disk configuration. Unknown keys are rejected.
type Config struct {
	Host string `json:"host"`
	Port int    `json:"port"`
}

// Default returns the configuration used when no file exists.
func Default() Config {
	return Config{Host: DefaultHost, Port: DefaultPort}
}

// Addr returns the host:port listen address.
func (c Config) Addr() string {
	return net.JoinHostPort(c.Host, fmt.Sprint(c.Port))
}

// Validate refuses non-loopback hosts and out-of-range ports.
func (c Config) Validate() error {
	if c.Host != "127.0.0.1" && c.Host != "::1" {
		return fmt.Errorf("host %q refused: only loopback (127.0.0.1, ::1) is allowed", c.Host)
	}
	if c.Port < 1 || c.Port > 65535 {
		return fmt.Errorf("port %d out of range 1-65535", c.Port)
	}
	return nil
}

// Parse decodes JSON onto the defaults, rejecting unknown keys and trailing data.
func Parse(data []byte) (Config, error) {
	if len(bytes.TrimSpace(data)) == 0 {
		return Config{}, errors.New("invalid config: config file is empty")
	}
	if err := checkKeys(data); err != nil {
		return Config{}, fmt.Errorf("invalid config: %w", err)
	}
	cfg := Default()
	dec := json.NewDecoder(bytes.NewReader(data))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&cfg); err != nil {
		return Config{}, fmt.Errorf("invalid config: %w", err)
	}
	if dec.More() {
		return Config{}, errors.New("invalid config: trailing data after JSON value")
	}
	if err := cfg.Validate(); err != nil {
		return Config{}, err
	}
	return cfg, nil
}

var knownKeys = map[string]bool{"host": true, "port": true}

// checkKeys requires top-level keys to match exactly (case-sensitive) and
// rejects duplicates. Non-object input is left to the JSON decoder to report.
func checkKeys(data []byte) error {
	dec := json.NewDecoder(bytes.NewReader(data))
	if tok, err := dec.Token(); err != nil || tok != json.Delim('{') {
		return nil
	}
	seen := map[string]bool{}
	for dec.More() {
		tok, err := dec.Token()
		if err != nil {
			return nil
		}
		key, _ := tok.(string)
		if err := checkKey(key, seen); err != nil {
			return err
		}
		var skip json.RawMessage
		if err := dec.Decode(&skip); err != nil {
			return nil
		}
	}
	return nil
}

func checkKey(key string, seen map[string]bool) error {
	if !knownKeys[key] {
		for known := range knownKeys {
			if strings.EqualFold(key, known) {
				return fmt.Errorf("key %q must be written exactly as %q", key, known)
			}
		}
		return fmt.Errorf("unknown field %q", key)
	}
	if seen[key] {
		return fmt.Errorf("duplicate key %q", key)
	}
	seen[key] = true
	return nil
}

// Load reads the config file at path. A missing file yields the defaults.
func Load(path string) (Config, error) {
	data, err := os.ReadFile(path)
	if errors.Is(err, fs.ErrNotExist) {
		cfg := Default()
		return cfg, cfg.Validate()
	}
	if err != nil {
		return Config{}, fmt.Errorf("read config %s: %w", path, err)
	}
	cfg, err := Parse(data)
	if err != nil {
		return Config{}, fmt.Errorf("%s: %w", path, err)
	}
	return cfg, nil
}

// ConfigPath returns $XDG_CONFIG_HOME/aristotle/config.json (default ~/.config).
func ConfigPath() (string, error) {
	dir, err := xdgDir("XDG_CONFIG_HOME", ".config")
	if err != nil {
		return "", err
	}
	return filepath.Join(dir, "config.json"), nil
}

// StateDir returns $XDG_STATE_HOME/aristotle (default ~/.local/state).
func StateDir() (string, error) {
	return xdgDir("XDG_STATE_HOME", filepath.Join(".local", "state"))
}

// EnsureStateDir creates the state directory and enforces mode 0700. A
// pre-existing directory owned by the current user is chmod'ed (and logged);
// a symlink or a directory owned by another user is refused. A nil log is allowed.
func EnsureStateDir(log *slog.Logger) (string, error) {
	dir, err := StateDir()
	if err != nil {
		return "", err
	}
	if err := ensureDir(dir, log); err != nil {
		return "", err
	}
	return dir, nil
}

func ensureDir(dir string, log *slog.Logger) error {
	if log == nil {
		log = slog.New(slog.NewTextHandler(io.Discard, nil))
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return fmt.Errorf("create state dir: %w", err)
	}
	info, err := os.Lstat(dir)
	if err != nil {
		return fmt.Errorf("stat state dir: %w", err)
	}
	if err := checkDirSafe(dir, info); err != nil {
		return err
	}
	if perm := info.Mode().Perm(); perm != 0o700 {
		if err := os.Chmod(dir, 0o700); err != nil {
			return fmt.Errorf("chmod state dir %s: %w", dir, err)
		}
		log.Info("state dir permissions tightened", "dir", dir, "from", perm.String(), "to", "-rwx------")
	}
	return nil
}

func checkDirSafe(dir string, info fs.FileInfo) error {
	if info.Mode()&fs.ModeSymlink != 0 {
		return fmt.Errorf("state dir %s is a symlink: refused", dir)
	}
	if !info.IsDir() {
		return fmt.Errorf("state dir %s is not a directory", dir)
	}
	if st, ok := info.Sys().(*syscall.Stat_t); ok && int(st.Uid) != os.Getuid() {
		return fmt.Errorf("state dir %s is owned by uid %d, not the current user: refused", dir, st.Uid)
	}
	return nil
}

func xdgDir(env, homeRel string) (string, error) {
	if base := os.Getenv(env); filepath.IsAbs(base) {
		return filepath.Join(base, appDir), nil
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return "", fmt.Errorf("resolve home dir: %w", err)
	}
	return filepath.Join(home, homeRel, appDir), nil
}
