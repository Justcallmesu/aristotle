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
	"regexp"
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
	Terminal         Terminal `json:"terminal"`
}

// Terminal holds the remote terminal's PC-side settings (docs/terminal.md). The switch and the grants
// are not here: they live in the state dir (terminal.json), changed only by the CLI.
type Terminal struct {
	// KeepEnv names desktop-session variables (DISPLAY, SSH_AUTH_SOCK, ...) that the shell keeps;
	// by default it gets none of them.
	KeepEnv []string `json:"keep_env"`
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
	if err := checkIfaceNames(c.LANInterfaces); err != nil {
		return err
	}
	return checkEnvNames(c.Terminal.KeepEnv)
}

func checkEnvNames(names []string) error {
	seen := map[string]bool{}
	for _, n := range names {
		if !envName.MatchString(n) {
			return fmt.Errorf("terminal.keep_env: %q is not a variable name", n)
		}
		if seen[n] {
			return fmt.Errorf("terminal.keep_env: %q listed twice", n)
		}
		seen[n] = true
	}
	return nil
}

var envName = regexp.MustCompile(`^[A-Za-z_][A-Za-z0-9_]*$`)

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

var knownKeys = map[string]bool{"host": true, "port": true, "lan_enabled": true, "tailscale_enabled": true, "lan_interfaces": true,
	"terminal": true}

// knownObjects lists, for each key whose value is an object, that object's keys.
var knownObjects = map[string]map[string]bool{"terminal": {"keep_env": true}}

// checkKeys requires a JSON object whose top-level keys (and those of the nested objects in
// knownObjects) match exactly (case-sensitive), appear once and are not null. Syntax errors inside
// the object are left to the JSON decoder to report.
func checkKeys(data []byte) error {
	return checkObject(data, knownKeys, "")
}

// checkObject checks one object's keys against known; prefix names the object in errors ("" for the
// top level, "terminal." inside it).
func checkObject(data []byte, known map[string]bool, prefix string) error {
	dec := json.NewDecoder(bytes.NewReader(data))
	tok, err := dec.Token()
	if err != nil {
		return nil
	}
	if tok != json.Delim('{') {
		if prefix == "" {
			return errors.New("config must be a JSON object")
		}
		return fmt.Errorf("%s must be a JSON object", strings.TrimSuffix(prefix, "."))
	}
	seen := map[string]bool{}
	for dec.More() {
		tok, err := dec.Token()
		if err != nil {
			return nil
		}
		key, _ := tok.(string)
		if err := checkKey(key, prefix, known, seen); err != nil {
			return err
		}
		var value json.RawMessage
		if err := dec.Decode(&value); err != nil {
			return nil
		}
		if string(value) == "null" {
			return fmt.Errorf("key %q must not be null", prefix+key)
		}
		if sub, ok := knownObjects[prefix+key]; ok {
			if err := checkObject(value, sub, prefix+key+"."); err != nil {
				return err
			}
		}
	}
	return nil
}

func checkKey(key, prefix string, known, seen map[string]bool) error {
	if !known[key] {
		for k := range known {
			if strings.EqualFold(key, k) {
				return fmt.Errorf("key %q must be written exactly as %q", prefix+key, prefix+k)
			}
		}
		return fmt.Errorf("unknown field %q", prefix+key)
	}
	if seen[key] {
		return fmt.Errorf("duplicate key %q", prefix+key)
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
