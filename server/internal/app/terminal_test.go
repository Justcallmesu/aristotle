//go:build linux

package app

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
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

// T19a review m3: from a terminal process only the allowlisted commands run (status, devices list,
// terminal status, disable, ungrant, revoke); every other command is refused, including one added later.
func TestControlFromATerminalIsAnAllowlist(t *testing.T) {
	srv, logs := startTestServer(t)
	child := exec.Command("sleep", "30")
	child.Env = append(os.Environ(), "ARISTOTLE_TERMINAL=term_0000")
	if err := child.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = child.Process.Kill(); _ = child.Wait() })
	environ := fmt.Sprintf("/proc/%d/environ", child.Process.Pid)
	for deadline := time.Now().Add(5 * time.Second); ; time.Sleep(5 * time.Millisecond) { // execve may still be setting it up
		if b, _ := os.ReadFile(environ); strings.Contains(string(b), "ARISTOTLE_TERMINAL=") {
			break
		} else if time.Now().After(deadline) {
			t.Fatal("the marker never showed in the child's environment")
		}
	}
	allowed := map[string]bool{"status": true, "devices.list": true, "terminal.status": true, "terminal.disable": true,
		"devices.ungrant_terminal": true, "devices.revoke": true}
	cmds := []string{"devices.rename", "terminal.set_idle", "pair.start_qr"} // not (yet) commands: refused all the same
	for cmd := range srv.controlHandlers() {
		cmds = append(cmds, cmd)
	}
	for _, cmd := range cmds {
		err := srv.authorizeControl(cmd, child.Process.Pid)
		if allowed[cmd] != (err == nil) {
			t.Errorf("%s from a terminal process: %v", cmd, err)
		}
		if err := srv.authorizeControl(cmd, os.Getpid()); err != nil {
			t.Errorf("%s from the PC: %v", cmd, err)
		}
	}
	if !strings.Contains(logs.String(), "cmd=devices.rename") {
		t.Fatalf("no refusal line for an unknown command:\n%s", logs.String())
	}
}

// T19a review m4: a corrupt terminal.json does not stop the server: the error is logged, the terminal
// is disabled, the rest keeps serving, terminal status names the problem, and enable works once the
// file is fixed or removed.
func TestUnusableTerminalStateDisablesOnlyTheTerminal(t *testing.T) {
	for name, mode := range map[string]os.FileMode{"corrupt": 0o600, "wrong permissions": 0o644} {
		t.Run(name, func(t *testing.T) {
			data := `{"enabled":tru`
			if mode != 0o600 {
				data = `{"enabled":true,"devices":[]}`
			}
			var path string
			ts := startServerWithState(t, noIfaces, "", func(dir string) {
				path = filepath.Join(dir, terminal.GrantsFile)
				if err := os.WriteFile(path, []byte(data), 0o600); err != nil {
					t.Fatal(err)
				}
				if err := os.Chmod(path, mode); err != nil {
					t.Fatal(err)
				}
			})
			if !strings.Contains(ts.logs.String(), "level=ERROR msg=\"terminal state unusable") {
				t.Fatalf("no error line:\n%s", ts.logs.String())
			}
			dev, token, err := ts.srv.devices.Add("pixel")
			if err != nil {
				t.Fatal(err)
			}
			api := httptest.NewServer(ts.srv.remoteHandler(listen.LAN))
			defer api.Close()
			get := func(path string) (int, string) {
				req, _ := http.NewRequest("GET", api.URL+path, nil)
				req.Header.Set("Authorization", "Bearer "+token)
				resp, err := http.DefaultClient.Do(req)
				if err != nil {
					t.Fatal(err)
				}
				defer resp.Body.Close()
				b, _ := io.ReadAll(resp.Body)
				return resp.StatusCode, string(b)
			}
			if code, body := get("/v1/me"); code != 200 || !strings.Contains(body, `"terminal":{"enabled":false,"granted":false}`) {
				t.Fatalf("/v1/me = %d %s", code, body)
			}
			if code, body := get("/v1/terminal"); code != 403 || !strings.Contains(body, "terminal_disabled") {
				t.Fatalf("/v1/terminal = %d %s", code, body)
			}
			if code, out, errOut := run(t, "devices", "list"); code != 0 || !strings.Contains(out, dev.ID) {
				t.Fatalf("devices list: %d %s%s", code, out, errOut)
			}
			code, out, errOut := run(t, "terminal", "status")
			if code != 0 || !strings.Contains(out, "Terminal: disabled") || !strings.Contains(out, "terminal.json cannot be used") {
				t.Fatalf("terminal status: %d\n%s%s", code, out, errOut)
			}
			code, out, errOut = run(t, "terminal", "enable")
			if code != 1 || !strings.Contains(errOut, "stays disabled until terminal.json is fixed or removed") {
				t.Fatalf("enable while unusable: %d\n%s%s", code, out, errOut)
			}
			if b, _ := os.ReadFile(path); string(b) != data {
				t.Fatalf("terminal.json overwritten: %s", b)
			}
			if err := os.Remove(path); err != nil {
				t.Fatal(err)
			}
			if code, out, errOut := run(t, "terminal", "enable"); code != 0 || ts.srv.grants.Problem() != nil {
				t.Fatalf("enable after removing the file: %d\n%s%s", code, out, errOut)
			}
			if code, out, _ := run(t, "terminal", "status"); code != 0 || strings.Contains(out, "cannot be used") ||
				!strings.Contains(out, "Terminal: enabled") {
				t.Fatalf("status after the fix:\n%s", out)
			}
		})
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
