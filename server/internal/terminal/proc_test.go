package terminal

import (
	"slices"
	"strings"
	"testing"
)

func TestShellEnv(t *testing.T) {
	base := []string{"PATH=/usr/bin", "HOME=/old", "SSH_AUTH_SOCK=/run/ssh", "INVOCATION_ID=abc", "JOURNAL_STREAM=8:9",
		"MANAGERPID=12", "NOTIFY_SOCKET=/run/n", "LISTEN_FDS=1", "ARISTOTLE_DEBUG=1", "ARISTOTLE_TERMINAL=forged",
		"TERM=dumb", "MEMORY_PRESSURE_WATCH=/x", "XDG_RUNTIME_DIR=/run/user/1000"}
	got := shellEnv(base, "term_1", "/bin/zsh", "/home/u")
	for _, gone := range []string{"INVOCATION_ID", "JOURNAL_STREAM", "MANAGERPID", "NOTIFY_SOCKET", "LISTEN_FDS",
		"ARISTOTLE_DEBUG", "MEMORY_PRESSURE_WATCH", "HOME=/old", "TERM=dumb", "ARISTOTLE_TERMINAL=forged"} {
		if slices.ContainsFunc(got, func(kv string) bool { return strings.HasPrefix(kv, gone) }) {
			t.Errorf("%s kept: %v", gone, got)
		}
	}
	for _, want := range []string{"PATH=/usr/bin", "SSH_AUTH_SOCK=/run/ssh", "XDG_RUNTIME_DIR=/run/user/1000",
		"TERM=xterm-256color", "COLORTERM=truecolor", "SHELL=/bin/zsh", "HOME=/home/u", "PWD=/home/u", "ARISTOTLE_TERMINAL=term_1"} {
		if !slices.Contains(got, want) {
			t.Errorf("missing %s: %v", want, got)
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
