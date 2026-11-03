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
// Host is the loopback listener; Port is shared by every listener.
// LANInterfaces, when not empty, names the only interfaces that get a LAN
// listener (default: every physical interface).
type Config struct {
	Host             string   `json:"host"`
	Port             int      `json:"port"`
	LANEnabled       bool     `json:"lan_enabled"`
	TailscaleEnabled bool     `json:"tailscale_enabled"`
	LANInterfaces    []string `json:"lan_interfaces"`
}

// Default returns the configuration used when no file exists.
func Default() Config {
	return Config{Host: DefaultHost, Port: DefaultPort, LANEnabled: true, TailscaleEnabled: true}
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
	return checkIfaceNames(c.LANInterfaces)
}

// maxIfaceName is IFNAMSIZ minus the terminating NUL on Linux.
const maxIfaceName = 15

func checkIfaceNames(names []string) error {
	seen := map[string]bool{}
	for _, n := range names {
		if n == "" || len(n) > maxIfaceName || n == "." || n == ".." || strings.ContainsFunc(n, badIfaceRune) {
			return fmt.Errorf("lan_interfaces: %q is not an interface name", n)
		}
		if seen[n] {
			return fmt.Errorf("lan_interfaces: %q listed twice", n)
		}
		seen[n] = true
	}
	return nil
}

func badIfaceRune(r rune) bool {
	return r <= ' ' || r >= 0x7f || r == '/' || r == ':'
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

var knownKeys = map[string]bool{"host": true, "port": true, "lan_enabled": true, "tailscale_enabled": true, "lan_interfaces": true}

// checkKeys requires a JSON object whose top-level keys match exactly
// (case-sensitive), appear once and are not null. Syntax errors inside the
// object are left to the JSON decoder to report.
func checkKeys(data []byte) error {
	dec := json.NewDecoder(bytes.NewReader(data))
	tok, err := dec.Token()
	if err != nil {
		return nil
	}
	if tok != json.Delim('{') {
		return errors.New("config must be a JSON object")
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
		var value json.RawMessage
		if err := dec.Decode(&value); err != nil {
			return nil
		}
		if string(value) == "null" {
			return fmt.Errorf("key %q must not be null", key)
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
//
// The directory is opened with O_NOFOLLOW and checked and chmod'ed through
// that handle (fstat/fchmod), so a swap between check and chmod cannot
// redirect the chmod to another path.
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
	f, err := openDirNoFollow(dir)
	if err != nil {
		return err
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil {
		return fmt.Errorf("stat state dir: %w", err)
	}
	if err := checkDirSafe(dir, info); err != nil {
		return err
	}
	if perm := info.Mode().Perm(); perm != 0o700 {
		if err := f.Chmod(0o700); err != nil {
			return fmt.Errorf("chmod state dir %s: %w", dir, err)
		}
		log.Info("state dir permissions tightened", "dir", dir, "from", perm.String(), "to", "-rwx------")
	}
	return nil
}

// openDirNoFollow opens dir without following a symlink in its last component.
func openDirNoFollow(dir string) (*os.File, error) {
	fd, err := syscall.Open(dir, syscall.O_RDONLY|syscall.O_DIRECTORY|syscall.O_NOFOLLOW|syscall.O_CLOEXEC, 0)
	switch {
	case errors.Is(err, syscall.ELOOP), errors.Is(err, syscall.ENOTDIR):
		// Linux reports a symlink opened with O_NOFOLLOW|O_DIRECTORY as
		// ENOTDIR; Lstat here only picks the error message.
		if info, lerr := os.Lstat(dir); lerr == nil && info.Mode()&fs.ModeSymlink != 0 {
			return nil, fmt.Errorf("state dir %s is a symlink: refused", dir)
		}
		return nil, fmt.Errorf("state dir %s is not a directory", dir)
	case err != nil:
		return nil, fmt.Errorf("open state dir %s: %w", dir, err)
	}
	return os.NewFile(uintptr(fd), dir), nil
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
