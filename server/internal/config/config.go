// Package config loads and validates aristotle-server configuration and
// resolves the config and state directories.
package config

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"net"
	"os"
	"path/filepath"
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

// EnsureStateDir creates the state directory with mode 0700.
func EnsureStateDir() (string, error) {
	dir, err := StateDir()
	if err != nil {
		return "", err
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return "", fmt.Errorf("create state dir: %w", err)
	}
	return dir, nil
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
