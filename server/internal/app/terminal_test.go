//go:build linux

package app

import (
	"context"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"

	"github.com/justcallmesu/aristotle/server/internal/listen"
	"github.com/justcallmesu/aristotle/server/internal/terminal"
)

func noIfaces() ([]listen.Iface, error) { return nil, nil }

// TestHelperCLI is not a test: the B2 tests run the test binary as the aristotle-server CLI in a
// child process (T19A_CLI_ARGS), so the control socket sees a real peer process.
func TestHelperCLI(t *testing.T) {
	args := os.Getenv("T19A_CLI_ARGS")
	if args == "" {
		t.Skip("helper process only")
	}
	os.Exit(Run(strings.Fields(args), "t", os.Stdout, os.Stderr))
}

// cliAs runs the CLI in a child process with extra environment.
func cliAs(t *testing.T, env []string, args string) (int, string) {
	t.Helper()
	cmd := exec.Command(os.Args[0], "-test.run=^TestHelperCLI$")
	cmd.Env = append(append(os.Environ(), "T19A_CLI_ARGS="+args), env...)
	out, err := cmd.CombinedOutput()
	code := 0
	if ee, ok := err.(*exec.ExitError); ok {
		code = ee.ExitCode()
	} else if err != nil {
		t.Fatal(err)
	}
	return code, string(out)
}

func TestTerminalCLI(t *testing.T) {
	srv, logs := startTestServer(t)
	dev, _, err := srv.devices.Add("pixel")
	if err != nil {
		t.Fatal(err)
	}
	expect := func(want int, contains string, args ...string) string {
		t.Helper()
		code, out, errOut := run(t, args...)
		if code != want || !strings.Contains(out+errOut, contains) {
			t.Fatalf("%v: exit %d, want %d with %q:\n%s%s", args, code, want, contains, out, errOut)
		}
		return out
	}
	expect(0, "Terminal: disabled", "terminal", "status")
	expect(2, "usage", "terminal", "on")
	expect(1, "no device with id dev_nope", "devices", "grant-terminal", "dev_nope")
	expect(0, "once the terminal is enabled", "devices", "grant-terminal", dev.ID)
	expect(0, "already holds", "devices", "grant-terminal", dev.ID)
	out := expect(0, "FULL access", "terminal", "enable")
	if !strings.Contains(out, dev.ID) || !strings.Contains(out, "pixel") {
		t.Fatalf("enable output lacks the grant:\n%s", out)
	}
	if err := srv.grants.Allowed(dev.ID); err != nil {
		t.Fatal(err)
	}
	expect(0, "Removed the terminal grant", "devices", "ungrant-terminal", dev.ID)
	expect(1, "has no terminal grant", "devices", "ungrant-terminal", dev.ID)
	expect(0, "Remote terminal disabled", "terminal", "disable")
	expect(0, "Granted devices: none", "terminal", "status")

	expect(0, "Granted", "devices", "grant-terminal", dev.ID)
	expect(0, "Revoked", "devices", "revoke", dev.ID)
	if srv.grants.Granted(dev.ID) {
		t.Fatal("revoke left the grant")
	}
	data, err := os.ReadFile(srv.stateDir + "/" + terminal.GrantsFile)
	if err != nil || strings.Contains(string(data), dev.ID) {
		t.Fatalf("terminal.json after revoke: %s %v", data, err)
	}
	if strings.Contains(logs.String(), "refused") {
		t.Fatalf("a PC-side command was refused:\n%s", logs.String())
	}
}

// B2: a process marked as a terminal process (ARISTOTLE_TERMINAL) may not pair, enable the terminal
// or grant it; it may still disable, ungrant, revoke and read status.
func TestControlRefusesEscalationFromATerminalProcess(t *testing.T) {
	srv, logs := startTestServer(t)
	dev, _, _ := srv.devices.Add("pixel")
	marked := []string{"ARISTOTLE_TERMINAL=term_0000"}
	for _, args := range []string{"pair", "terminal enable", "devices grant-terminal " + dev.ID} {
		code, out := cliAs(t, marked, args)
		if code != 1 || !strings.Contains(out, "refused") {
			t.Fatalf("%q from a terminal process: exit %d\n%s", args, code, out)
		}
	}
	if on, ids := srv.grants.Status(); on || len(ids) != 0 {
		t.Fatalf("state changed by refused commands: %v %v", on, ids)
	}
	if !strings.Contains(logs.String(), "control command refused from a remote terminal") ||
		!strings.Contains(logs.String(), "seen_by=env") {
		t.Fatalf("no refusal line:\n%s", logs.String())
	}
	changes := func(c bool, err error) {
		t.Helper()
		if err != nil || !c {
			t.Fatal(c, err)
		}
	}
	changes(srv.grants.SetEnabled(true))
	changes(srv.grants.Grant(dev.ID))
	for _, args := range []string{"terminal status", "devices ungrant-terminal " + dev.ID, "terminal disable", "devices list",
		"devices revoke " + dev.ID} {
		if code, out := cliAs(t, marked, args); code != 0 {
			t.Fatalf("%q from a terminal process: exit %d\n%s", args, code, out)
		}
	}
	// The same escalating command from an ordinary process works.
	if code, out := cliAs(t, nil, "terminal enable"); code != 0 {
		t.Fatalf("enable from the PC: exit %d\n%s", code, out)
	}
}

// B2 end to end: a phone's terminal runs the CLI with the marker removed; its ancestry still gives it
// away, and the pairing it asks for is refused.
func TestTerminalShellCannotPair(t *testing.T) {
	bin := os.Args[0]
	ts := startServer(t, noIfaces, "", func(s *server) {
		s.terms = terminal.New(terminal.Options{Grants: s.grants, Devices: s.devices, Log: s.log, NoCgroup: true,
			Command: func() (string, []string) {
				return "/bin/sh", []string{"sh", "-c", `env -u ARISTOTLE_TERMINAL T19A_CLI_ARGS=pair "$0" -test.run='^TestHelperCLI$'; echo rc=$?; sleep 30`, bin}
			}})
	})
	dev, token, _ := ts.srv.devices.Add("pixel")
	if _, err := ts.srv.grants.SetEnabled(true); err != nil {
		t.Fatal(err)
	}
	if _, err := ts.srv.grants.Grant(dev.ID); err != nil {
		t.Fatal(err)
	}
	api := httptest.NewServer(ts.srv.remoteHandler(listen.LAN))
	defer api.Close()

	req, _ := http.NewRequest("GET", api.URL+"/v1/me", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	var me strings.Builder
	buf := make([]byte, 512)
	n, _ := resp.Body.Read(buf)
	me.Write(buf[:n])
	resp.Body.Close()
	if !strings.Contains(me.String(), `"terminal":{"enabled":true,"granted":true}`) {
		t.Fatalf("/v1/me = %s", me.String())
	}

	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	c, _, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(api.URL, "http")+"/v1/terminal",
		&websocket.DialOptions{HTTPHeader: http.Header{"Authorization": {"Bearer " + token}}})
	if err != nil {
		t.Fatal(err)
	}
	defer c.CloseNow()
	var out strings.Builder
	for !strings.Contains(out.String(), "rc=") {
		_, msg, err := c.Read(ctx)
		if err != nil {
			t.Fatalf("%v; output so far:\n%s", err, out.String())
		}
		out.Write(msg)
	}
	if !strings.Contains(out.String(), "refused") || !strings.Contains(out.String(), "rc=1") {
		t.Fatalf("pair from inside the terminal was not refused:\n%s", out.String())
	}
	if !strings.Contains(ts.logs.String(), "seen_by=ancestry:term_") && !strings.Contains(ts.logs.String(), "seen_by=session:term_") {
		t.Fatalf("refusal not by ancestry:\n%s", ts.logs.String())
	}
	if strings.Contains(ts.logs.String(), "pairing code issued") {
		t.Fatal("a pairing code was issued")
	}
	c.Close(websocket.StatusNormalClosure, "")
}
