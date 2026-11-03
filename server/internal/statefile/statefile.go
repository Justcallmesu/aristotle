// Package statefile reads and writes private files inside the state dir:
// mode 0600, owned by the current user, never through a symlink, and
// replaced atomically (temp file + fsync + rename).
package statefile

import (
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"syscall"
)

// Mode is the permission every state file gets.
const Mode os.FileMode = 0o600

// WriteAtomic replaces dir/name with data. Readers see the old or the new
// content, never a partial file. A symlink at dir/name is replaced, not followed.
func WriteAtomic(dir, name string, data []byte) error {
	if err := checkName(name); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(dir, "."+name+".tmp-*") // CreateTemp uses 0600
	if err != nil {
		return fmt.Errorf("write %s: %w", name, err)
	}
	defer os.Remove(tmp.Name()) // no-op after a successful rename
	if err := writeAndSync(tmp, data); err != nil {
		return fmt.Errorf("write %s: %w", name, err)
	}
	if err := os.Rename(tmp.Name(), filepath.Join(dir, name)); err != nil {
		return fmt.Errorf("write %s: %w", name, err)
	}
	return syncDir(dir)
}

func writeAndSync(f *os.File, data []byte) error {
	_, werr := f.Write(data)
	serr := f.Sync()
	cerr := f.Close()
	return errors.Join(werr, serr, cerr)
}

func syncDir(dir string) error {
	d, err := os.Open(dir)
	if err != nil {
		return err
	}
	defer d.Close()
	return d.Sync()
}

// Read returns the content of dir/name. A missing file returns an error
// matching fs.ErrNotExist. A symlink, a non-regular file, a file owned by
// another user or one readable by group/other is refused.
func Read(dir, name string) ([]byte, error) {
	if err := checkName(name); err != nil {
		return nil, err
	}
	path := filepath.Join(dir, name)
	fd, err := syscall.Open(path, syscall.O_RDONLY|syscall.O_NOFOLLOW|syscall.O_CLOEXEC, 0)
	if errors.Is(err, syscall.ELOOP) {
		return nil, fmt.Errorf("%s is a symlink: refused", path)
	}
	if err != nil {
		return nil, &os.PathError{Op: "open", Path: path, Err: err}
	}
	f := os.NewFile(uintptr(fd), path)
	defer f.Close()
	info, err := f.Stat()
	if err != nil {
		return nil, err
	}
	if err := checkPrivate(path, info); err != nil {
		return nil, err
	}
	return io.ReadAll(f)
}

func checkPrivate(path string, info os.FileInfo) error {
	if !info.Mode().IsRegular() {
		return fmt.Errorf("%s is not a regular file: refused", path)
	}
	if st, ok := info.Sys().(*syscall.Stat_t); ok && int(st.Uid) != os.Getuid() {
		return fmt.Errorf("%s is owned by uid %d, not the current user: refused", path, st.Uid)
	}
	if perm := info.Mode().Perm(); perm&0o077 != 0 {
		return fmt.Errorf("%s has mode %v, want %v: refused (chmod 600 it)", path, perm, Mode)
	}
	return nil
}

func checkName(name string) error {
	if name == "" || name == "." || name == ".." || filepath.Base(name) != name {
		return fmt.Errorf("state file name %q must be a plain file name", name)
	}
	return nil
}
