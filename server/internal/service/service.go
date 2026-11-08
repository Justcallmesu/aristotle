// Package service installs aristotle-server as a systemd user service. All
// systemctl and journalctl calls go through Runner so tests never touch the
// real service manager.
package service

import (
	"bytes"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
)

// UnitName is the systemd unit file name.
const UnitName = "aristotle-server.service"

// Runner runs an external command (systemctl, journalctl). Output goes to
// the given writers.
type Runner interface {
	Run(stdout, stderr io.Writer, name string, args ...string) error
}

// ErrUnitDiffers means a different unit file already exists and force is off.
var ErrUnitDiffers = errors.New("a different unit file already exists")

// ErrUnitSymlink means the unit file is a symlink install will not rewrite.
var ErrUnitSymlink = errors.New("unit file is a symlink")

// ErrRestartFailed means the unit was installed and enabled but restarting the running service failed.
var ErrRestartFailed = errors.New("service restart failed")

// JournalCommand shows the service's recent log lines (what install.sh prints on a failed start, too).
const JournalCommand = "journalctl --user -u aristotle-server -n 50 --no-pager (or: aristotle-server logs)"

// UnitDir returns $XDG_CONFIG_HOME/systemd/user (default ~/.config/systemd/user).
func UnitDir() (string, error) {
	if x := os.Getenv("XDG_CONFIG_HOME"); x != "" {
		if !filepath.IsAbs(x) {
			return "", fmt.Errorf("XDG_CONFIG_HOME must be an absolute path, got %q", x)
		}
		return filepath.Join(x, "systemd", "user"), nil
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(home, ".config", "systemd", "user"), nil
}

// UnitText renders the unit file for the binary at bin.
// No Environment=HERMES_HOME (the connector finds the gateway itself) and no
// StateDirectory= (systemd would create 0755 dirs; the server enforces 0700).
// Delegate=yes hands the service its own cgroup subtree: each remote terminal
// runs in a child cgroup that is killed whole when it closes (T19a).
// No PrivateTmp/ProtectHome/ProtectSystem/PrivateDevices/... : in a user unit systemd implies a private user
// namespace for them, and then the server cannot read /proc/<pid>/environ of processes outside the unit, so
// terminal.Hub.InTerminal fails closed and refuses every control command from a PC terminal (TF1).
func UnitText(bin string) (string, error) {
	q, err := quoteExec(bin)
	if err != nil {
		return "", err
	}
	return "[Unit]\n" +
		"Description=Aristotle server (relay between the Aristotle app and Hermes)\n" +
		"\n[Service]\n" +
		"Type=simple\n" +
		"ExecStart=" + q + " serve\n" +
		"Restart=on-failure\n" +
		"RestartSec=5\n" +
		"NoNewPrivileges=yes\n" +
		"UMask=0077\n" +
		"Delegate=yes\n" +
		"\n[Install]\n" +
		"WantedBy=default.target\n", nil
}

// InstalledUnitDelegates reports whether the installed unit file says Delegate=yes (a unit installed
// before T19a does not; install --force replaces it). systemd's user manager does not mark delegated
// cgroups with an xattr, so the server learns it here.
func InstalledUnitDelegates() bool {
	dir, err := UnitDir()
	if err != nil {
		return false
	}
	data, err := os.ReadFile(filepath.Join(dir, UnitName))
	if err != nil {
		return false
	}
	for line := range strings.SplitSeq(string(data), "\n") {
		if strings.TrimSpace(line) == "Delegate=yes" {
			return true
		}
	}
	return false
}

// quoteExec makes a path safe as the ExecStart= program.
func quoteExec(p string) (string, error) {
	if !filepath.IsAbs(p) {
		return "", fmt.Errorf("binary path %q is not absolute", p)
	}
	if strings.ContainsAny(p, "\n\r\x00") {
		return "", errors.New("binary path contains a control character")
	}
	p = strings.ReplaceAll(p, "%", "%%")
	p = strings.ReplaceAll(p, "$", "$$")
	if strings.ContainsAny(p, " \t\"'\\;") {
		p = strings.ReplaceAll(p, `\`, `\\`)
		p = strings.ReplaceAll(p, `"`, `\"`)
		return `"` + p + `"`, nil
	}
	return p, nil
}

// Manager installs and removes the unit.
type Manager struct {
	Dir    string // unit directory
	Bin    string // absolute binary path
	Runner Runner
	Out    io.Writer
	Err    io.Writer

	// Restarted is set by Install when it restarted a running service (--force).
	Restarted bool
}

// UnitPath is the full path of the unit file.
func (m *Manager) UnitPath() string { return filepath.Join(m.Dir, UnitName) }

func (m *Manager) ctl(args ...string) error {
	return m.Runner.Run(m.Out, m.Err, "systemctl", append([]string{"--user"}, args...)...)
}

// Install writes the unit, reloads systemd, enables it and optionally starts it.
func (m *Manager) Install(force, start bool) error {
	want, err := UnitText(m.Bin)
	if err != nil {
		return err
	}
	path := m.UnitPath()
	m.Restarted = false
	linked := false
	if fi, lerr := os.Lstat(path); lerr == nil && fi.Mode()&os.ModeSymlink != 0 {
		linked = true
	}
	have, err := os.ReadFile(path)
	switch {
	case err == nil:
		if string(have) != want && !linked && !force {
			return fmt.Errorf("%w at %s (it points at another binary or was edited); rerun with --force to replace it", ErrUnitDiffers, path)
		}
		if linked && string(have) != want {
			return symlinkRefusal(path)
		}
	case !errors.Is(err, fs.ErrNotExist):
		return err
	case linked:
		return symlinkRefusal(path)
	}
	if linked {
		fmt.Fprintf(m.Out, "%s is a symlink with the right content; left as is\n", path)
	} else {
		if err := os.MkdirAll(m.Dir, 0o755); err != nil {
			return err
		}
		if err := writeAtomic(path, []byte(want)); err != nil {
			return err
		}
		fmt.Fprintf(m.Out, "wrote %s\n", path)
	}
	if err := m.ctl("daemon-reload"); err != nil {
		return fmt.Errorf("systemctl daemon-reload: %w", err)
	}
	if err := m.ctl("enable", UnitName); err != nil {
		return fmt.Errorf("systemctl enable: %w", err)
	}
	if force && m.State("is-active") == "active" {
		if err := m.ctl("restart", UnitName); err != nil {
			return restartFailure(path, linked, err)
		}
		m.Restarted = true
		return nil
	}
	if start {
		if err := m.ctl("start", UnitName); err != nil {
			return fmt.Errorf("systemctl start: %w", err)
		}
	}
	return nil
}

// restartFailure is the error for a failed restart after the unit was written (or kept) and enabled. A restart
// is stop then start, so the server is probably down now; the message says what changed and where to look.
func restartFailure(path string, linked bool, err error) error {
	unit := "unit " + path + " was updated"
	if linked {
		unit = "unit " + path + " (a symlink) was kept"
	}
	return fmt.Errorf("%w (systemctl --user restart: %v): the %s and enabled, but the server is likely down now. See: %s",
		ErrRestartFailed, err, unit, JournalCommand)
}

// symlinkRefusal is the error for a unit file that is a symlink whose content is not what install
// would write. Refusing is safer than writing through: the target is usually managed elsewhere
// (dotfiles repo, read-only store) and replacing the link with a file would silently detach it.
func symlinkRefusal(path string) error {
	target, err := filepath.EvalSymlinks(path)
	if err != nil {
		target, _ = os.Readlink(path)
	}
	return fmt.Errorf("%w: %s is a symlink to %s; not touching it. Edit the target (ExecStart=... serve) or remove the link, then rerun install", ErrUnitSymlink, path, target)
}

// Uninstall disables and stops the unit, removes the file and reloads. A
// missing unit file is not an error.
func (m *Manager) Uninstall() error {
	path := m.UnitPath()
	_, statErr := os.Stat(path)
	if err := m.ctl("disable", "--now", UnitName); err != nil && statErr == nil {
		return fmt.Errorf("systemctl disable: %w", err)
	}
	if err := os.Remove(path); err != nil && !errors.Is(err, fs.ErrNotExist) {
		return err
	}
	if err := m.ctl("daemon-reload"); err != nil {
		return fmt.Errorf("systemctl daemon-reload: %w", err)
	}
	return nil
}

// State returns the trimmed first line systemctl prints for is-active /
// is-enabled (these exit non-zero for "inactive"/"disabled", so the exit
// code is ignored and the text is what counts).
func (m *Manager) State(query string) string {
	var out bytes.Buffer
	_ = m.Runner.Run(&out, io.Discard, "systemctl", "--user", query, UnitName)
	s := strings.TrimSpace(strings.SplitN(out.String(), "\n", 2)[0])
	if s == "" {
		return "unknown"
	}
	return s
}

// Logs passes through to journalctl.
func (m *Manager) Logs(follow bool) error {
	args := []string{"--user", "-u", "aristotle-server"}
	if follow {
		args = append(args, "-f")
	}
	return m.Runner.Run(m.Out, m.Err, "journalctl", args...)
}

func writeAtomic(path string, data []byte) error {
	tmp, err := os.CreateTemp(filepath.Dir(path), ".aristotle-unit-*")
	if err != nil {
		return err
	}
	defer os.Remove(tmp.Name())
	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Chmod(0o644); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	return os.Rename(tmp.Name(), path)
}
