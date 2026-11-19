package service

import (
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
)

// The desktop app's programs (spec P24). `uninstall` stops them; like the server binary, the files stay.
var desktopPrograms = []string{"aristotle-tray", "aristotle-desktop"}

// DesktopFiles are the three files scripts/install.sh writes for the desktop app (P24, P8).
type DesktopFiles struct {
	Menu      string // $XDG_DATA_HOME/applications/aristotle-desktop.desktop
	Autostart string // $XDG_CONFIG_HOME/autostart/aristotle-tray.desktop
	Icon      string // $XDG_DATA_HOME/icons/hicolor/256x256/apps/aristotle-server.png
}

// DesktopPaths resolves the files from the XDG variables (an absolute path, else the home-relative default).
func DesktopPaths() (DesktopFiles, error) {
	data, err := xdgBase("XDG_DATA_HOME", filepath.Join(".local", "share"))
	if err != nil {
		return DesktopFiles{}, err
	}
	cfg, err := xdgBase("XDG_CONFIG_HOME", ".config")
	if err != nil {
		return DesktopFiles{}, err
	}
	return DesktopFiles{
		Menu:      filepath.Join(data, "applications", "aristotle-desktop.desktop"),
		Autostart: filepath.Join(cfg, "autostart", "aristotle-tray.desktop"),
		Icon:      filepath.Join(data, "icons", "hicolor", "256x256", "apps", "aristotle-server.png"),
	}, nil
}

func xdgBase(env, fallback string) (string, error) {
	// A relative value is ignored, as the XDG spec says and as scripts/install.sh does (P26).
	if x := os.Getenv(env); filepath.IsAbs(x) {
		return x, nil
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(home, fallback), nil
}

// RemoveDesktopFiles deletes the menu entry, the autostart entry and the icon. A file that is already gone is fine.
func RemoveDesktopFiles(files DesktopFiles, out io.Writer) error {
	var errs []error
	for _, p := range []string{files.Menu, files.Autostart, files.Icon} {
		err := os.Remove(p)
		switch {
		case err == nil:
			fmt.Fprintf(out, "Removed %s\n", p)
		case !errors.Is(err, fs.ErrNotExist):
			errs = append(errs, err)
		}
	}
	return errors.Join(errs...)
}

// Desktop finds and stops the desktop app's running processes. ProcRoot and Signal are seams for tests.
type Desktop struct {
	ProcRoot string          // "/proc"
	Dir      string          // the installer's BIN_DIR; only programs running from here are stopped (P25 (2))
	Signal   func(int) error // SIGTERM to a pid
}

// InstalledBinDir is where scripts/install.sh puts the programs: $HOME/.local/bin.
func InstalledBinDir() (string, error) {
	home, err := os.UserHomeDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(home, ".local", "bin"), nil
}

// NewDesktop is the real thing: /proc, the installed directory and SIGTERM. Without a home directory Dir stays empty,
// which matches nothing.
func NewDesktop() Desktop {
	dir, _ := InstalledBinDir()
	return Desktop{ProcRoot: "/proc", Dir: dir, Signal: func(pid int) error { return syscall.Kill(pid, syscall.SIGTERM) }}
}

// resolvedDir is dir with symlinks resolved, because /proc/<pid>/exe is a resolved path (a symlinked home or
// ~/.local/bin would never match otherwise). A directory that does not exist cannot hold a running program.
func resolvedDir(dir string) string {
	if dir == "" {
		return ""
	}
	if r, err := filepath.EvalSymlinks(dir); err == nil {
		return r
	}
	return filepath.Clean(dir)
}

// StopProcesses sends SIGTERM to every process whose resolved executable is aristotle-tray or aristotle-desktop in
// Dir, the installed directory: never a dev or preview copy elsewhere. It reads /proc/<pid>/exe, not the process
// name: the kernel cuts that to 15 characters, so `aristotle-desktop` (16) would never match by name. Another user's
// process has an unreadable exe and is skipped.
func (d Desktop) StopProcesses(out io.Writer) {
	dir := resolvedDir(d.Dir)
	if dir == "" {
		return
	}
	entries, err := os.ReadDir(d.ProcRoot)
	if err != nil {
		return
	}
	for _, e := range entries {
		pid, err := strconv.Atoi(e.Name())
		if err != nil {
			continue
		}
		exe, err := os.Readlink(filepath.Join(d.ProcRoot, e.Name(), "exe"))
		if err != nil {
			continue
		}
		exe = strings.TrimSuffix(exe, " (deleted)") // an update replaces the file under a running process
		name := filepath.Base(exe)
		if !isDesktopProgram(name) || filepath.Dir(exe) != dir {
			continue
		}
		if err := d.Signal(pid); err != nil {
			fmt.Fprintf(out, "Could not stop %s (pid %d): %v\n", name, pid, err)
			continue
		}
		fmt.Fprintf(out, "Stopped %s (pid %d)\n", name, pid)
	}
}

func isDesktopProgram(name string) bool {
	for _, p := range desktopPrograms {
		if name == p {
			return true
		}
	}
	return false
}

// KeptPrograms lists the desktop programs that exist next to dir's server binary (they stay, like the server binary).
func KeptPrograms(dir string) []string {
	var kept []string
	for _, p := range desktopPrograms {
		if _, err := os.Stat(filepath.Join(dir, p)); err == nil {
			kept = append(kept, p)
		}
	}
	return kept
}
