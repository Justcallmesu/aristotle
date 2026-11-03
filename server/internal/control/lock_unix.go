//go:build linux || darwin || freebsd || netbsd || openbsd || dragonfly

package control

import (
	"errors"
	"fmt"
	"io"
	"os"
	"syscall"
)

// lockFile takes an exclusive, non-blocking flock on path (created 0600,
// never through a symlink). The lock is released when the returned file is
// closed or the process dies, so a crash never leaves it held.
func lockFile(path string) (io.Closer, error) {
	fd, err := syscall.Open(path, syscall.O_RDWR|syscall.O_CREAT|syscall.O_NOFOLLOW|syscall.O_CLOEXEC, 0o600)
	if err != nil {
		return nil, fmt.Errorf("control lock %s: %w", path, err)
	}
	f := os.NewFile(uintptr(fd), path)
	if err := syscall.Flock(fd, syscall.LOCK_EX|syscall.LOCK_NB); err != nil {
		f.Close()
		if errors.Is(err, syscall.EWOULDBLOCK) {
			return nil, errLockHeld
		}
		return nil, fmt.Errorf("control lock %s: %w", path, err)
	}
	return f, nil
}
