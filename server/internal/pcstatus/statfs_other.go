//go:build !linux

package pcstatus

func statfs(string) (free, total uint64, ok bool) { return 0, 0, false }
