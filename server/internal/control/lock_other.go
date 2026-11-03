//go:build !(linux || darwin || freebsd || netbsd || openbsd || dragonfly)

package control

import "io"

type noLock struct{}

func (noLock) Close() error { return nil }

// lockFile is a no-op where flock is unavailable; the live-socket check in
// removeStale is then the only guard against a second server.
func lockFile(string) (io.Closer, error) { return noLock{}, nil }
