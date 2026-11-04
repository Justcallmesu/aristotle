//go:build !linux

package terminal

import (
	"errors"
	"os"
	"syscall"
)

// The terminal needs Linux (PTY ioctls, /proc, cgroup v2). Elsewhere no shell starts, and every
// process check fails closed.

var errUnsupported = errors.New("the terminal is only supported on linux")

type cgroupParent struct{}

type termCgroup struct{ fd int }

func detectCgroup(string) (*cgroupParent, string)          { return nil, "not linux" }
func (p *cgroupParent) create(string) (*termCgroup, error) { return nil, errUnsupported }
func (p *cgroupParent) holds(int) (bool, error)            { return false, errUnsupported }
func (p *cgroupParent) sweep() int                         { return 0 }
func (c *termCgroup) closeFD()                             {}
func (c *termCgroup) destroy() int                         { return 0 }

type procInfo struct {
	ppid, sid int
	zombie    bool
}

func startShell(spawnSpec) (*shellProc, error) { return nil, errUnsupported }
func setWinsize(*os.File, int, int) error      { return errUnsupported }
func readProcStat(int) (procInfo, error)       { return procInfo{}, errUnsupported }
func sessionMembers(int) []int                 { return nil }
func signalSession(int, syscall.Signal) int    { return 0 }
func envMarker(int) (string, bool, error)      { return "", false, errUnsupported }
func procCgroup(int) (string, error)           { return "", errUnsupported }
