package service

import (
	"os"
	"path/filepath"
	"testing"
)

func TestStartStopRestartRunSystemctlUser(t *testing.T) {
	cases := map[string]func(*Manager) error{
		"start": (*Manager).Start, "stop": (*Manager).Stop, "restart": (*Manager).Restart,
	}
	for verb, call := range cases {
		m, f := newMgr(t, "/opt/aristotle-server")
		if err := call(m); err != nil {
			t.Fatal(err)
		}
		if got := f.list(); len(got) != 1 || got[0] != "systemctl --user "+verb+" "+UnitName {
			t.Errorf("%s ran %v", verb, got)
		}
	}
}

func TestStopReturnsTheRunnerError(t *testing.T) {
	m, f := newMgr(t, "/opt/aristotle-server")
	f.fail = map[string]bool{"--user stop " + UnitName: true}
	if err := m.Stop(); err == nil {
		t.Fatal("no error")
	}
}

func TestInRemoteTerminal(t *testing.T) {
	dir := t.TempDir()
	write := func(name, text string) string {
		p := filepath.Join(dir, name)
		if err := os.WriteFile(p, []byte(text), 0o644); err != nil {
			t.Fatal(err)
		}
		return p
	}
	plain := write("plain", "0::/user.slice/user-1000.slice/session-2.scope\n")
	inUnit := write("unit", "0::/user.slice/user-1000.slice/user@1000.service/app.slice/aristotle-server.service\n")
	none := func(string) string { return "" }
	marked := func(k string) string {
		if k == "MARKER_X" {
			return "term_1"
		}
		return ""
	}
	cases := []struct {
		name   string
		env    func(string) string
		cgroup string
		want   bool
	}{
		{"clean", none, plain, false},
		{"env marker", marked, plain, true},
		{"cgroup names the unit", none, inUnit, true},
		{"no cgroup file", none, filepath.Join(dir, "missing"), false},
		{"marker without cgroup file", marked, filepath.Join(dir, "missing"), true},
	}
	for _, c := range cases {
		if got := InRemoteTerminal("MARKER_X", c.env, c.cgroup); got != c.want {
			t.Errorf("%s: %v, want %v", c.name, got, c.want)
		}
	}
}

// The marker name is the caller's: a variable with another name does not count.
func TestInRemoteTerminalUsesTheGivenMarkerName(t *testing.T) {
	env := func(k string) string {
		if k == "MARKER_X" {
			return "t1"
		}
		return ""
	}
	missing := filepath.Join(t.TempDir(), "missing")
	if !InRemoteTerminal("MARKER_X", env, missing) || InRemoteTerminal("MARKER_Y", env, missing) {
		t.Error("the check does not follow the marker name it is given")
	}
}
