package statefile

import (
	"errors"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
)

func TestWriteAtomicReplacesWith0600(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "f.json")
	if err := os.WriteFile(p, []byte("old"), 0o644); err != nil {
		t.Fatal(err)
	}
	before := inode(t, p)
	if err := WriteAtomic(dir, "f.json", []byte("new")); err != nil {
		t.Fatal(err)
	}
	info, err := os.Stat(p)
	if err != nil {
		t.Fatal(err)
	}
	if info.Mode().Perm() != Mode {
		t.Fatalf("mode = %v", info.Mode().Perm())
	}
	if inode(t, p) == before {
		t.Fatal("file was rewritten in place, want rename of a new file")
	}
	got, err := Read(dir, "f.json")
	if err != nil || string(got) != "new" {
		t.Fatalf("read = %q, %v", got, err)
	}
	assertNoTemps(t, dir)
}

func TestWriteAtomicFailureLeavesNoTempAndOtherFilesIntact(t *testing.T) {
	dir := t.TempDir()
	if err := WriteAtomic(dir, "f.json", []byte("old")); err != nil {
		t.Fatal(err)
	}
	// A non-empty directory in place of the target makes the rename fail.
	if err := os.Mkdir(filepath.Join(dir, "sub"), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "sub", "keep"), nil, 0o600); err != nil {
		t.Fatal(err)
	}
	if err := WriteAtomic(dir, "sub", []byte("new")); err == nil {
		t.Fatal("want rename error")
	}
	assertNoTemps(t, dir)
	if got, _ := Read(dir, "f.json"); string(got) != "old" {
		t.Fatalf("old file changed: %q", got)
	}
}

func TestWriteAtomicReplacesSymlinkWithoutFollowing(t *testing.T) {
	dir := t.TempDir()
	outside := filepath.Join(t.TempDir(), "target")
	if err := os.WriteFile(outside, []byte("outside"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(outside, filepath.Join(dir, "f.json")); err != nil {
		t.Fatal(err)
	}
	if err := WriteAtomic(dir, "f.json", []byte("new")); err != nil {
		t.Fatal(err)
	}
	if got, _ := os.ReadFile(outside); string(got) != "outside" {
		t.Fatalf("write followed the symlink: %q", got)
	}
}

func TestReadRefuses(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "loose"), nil, 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(filepath.Join(dir, "loose"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "real"), nil, 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(filepath.Join(dir, "real"), filepath.Join(dir, "link")); err != nil {
		t.Fatal(err)
	}
	for name, want := range map[string]string{
		"loose": "want -rw-------",
		"link":  "symlink",
		"../x":  "plain file name",
	} {
		if _, err := Read(dir, name); err == nil || !strings.Contains(err.Error(), want) {
			t.Errorf("%s: err = %v, want %q", name, err, want)
		}
	}
	if _, err := Read(dir, "missing"); !errors.Is(err, fs.ErrNotExist) {
		t.Errorf("missing: err = %v, want ErrNotExist", err)
	}
}

func TestCheckPrivateOtherOwner(t *testing.T) {
	info := fakeInfo{sys: &syscall.Stat_t{Uid: uint32(os.Getuid()) + 1}}
	if err := checkPrivate("/s/f", info); err == nil || !strings.Contains(err.Error(), "owned by uid") {
		t.Fatalf("err = %v", err)
	}
}

type fakeInfo struct {
	os.FileInfo
	sys any
}

func (f fakeInfo) Mode() fs.FileMode { return 0o600 }
func (f fakeInfo) Sys() any          { return f.sys }

func inode(t *testing.T, p string) uint64 {
	t.Helper()
	info, err := os.Stat(p)
	if err != nil {
		t.Fatal(err)
	}
	return info.Sys().(*syscall.Stat_t).Ino
}

func assertNoTemps(t *testing.T, dir string) {
	t.Helper()
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	for _, e := range entries {
		if strings.Contains(e.Name(), ".tmp-") {
			t.Fatalf("temp file left behind: %s", e.Name())
		}
	}
}
