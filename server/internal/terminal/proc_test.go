package terminal

import (
	"slices"
	"strings"
	"testing"
)

func TestShellEnv(t *testing.T) {
	base := []string{"PATH=/usr/bin", "HOME=/old", "INVOCATION_ID=abc", "JOURNAL_STREAM=8:9",
		"MANAGERPID=12", "NOTIFY_SOCKET=/run/n", "LISTEN_FDS=1", "ARISTOTLE_DEBUG=1", "ARISTOTLE_TERMINAL=forged",
		"TERM=dumb", "MEMORY_PRESSURE_WATCH=/x", "XDG_RUNTIME_DIR=/run/user/1000", "LANG=C.UTF-8"}
	got := shellEnv(base, nil, "term_1", "/bin/zsh", "/home/u")
	for _, gone := range []string{"INVOCATION_ID", "JOURNAL_STREAM", "MANAGERPID", "NOTIFY_SOCKET", "LISTEN_FDS",
		"ARISTOTLE_DEBUG", "MEMORY_PRESSURE_WATCH", "HOME=/old", "TERM=dumb", "ARISTOTLE_TERMINAL=forged"} {
		if slices.ContainsFunc(got, func(kv string) bool { return strings.HasPrefix(kv, gone) }) {
			t.Errorf("%s kept: %v", gone, got)
		}
	}
	for _, want := range []string{"PATH=/usr/bin", "XDG_RUNTIME_DIR=/run/user/1000", "LANG=C.UTF-8",
		"TERM=xterm-256color", "COLORTERM=truecolor", "SHELL=/bin/zsh", "HOME=/home/u", "PWD=/home/u", "ARISTOTLE_TERMINAL=term_1"} {
		if !slices.Contains(got, want) {
			t.Errorf("missing %s: %v", want, got)
		}
	}
}

// desktopSession is what a systemd user manager's environment carries on a graphical login (the
// service inherits it): the display, the bus, the compositor and agents, the seat.
var desktopSession = []string{
	"DISPLAY=:0", "WAYLAND_DISPLAY=wayland-1", "XAUTHORITY=/run/user/1000/xauth_x", "DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/1000/bus",
	"XDG_SESSION_ID=2", "XDG_SESSION_TYPE=wayland", "XDG_SESSION_CLASS=user", "XDG_SESSION_DESKTOP=sway",
	"XDG_SEAT=seat0", "XDG_SEAT_PATH=/org/freedesktop/DisplayManager/Seat0", "SWAYSOCK=/run/user/1000/sway-ipc.sock",
	"HYPRLAND_INSTANCE_SIGNATURE=abc", "I3SOCK=/run/user/1000/i3/ipc", "NOTIFY_SOCKET=/run/systemd/notify",
	"SSH_AUTH_SOCK=/run/user/1000/ssh-agent.socket", "GPG_AGENT_INFO=/run/user/1000/gnupg/S.gpg-agent:0:1",
	"DESKTOP_SESSION=sway", "XDG_CURRENT_DESKTOP=sway",
}

// T19a review m1: no variable that leads to the live desktop session reaches the shell by default.
func TestShellEnvScrubsTheDesktopSession(t *testing.T) {
	base := append([]string{"PATH=/usr/bin", "XDG_RUNTIME_DIR=/run/user/1000", "XDG_CONFIG_HOME=/home/u/.config",
		"XDG_DATA_DIRS=/usr/share"}, desktopSession...)
	got := shellEnv(base, nil, "term_1", "/bin/sh", "/home/u")
	for _, kv := range desktopSession {
		if slices.Contains(got, kv) {
			t.Errorf("%s reaches the shell", kv)
		}
	}
	for _, want := range []string{"PATH=/usr/bin", "XDG_RUNTIME_DIR=/run/user/1000", "XDG_CONFIG_HOME=/home/u/.config",
		"XDG_DATA_DIRS=/usr/share"} {
		if !slices.Contains(got, want) {
			t.Errorf("%s dropped: %v", want, got)
		}
	}
}

// terminal.keep_env brings back the desktop variables it names, never a systemd or aristotle one.
func TestShellEnvKeepsWhatTheConfigNames(t *testing.T) {
	base := append([]string{"ARISTOTLE_DEBUG=1", "INVOCATION_ID=abc"}, desktopSession...)
	got := shellEnv(base, []string{"SSH_AUTH_SOCK", "XDG_SESSION_TYPE", "NOTIFY_SOCKET", "ARISTOTLE_DEBUG", "INVOCATION_ID"},
		"term_1", "/bin/sh", "/home/u")
	for _, want := range []string{"SSH_AUTH_SOCK=/run/user/1000/ssh-agent.socket", "XDG_SESSION_TYPE=wayland"} {
		if !slices.Contains(got, want) {
			t.Errorf("kept %s missing: %v", want, got)
		}
	}
	for _, gone := range []string{"DISPLAY=", "XDG_SESSION_ID=", "NOTIFY_SOCKET=", "ARISTOTLE_DEBUG=", "INVOCATION_ID="} {
		if slices.ContainsFunc(got, func(kv string) bool { return strings.HasPrefix(kv, gone) }) {
			t.Errorf("%s kept: %v", gone, got)
		}
	}
}

func TestLoginShell(t *testing.T) {
	passwd := []byte("root:x:0:0::/root:/bin/sh\nu:x:1000:1000::/home/u:/bin/sh\n")
	env := func(v string) func(string) string { return func(string) string { return v } }
	if got := loginShell(env("/bin/sh"), nil, 1000); got != "/bin/sh" {
		t.Fatalf("$SHELL: %s", got)
	}
	if got := loginShell(env("/no/such/shell"), passwd, 1000); got != "/bin/sh" {
		t.Fatalf("passwd: %s", got)
	}
	if got := loginShell(env("relative"), []byte("u:x:1000:1000::/home/u:/nope\n"), 1000); got != fallbackShell {
		t.Fatalf("fallback: %s", got)
	}
	if got := passwdShell(passwd, 4242); got != "" {
		t.Fatalf("unknown uid: %q", got)
	}
}
