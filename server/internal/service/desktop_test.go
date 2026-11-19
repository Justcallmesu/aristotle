package service

import (
	"bytes"
	"os"
	"path/filepath"
	"reflect"
	"sort"
	"strconv"
	"testing"
)

func TestDesktopPathsFollowXDGWithFallbacks(t *testing.T) {
	data, cfg := t.TempDir(), t.TempDir()
	t.Setenv("XDG_DATA_HOME", data)
	t.Setenv("XDG_CONFIG_HOME", cfg)
	got, err := DesktopPaths()
	if err != nil {
		t.Fatal(err)
	}
	want := DesktopFiles{
		Menu:      filepath.Join(data, "applications", "aristotle-desktop.desktop"),
		Autostart: filepath.Join(cfg, "autostart", "aristotle-tray.desktop"),
		Icon:      filepath.Join(data, "icons", "hicolor", "256x256", "apps", "aristotle-server.png"),
	}
	if got != want {
		t.Fatalf("got %+v, want %+v", got, want)
	}

	home := t.TempDir()
	t.Setenv("HOME", home)
	t.Setenv("XDG_DATA_HOME", "")
	t.Setenv("XDG_CONFIG_HOME", "")
	got, err = DesktopPaths()
	if err != nil {
		t.Fatal(err)
	}
	if got.Menu != filepath.Join(home, ".local", "share", "applications", "aristotle-desktop.desktop") ||
		got.Autostart != filepath.Join(home, ".config", "autostart", "aristotle-tray.desktop") {
		t.Fatalf("fallbacks wrong: %+v", got)
	}

	// A relative value is ignored, as the XDG spec says and as scripts/install.sh does (P26): the default is used.
	t.Setenv("XDG_DATA_HOME", "relative/dir")
	t.Setenv("XDG_CONFIG_HOME", "also/relative")
	got, err = DesktopPaths()
	if err != nil {
		t.Fatalf("a relative XDG variable must fall back to the default, got %v", err)
	}
	if got.Menu != filepath.Join(home, ".local", "share", "applications", "aristotle-desktop.desktop") ||
		got.Autostart != filepath.Join(home, ".config", "autostart", "aristotle-tray.desktop") {
		t.Fatalf("relative XDG fallbacks wrong: %+v", got)
	}
}

// fakeProc builds a /proc look-alike: one directory per pid with an `exe` symlink to the given target.
func fakeProc(t *testing.T, exes map[int]string) string {
	t.Helper()
	root := t.TempDir()
	for pid, target := range exes {
		dir := filepath.Join(root, strconv.Itoa(pid))
		if err := os.MkdirAll(dir, 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.Symlink(target, filepath.Join(dir, "exe")); err != nil {
			t.Fatal(err)
		}
	}
	// Entries that are not processes, and a process whose exe cannot be read (another user's).
	os.WriteFile(filepath.Join(root, "uptime"), []byte("1"), 0o644)
	os.MkdirAll(filepath.Join(root, "999"), 0o755)
	return root
}

func stopped(t *testing.T, root, dir string) []int {
	t.Helper()
	var signalled []int
	d := Desktop{ProcRoot: root, Dir: dir, Signal: func(pid int) error { signalled = append(signalled, pid); return nil }}
	var out bytes.Buffer
	d.StopProcesses(&out)
	sort.Ints(signalled)
	return signalled
}

func TestStopDesktopProcessesMatchesTheExecutableNameExactly(t *testing.T) {
	root := fakeProc(t, map[int]string{
		101: "/home/u/.local/bin/aristotle-tray",
		102: "/home/u/.local/bin/aristotle-desktop (deleted)", // replaced by an update while running
		103: "/usr/bin/sleep",
		104: "/home/u/.local/bin/aristotle-tray-old",
		105: "/home/u/.local/bin/aristotle-server",
		106: "/tmp/not-aristotle-desktop",
	})
	if got := stopped(t, root, "/home/u/.local/bin"); !reflect.DeepEqual(got, []int{101, 102}) {
		t.Fatalf("signalled %v, want [101 102]", got)
	}
}

// A dev or preview copy of the tray, in any other directory, is never stopped (P25 (2)).
func TestStopDesktopProcessesIgnoresCopiesInOtherDirectories(t *testing.T) {
	root := fakeProc(t, map[int]string{
		201: "/home/u/.local/bin/aristotle-tray",
		202: "/home/u/.cache/scratch/preview/aristotle-tray",
		203: "/home/u/project/Aristotle/desktop/target/release/aristotle-desktop",
		204: "/home/u/.local/bin/sub/aristotle-tray",
		205: "/home/u/.local/bin2/aristotle-desktop (deleted)",
	})
	if got := stopped(t, root, "/home/u/.local/bin"); !reflect.DeepEqual(got, []int{201}) {
		t.Fatalf("signalled %v, want only [201]", got)
	}
	if got := stopped(t, root, ""); len(got) != 0 {
		t.Fatalf("an empty install dir must match nothing, signalled %v", got)
	}
}

// /proc/<pid>/exe is the resolved path: a symlinked ~/.local/bin (or home) must still match (M2).
func TestStopDesktopProcessesResolvesASymlinkedInstallDir(t *testing.T) {
	base := t.TempDir()
	real := filepath.Join(base, "real", "bin")
	if err := os.MkdirAll(real, 0o755); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(base, "link")
	if err := os.Symlink(filepath.Join(base, "real"), link); err != nil {
		t.Fatal(err)
	}
	root := fakeProc(t, map[int]string{
		301: filepath.Join(real, "aristotle-tray"),
		302: filepath.Join(base, "elsewhere", "aristotle-tray"),
	})
	if got := stopped(t, root, filepath.Join(link, "bin")); !reflect.DeepEqual(got, []int{301}) {
		t.Fatalf("signalled %v, want [301]", got)
	}
}

func TestNewDesktopUsesTheInstallersBinDir(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)
	if d := NewDesktop(); d.Dir != filepath.Join(home, ".local", "bin") {
		t.Fatalf("Dir %q, want $HOME/.local/bin", d.Dir)
	}
}

func TestRemoveDesktopFilesRemovesOnlyTheThreeFilesAndToleratesMissingOnes(t *testing.T) {
	data, cfg := t.TempDir(), t.TempDir()
	t.Setenv("XDG_DATA_HOME", data)
	t.Setenv("XDG_CONFIG_HOME", cfg)
	files, _ := DesktopPaths()
	other := filepath.Join(filepath.Dir(files.Menu), "other.desktop")
	for _, p := range []string{files.Menu, files.Icon, other} { // the autostart entry is already gone
		os.MkdirAll(filepath.Dir(p), 0o755)
		os.WriteFile(p, []byte("x"), 0o644)
	}
	var out bytes.Buffer
	if err := RemoveDesktopFiles(files, &out); err != nil {
		t.Fatal(err)
	}
	for _, p := range []string{files.Menu, files.Icon} {
		if _, err := os.Stat(p); !os.IsNotExist(err) {
			t.Fatalf("%s remains", p)
		}
	}
	if _, err := os.Stat(other); err != nil {
		t.Fatal("another entry was removed")
	}
}
