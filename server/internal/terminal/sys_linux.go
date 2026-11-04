//go:build linux

package terminal

import (
	"bytes"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"syscall"
	"unsafe"
)

// The PTY uses /dev/ptmx and three ioctls from package syscall; no third-party PTY library.

// openPTY returns a new pseudo-terminal pair. Both ends are close-on-exec; the master is non-blocking
// (Go's poller), so Close unblocks a Read or Write in flight.
func openPTY() (master, slave *os.File, err error) {
	master, err = os.OpenFile("/dev/ptmx", os.O_RDWR|syscall.O_NOCTTY, 0)
	if err != nil {
		return nil, nil, err
	}
	var unlock int32
	var n uint32
	if err = ioctl(master, syscall.TIOCSPTLCK, unsafe.Pointer(&unlock)); err == nil {
		err = ioctl(master, syscall.TIOCGPTN, unsafe.Pointer(&n))
	}
	if err == nil {
		slave, err = os.OpenFile("/dev/pts/"+strconv.FormatUint(uint64(n), 10), os.O_RDWR|syscall.O_NOCTTY, 0)
	}
	if err != nil {
		master.Close()
		return nil, nil, fmt.Errorf("pty: %w", err)
	}
	return master, slave, nil
}

type winsize struct{ rows, cols, x, y uint16 }

// setWinsize sets the terminal size; the kernel sends SIGWINCH to the foreground process group.
func setWinsize(master *os.File, cols, rows int) error {
	ws := winsize{rows: uint16(rows), cols: uint16(cols)}
	return ioctl(master, syscall.TIOCSWINSZ, unsafe.Pointer(&ws))
}

// ioctl runs one ioctl through SyscallConn, which (unlike File.Fd) keeps the descriptor non-blocking.
func ioctl(f *os.File, req uintptr, arg unsafe.Pointer) error {
	rc, err := f.SyscallConn()
	if err != nil {
		return err
	}
	var errno syscall.Errno
	if err := rc.Control(func(fd uintptr) {
		_, _, errno = syscall.Syscall(syscall.SYS_IOCTL, fd, req, uintptr(arg))
	}); err != nil {
		return err
	}
	if errno != 0 {
		return errno
	}
	return nil
}

// startShell starts spec's program on a new PTY as the leader of a new session with the PTY as its
// controlling terminal, inside spec.cgroup when given (clone3 CLONE_INTO_CGROUP: it never runs outside it).
func startShell(spec spawnSpec) (*shellProc, error) {
	master, slave, err := openPTY()
	if err != nil {
		return nil, err
	}
	defer slave.Close()
	if err := setWinsize(master, spec.cols, spec.rows); err != nil {
		master.Close()
		return nil, fmt.Errorf("pty size: %w", err)
	}
	attr := &syscall.SysProcAttr{Setsid: true, Setctty: true, Ctty: 0}
	if spec.cgroup != nil {
		attr.UseCgroupFD, attr.CgroupFD = true, spec.cgroup.fd
	}
	cmd := &exec.Cmd{Path: spec.path, Args: spec.args, Dir: spec.dir, Env: spec.env,
		Stdin: slave, Stdout: slave, Stderr: slave, SysProcAttr: attr}
	if err := cmd.Start(); err != nil {
		master.Close()
		return nil, err
	}
	p := &shellProc{pid: cmd.Process.Pid, master: master, cg: spec.cgroup, exited: make(chan struct{})}
	go func() {
		_ = cmd.Wait()
		p.code = exitCode(cmd.ProcessState)
		close(p.exited)
	}()
	return p, nil
}

// exitCode is the shell's exit status, or 128+signal when a signal ended it (as shells report it).
func exitCode(ps *os.ProcessState) int {
	if ps == nil {
		return -1
	}
	if ws, ok := ps.Sys().(syscall.WaitStatus); ok && ws.Signaled() {
		return 128 + int(ws.Signal())
	}
	return ps.ExitCode()
}

// procInfo is what /proc/<pid>/stat says about a process.
type procInfo struct {
	ppid, sid int
	zombie    bool
}

func readProcStat(pid int) (procInfo, error) {
	b, err := os.ReadFile("/proc/" + strconv.Itoa(pid) + "/stat")
	if err != nil {
		return procInfo{}, err
	}
	// "pid (comm) state ppid pgrp session ...": comm may hold spaces and parentheses.
	i := bytes.LastIndexByte(b, ')')
	if i < 0 || i+2 > len(b) {
		return procInfo{}, errors.New("bad stat")
	}
	f := strings.Fields(string(b[i+2:]))
	if len(f) < 4 {
		return procInfo{}, errors.New("bad stat")
	}
	ppid, err1 := strconv.Atoi(f[1])
	sid, err2 := strconv.Atoi(f[3])
	if err1 != nil || err2 != nil {
		return procInfo{}, errors.New("bad stat")
	}
	return procInfo{ppid: ppid, sid: sid, zombie: f[0] == "Z"}, nil
}

// sessionMembers lists the live (non-zombie) processes of session sid.
func sessionMembers(sid int) []int {
	entries, err := os.ReadDir("/proc")
	if err != nil {
		return nil
	}
	var out []int
	for _, e := range entries {
		pid, err := strconv.Atoi(e.Name())
		if err != nil {
			continue
		}
		if info, err := readProcStat(pid); err == nil && info.sid == sid && !info.zombie {
			out = append(out, pid)
		}
	}
	return out
}

// signalSession sends sig to the shell's process group and to every process still in its session
// (job-control jobs have their own groups; nohup and disown keep the session). It returns how many
// session members it signalled. Processes that left the session (setsid, daemons) are not reached.
func signalSession(sid int, sig syscall.Signal) int {
	members := sessionMembers(sid) // before the group signal, which may already end some of them
	_ = syscall.Kill(-sid, sig)
	for _, pid := range members {
		_ = syscall.Kill(pid, sig)
	}
	return len(members)
}

// envMarker returns the value of ARISTOTLE_TERMINAL in the environment pid was started with.
func envMarker(pid int) (string, bool, error) {
	b, err := os.ReadFile("/proc/" + strconv.Itoa(pid) + "/environ")
	if err != nil {
		return "", false, err
	}
	for kv := range bytes.SplitSeq(b, []byte{0}) {
		if v, ok := bytes.CutPrefix(kv, []byte(markerEnv+"=")); ok {
			return string(v), true, nil
		}
	}
	return "", false, nil
}

// procCgroup returns pid's cgroup v2 path ("/user.slice/…"); an error without a v2 entry.
func procCgroup(pid int) (string, error) {
	b, err := os.ReadFile("/proc/" + strconv.Itoa(pid) + "/cgroup")
	if err != nil {
		return "", err
	}
	for line := range strings.SplitSeq(string(b), "\n") {
		if p, ok := strings.CutPrefix(line, "0::"); ok {
			return p, nil
		}
	}
	return "", errors.New("no cgroup v2 entry")
}
