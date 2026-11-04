package terminal

import (
	"os"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"syscall"
	"time"
)

// markerEnv marks every terminal process (its value is the terminal id). It is one of two checks the
// control socket makes (hub.go InTerminal): a shell can unset it, so ancestry and cgroup count too.
const markerEnv = "ARISTOTLE_TERMINAL"

const (
	fallbackShell = "/bin/bash"
	// exitWait bounds the wait for the shell to be reaped after the final SIGKILL.
	exitWait = 2 * time.Second
)

// spawnSpec is one shell to start (sys_linux.go startShell).
type spawnSpec struct {
	path       string
	args       []string
	dir        string
	env        []string
	cols, rows int
	cgroup     *termCgroup // nil: no cgroup of its own
}

// shellProc is a started shell: the session leader on its PTY.
type shellProc struct {
	pid    int
	master *os.File
	cg     *termCgroup
	exited chan struct{} // closed once the shell is reaped
	code   int           // exit status, valid after exited
}

// killReport says how terminate ended the terminal's processes, for the log.
type killReport struct {
	method  string // "cgroup" or "session"
	killed  int    // processes that were still there for the final SIGKILL (cgroup: found in the cgroup)
	settled bool   // everything had ended before the kill (cgroup: the shell had)
}

// terminate ends every process of the terminal. The session gets SIGHUP (and SIGCONT, for stopped
// jobs) and the PTY master is closed; once the shell is gone, or after grace, the terminal's cgroup is
// killed with cgroup.kill, or without a cgroup every process still in the shell's session gets SIGKILL.
func (p *shellProc) terminate(grace time.Duration) killReport {
	signalSession(p.pid, syscall.SIGHUP)
	signalSession(p.pid, syscall.SIGCONT)
	_ = p.master.Close()
	rep := killReport{settled: p.waitSettled(grace)}
	if p.cg != nil {
		rep.method, rep.killed = "cgroup", p.cg.destroy()
	} else {
		rep.method, rep.killed = "session", signalSession(p.pid, syscall.SIGKILL)
	}
	select {
	case <-p.exited:
	case <-time.After(exitWait):
	}
	return rep
}

// waitSettled waits up to grace for the shell to exit and, without a cgroup, for its session to empty,
// and reports whether that happened.
func (p *shellProc) waitSettled(grace time.Duration) bool {
	deadline := time.Now().Add(grace)
	for {
		if isClosed(p.exited) && (p.cg != nil || len(sessionMembers(p.pid)) == 0) {
			return true
		}
		if !time.Now().Before(deadline) {
			return false
		}
		time.Sleep(20 * time.Millisecond)
	}
}

func isClosed(c <-chan struct{}) bool {
	select {
	case <-c:
		return true
	default:
		return false
	}
}

// droppedEnv are variables systemd sets for the service itself; the shell must not inherit them.
var droppedEnv = []string{
	"INVOCATION_ID", "JOURNAL_STREAM", "MANAGERPID", "NOTIFY_SOCKET", "SYSTEMD_EXEC_PID", "MAINPID",
	"CREDENTIALS_DIRECTORY", "RUNTIME_DIRECTORY", "STATE_DIRECTORY", "CACHE_DIRECTORY", "LOGS_DIRECTORY",
	"CONFIGURATION_DIRECTORY", "EXIT_CODE", "EXIT_STATUS", "SERVICE_RESULT",
	// set again below
	"TERM", "COLORTERM", "SHELL", "HOME", "PWD", "OLDPWD",
}

var droppedEnvPrefixes = []string{"LISTEN_", "WATCHDOG_", "TRIGGER_", "MONITOR_", "MEMORY_PRESSURE_", "ARISTOTLE_"}

// desktopEnv point at the user's live desktop session: the display, the session bus, the window
// manager's IPC socket, the SSH and GPG agents and the login seat. The shell drops them unless the
// server config keeps them (terminal.keep_env), so that a phone shell does not reach the desktop by
// default. It can still set them itself (the X and Wayland sockets and the bus are reachable to any
// process of the user): this is hygiene, not a boundary (docs/terminal.md, T19a review m1).
var desktopEnv = []string{
	"DISPLAY", "WAYLAND_DISPLAY", "XAUTHORITY", "DBUS_SESSION_BUS_ADDRESS",
	"SWAYSOCK", "HYPRLAND_INSTANCE_SIGNATURE", "I3SOCK",
	"SSH_AUTH_SOCK", "GPG_AGENT_INFO", "DESKTOP_SESSION", "XDG_CURRENT_DESKTOP",
}

var desktopEnvPrefixes = []string{"XDG_SESSION_", "XDG_SEAT"}

// shellEnv is the service's environment minus systemd's and aristotle's own variables and minus the
// desktop session's (except those named in keep), plus the terminal's: TERM, COLORTERM, SHELL, HOME,
// PWD and the ARISTOTLE_TERMINAL marker. keep never brings back a systemd or aristotle variable.
func shellEnv(base, keep []string, termID, shell, home string) []string {
	out := make([]string, 0, len(base)+7)
	for _, kv := range base {
		key, _, _ := strings.Cut(kv, "=")
		if listed(key, droppedEnv, droppedEnvPrefixes) ||
			(listed(key, desktopEnv, desktopEnvPrefixes) && !slices.Contains(keep, key)) {
			continue
		}
		out = append(out, kv)
	}
	return append(out, "TERM=xterm-256color", "COLORTERM=truecolor", "SHELL="+shell, "HOME="+home,
		"PWD="+home, markerEnv+"="+termID)
}

// listed reports whether key is one of names or starts with one of prefixes.
func listed(key string, names, prefixes []string) bool {
	return slices.Contains(names, key) || slices.ContainsFunc(prefixes, func(p string) bool {
		return strings.HasPrefix(key, p)
	})
}

// loginShell is $SHELL, else the user's shell in passwd, else /bin/bash: the first that is an
// absolute path to an executable file.
func loginShell(getenv func(string) string, passwd []byte, uid int) string {
	for _, sh := range []string{getenv("SHELL"), passwdShell(passwd, uid)} {
		if executable(sh) {
			return sh
		}
	}
	return fallbackShell
}

// passwdShell returns the shell field of uid's line in an /etc/passwd text.
func passwdShell(passwd []byte, uid int) string {
	want := strconv.Itoa(uid)
	for line := range strings.SplitSeq(string(passwd), "\n") {
		f := strings.Split(line, ":")
		if len(f) == 7 && f[2] == want {
			return f[6]
		}
	}
	return ""
}

func executable(path string) bool {
	if !filepath.IsAbs(path) {
		return false
	}
	info, err := os.Stat(path)
	return err == nil && info.Mode().IsRegular() && info.Mode().Perm()&0o111 != 0
}

// homeDir is the shell's working directory: $HOME when it is a directory, else "/".
func homeDir() string {
	if h, err := os.UserHomeDir(); err == nil {
		if info, err := os.Stat(h); err == nil && info.IsDir() {
			return h
		}
	}
	return "/"
}
