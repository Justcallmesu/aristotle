package app

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/justcallmesu/aristotle/server/internal/control"
	"github.com/justcallmesu/aristotle/server/internal/devices"
	"github.com/justcallmesu/aristotle/server/internal/httpapi"
	"github.com/justcallmesu/aristotle/server/internal/pairing"
	"github.com/justcallmesu/aristotle/server/internal/terminal"
)

// The --json contract (docs/protocol.md "CLI JSON output (desktop app)"). The fixtures under
// testdata/json/ are the exact success output of each command; the desktop app's tests reuse them.
// UPDATE_JSON_FIXTURES=1 rewrites them from the current output (review the diff by eye).

const fixtureDir = "testdata/json"

var jsonPixel = devices.Public{ID: "dev_a1", Name: "pixel",
	CreatedAt: time.Date(2026, 11, 1, 10, 0, 0, 0, time.UTC), LastSeenAt: time.Date(2026, 11, 18, 9, 30, 0, 0, time.UTC)}

func jsonPairResult() pairStartResult {
	return pairStartResult{Code: "K7Q2M9XD", ExpiresAt: time.Date(2026, 11, 18, 10, 5, 0, 0, time.UTC), Fingerprint: testFingerprint,
		Addresses: []pairAddr{
			{Address: httpapi.Address{URL: "https://192.168.1.5:8760", Kind: "lan", Iface: "wlan0"}, DefaultRoute: true},
			{Address: httpapi.Address{URL: "https://100.74.61.96:8760", Kind: "tailscale", Iface: "tailscale0"}}}}
}

// runJSON runs the CLI and returns exit code, stdout and stderr.
func runJSON(t *testing.T, args ...string) (int, string, string) {
	t.Helper()
	var out, errOut bytes.Buffer
	code := Run(args, "1.2.3", &out, &errOut)
	return code, out.String(), errOut.String()
}

// oneObject checks stdout is exactly one JSON object and a newline, and returns it decoded.
func oneObject(t *testing.T, out string) map[string]any {
	t.Helper()
	if !strings.HasSuffix(out, "\n") || strings.Count(out, "\n") != 1 {
		t.Fatalf("stdout is not one line: %q", out)
	}
	var m map[string]any
	if err := json.Unmarshal([]byte(out), &m); err != nil {
		t.Fatalf("stdout is not a JSON object: %v: %q", err, out)
	}
	return m
}

func checkFixture(t *testing.T, name, out string) {
	t.Helper()
	path := filepath.Join(fixtureDir, name)
	if os.Getenv("UPDATE_JSON_FIXTURES") != "" {
		if err := os.MkdirAll(fixtureDir, 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, []byte(out), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	want, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("fixture %s: %v (UPDATE_JSON_FIXTURES=1 writes it)", name, err)
	}
	if string(want) != out {
		t.Fatalf("output differs from fixture %s\n got %s\nwant %s", name, out, want)
	}
}

type jsonCase struct {
	fixture string
	args    []string
	answers map[string]ctlAnswer
}

func jsonCases() []jsonCase {
	granted := goldenTerminalStatus(true, true, "")
	granted.Device = &jsonPixel
	return []jsonCase{
		{"devices_list.json", []string{"devices", "list", "--json"},
			map[string]ctlAnswer{cmdDevicesList: {res: []devices.Public{jsonPixel, goldenTablet}}}},
		{"devices_list_empty.json", []string{"devices", "list", "--json"},
			map[string]ctlAnswer{cmdDevicesList: {res: nil}}},
		{"devices_revoke.json", []string{"devices", "revoke", "dev_a1", "--json"},
			map[string]ctlAnswer{cmdDevicesRevoke: {res: jsonPixel}}},
		{"devices_grant_terminal.json", []string{"devices", "grant-terminal", "dev_a1", "--json"},
			map[string]ctlAnswer{cmdGrantTerminal: {res: granted}}},
		{"devices_ungrant_terminal.json", []string{"devices", "ungrant-terminal", "dev_a1", "--json"},
			map[string]ctlAnswer{cmdUngrantTerminal: {res: goldenTerminalStatus(true, true, "")}}},
		{"terminal_status.json", []string{"terminal", "status", "--json"},
			map[string]ctlAnswer{cmdTerminalStatus: {res: goldenTerminalStatus(true, false, "")}}},
		{"terminal_enable.json", []string{"terminal", "enable", "--json"},
			map[string]ctlAnswer{cmdTerminalEnable: {res: goldenTerminalStatus(true, true, "terminal.json could not be fsynced")}}},
		{"terminal_disable.json", []string{"terminal", "disable", "--json"},
			map[string]ctlAnswer{cmdTerminalDisable: {res: terminalStatusResult{Changed: true}}}},
		{"pair.json", []string{"pair", "--json"},
			map[string]ctlAnswer{cmdPairStart: {res: jsonPairResult()}}},
	}
}

func TestJSONFixtures(t *testing.T) {
	for _, c := range jsonCases() {
		t.Run(c.fixture, func(t *testing.T) {
			stubControl(t, c.answers)
			code, out, errOut := runJSON(t, c.args...)
			if code != 0 || errOut != "" {
				t.Fatalf("exit %d stderr %q", code, errOut)
			}
			oneObject(t, out)
			checkFixture(t, c.fixture, out)
		})
	}
	t.Run("terminal_status_empty.json", func(t *testing.T) {
		stubControl(t, map[string]ctlAnswer{cmdTerminalStatus: {res: terminalStatusResult{Problem: "bad json"}}})
		code, out, errOut := runJSON(t, "terminal", "status", "--json")
		if code != 0 || errOut != "" {
			t.Fatalf("exit %d stderr %q", code, errOut)
		}
		checkFixture(t, "terminal_status_empty.json", out)
	})
	t.Run("version.json", func(t *testing.T) {
		code, out, errOut := runJSON(t, "version", "--json")
		if code != 0 || errOut != "" {
			t.Fatalf("exit %d stderr %q", code, errOut)
		}
		checkFixture(t, "version.json", out)
	})
}

func TestJSONVersionFields(t *testing.T) {
	_, out, _ := runJSON(t, "version", "--json")
	m := oneObject(t, out)
	if m["version"] != "1.2.3" || m["commit"] != "unknown" || m["protocol"] != float64(1) || len(m) != 3 {
		t.Fatalf("%v", m)
	}
}

func TestJSONDevicesListIsAnObject(t *testing.T) {
	stubControl(t, map[string]ctlAnswer{cmdDevicesList: {res: []devices.Public{jsonPixel}}})
	_, out, _ := runJSON(t, "devices", "list", "--json")
	var got struct {
		Devices []devices.Public `json:"devices"`
	}
	if err := json.Unmarshal([]byte(out), &got); err != nil || len(got.Devices) != 1 || got.Devices[0] != jsonPixel {
		t.Fatalf("%v %+v", err, got)
	}
	// An empty list is [], never null.
	stubControl(t, map[string]ctlAnswer{cmdDevicesList: {res: nil}})
	if _, out, _ = runJSON(t, "devices", "list", "--json"); out != "{\"devices\":[]}\n" {
		t.Fatalf("%q", out)
	}
}

func TestJSONTerminalListsAreArrays(t *testing.T) {
	stubControl(t, map[string]ctlAnswer{cmdTerminalStatus: {res: terminalStatusResult{}}})
	_, out, _ := runJSON(t, "terminal", "status", "--json")
	m := oneObject(t, out)
	if g, ok := m["granted"].([]any); !ok || len(g) != 0 {
		t.Errorf("granted %v", m["granted"])
	}
	if o, ok := m["open"].([]any); !ok || len(o) != 0 {
		t.Errorf("open %v", m["open"])
	}
	if m["enabled"] != false || m["changed"] != false {
		t.Errorf("%v", m)
	}
}

func TestJSONTerminalWarningStaysOutOfStderr(t *testing.T) {
	stubControl(t, map[string]ctlAnswer{cmdTerminalEnable: {res: goldenTerminalStatus(true, true, "disk slow")}})
	code, out, errOut := runJSON(t, "terminal", "enable", "--json")
	if code != 0 || errOut != "" || oneObject(t, out)["warning"] != "disk slow" {
		t.Fatalf("exit %d out %q err %q", code, out, errOut)
	}
}

func TestJSONErrorPathEveryCommand(t *testing.T) {
	cmds := map[string][]string{
		cmdDevicesList:     {"devices", "list", "--json"},
		cmdDevicesRevoke:   {"devices", "revoke", "dev_x", "--json"},
		cmdGrantTerminal:   {"devices", "grant-terminal", "dev_x", "--json"},
		cmdUngrantTerminal: {"devices", "--json", "ungrant-terminal", "dev_x"}, // the flag may sit anywhere after the command
		cmdTerminalStatus:  {"terminal", "status", "--json"},
		cmdTerminalEnable:  {"terminal", "enable", "--json"},
		cmdTerminalDisable: {"terminal", "disable", "--json"},
		cmdPairStart:       {"pair", "--json"},
	}
	for cmd, args := range cmds {
		t.Run(cmd, func(t *testing.T) {
			stubControl(t, map[string]ctlAnswer{cmd: {err: errors.New("no device with id dev_x")}})
			code, out, errOut := runJSON(t, args...)
			if code != 1 || errOut != "" {
				t.Fatalf("exit %d stderr %q", code, errOut)
			}
			if m := oneObject(t, out); m["error"] != "no device with id dev_x" || len(m) != 1 {
				t.Fatalf("%v", m)
			}
		})
	}
}

func TestJSONErrorWhenServerNotRunning(t *testing.T) {
	root := t.TempDir()
	t.Setenv("XDG_STATE_HOME", root)
	code, out, errOut := runJSON(t, "devices", "list", "--json")
	if code != 1 || errOut != "" || !strings.Contains(oneObject(t, out)["error"].(string), "not running") {
		t.Fatalf("exit %d out %q err %q", code, out, errOut)
	}
}

func TestJSONUsageErrorStaysOnStderr(t *testing.T) {
	for _, args := range [][]string{{"devices", "revoke", "--json"}, {"terminal", "on", "--json"}, {"pair", "x", "--json"},
		{"status", "--json", "--porcelain"}, {"stop", "x", "--json"}} {
		code, out, errOut := runJSON(t, args...)
		if code != 2 || out != "" || !strings.Contains(errOut, "usage") {
			t.Errorf("%v: exit %d out %q err %q", args, code, out, errOut)
		}
	}
}

func TestJSONPairHasQRPayloadForEveryAddress(t *testing.T) {
	res := jsonPairResult()
	for i := 0; i < 9; i++ { // more than pairing.MaxPayloadAddresses
		res.Addresses = append(res.Addresses, pairAddr{Address: httpapi.Address{URL: fmt.Sprintf("https://10.0.0.%d:8760", i), Kind: "lan", Iface: "eth0"}})
	}
	stubControl(t, map[string]ctlAnswer{cmdPairStart: {res: res}})
	code, out, errOut := runJSON(t, "pair", "--json", "--name", "x")
	if code != 0 || errOut != "" {
		t.Fatalf("exit %d stderr %q", code, errOut)
	}
	var got pairJSON
	if err := json.Unmarshal([]byte(out), &got); err != nil {
		t.Fatal(err)
	}
	urls := make([]string, len(res.Addresses))
	for i, a := range res.Addresses {
		urls[i] = a.URL
	}
	want, err := pairing.Payload(res.Code, res.Fingerprint, urls)
	if err != nil || got.QRPayload != want || got.QRPayload == "" {
		t.Fatalf("qr_payload %q, want %q (%v)", got.QRPayload, want, err)
	}
	if n := strings.Count(got.QRPayload, "&a="); n != pairing.MaxPayloadAddresses {
		t.Errorf("payload carries %d addresses, want %d", n, pairing.MaxPayloadAddresses)
	}
	if got.Code != res.Code || got.Fingerprint != res.Fingerprint || len(got.Addresses) != len(res.Addresses) || !got.ExpiresAt.Equal(res.ExpiresAt) {
		t.Errorf("result fields lost: %+v", got)
	}
	m := oneObject(t, out)
	for _, k := range []string{"code", "expires_at", "fingerprint", "addresses", "qr_payload"} {
		if _, ok := m[k]; !ok {
			t.Errorf("missing %q", k)
		}
	}
	if _, ok := m["qr_error"]; ok {
		t.Error("qr_error with a working payload")
	}
}

func TestJSONPairWithoutAddressesExplainsTheMissingPayload(t *testing.T) {
	res := jsonPairResult()
	res.Addresses = nil
	stubControl(t, map[string]ctlAnswer{cmdPairStart: {res: res}})
	code, out, errOut := runJSON(t, "pair", "--json")
	m := oneObject(t, out)
	if code != 0 || errOut != "" || m["qr_payload"] != "" || m["qr_error"] == "" {
		t.Fatalf("exit %d %v err %q", code, m, errOut)
	}
	if a, ok := m["addresses"].([]any); !ok || len(a) != 0 {
		t.Errorf("addresses %v", m["addresses"])
	}
	if strings.Contains(m["qr_error"].(string), res.Code) {
		t.Error("qr_error holds the code")
	}
}

// pair --json prints the code on stdout only: not on stderr, not in the server log, not in an error.
func TestJSONPairNeverLeaksTheCodeElsewhere(t *testing.T) {
	_, logs := startTestServer(t)
	code, out, errOut := runJSON(t, "pair", "--json")
	if code != 0 || errOut != "" {
		t.Fatalf("exit %d stderr %q", code, errOut)
	}
	var got pairJSON
	if err := json.Unmarshal([]byte(out), &got); err != nil || len(got.Code) != pairing.CodeLength {
		t.Fatalf("%v %q", err, out)
	}
	if strings.Contains(logs.String(), got.Code) {
		t.Fatalf("the server log holds the pairing code:\n%s", logs.String())
	}
	// With the Payload failing (no listener is up in the test server) the error text has no code either.
	if got.QRError != "" && strings.Contains(got.QRError, got.Code) {
		t.Fatal("qr_error holds the code")
	}
}

func TestJSONRevokeEndToEnd(t *testing.T) {
	srv, _ := startTestServer(t)
	dev, _, err := srv.devices.Add("pixel")
	if err != nil {
		t.Fatal(err)
	}
	code, out, errOut := runJSON(t, "devices", "list", "--json")
	if code != 0 || errOut != "" || !strings.Contains(out, dev.ID) || strings.Contains(out, "token") {
		t.Fatalf("list: exit %d %q %q", code, out, errOut)
	}
	code, out, _ = runJSON(t, "devices", "revoke", dev.ID, "--json")
	if m := oneObject(t, out); code != 0 || m["id"] != dev.ID || m["name"] != "pixel" {
		t.Fatalf("revoke: exit %d %q", code, out)
	}
	code, out, errOut = runJSON(t, "devices", "revoke", dev.ID, "--json")
	if m := oneObject(t, out); code != 1 || errOut != "" || m["error"] != "no device with id "+dev.ID {
		t.Fatalf("again: exit %d %q %q", code, out, errOut)
	}
	code, out, errOut = runJSON(t, "terminal", "status", "--json")
	if m := oneObject(t, out); code != 0 || errOut != "" || m["enabled"] != false {
		t.Fatalf("terminal status: exit %d %q %q", code, out, errOut)
	}
}

// ---- status --json

func runStatusJSON(t *testing.T, active string, answer *statusResult, qerr error) (int, string, string) {
	t.Helper()
	return runStatusCmd(t, active, answer, qerr, "--json")
}

func TestJSONStatusRunning(t *testing.T) {
	code, out, errOut := runStatusJSON(t, "active", goldenStatus(), nil)
	if code != 0 || errOut != "" {
		t.Fatalf("exit %d stderr %q", code, errOut)
	}
	m := oneObject(t, out)
	if m["service"] != "active" || m["enabled"] != "enabled" || m["server"] != "running" {
		t.Fatalf("%v", m)
	}
	res, ok := m["result"].(map[string]any)
	if !ok || res["version"] != "1.2.3" || res["port"] != float64(8760) || res["hermes"].(map[string]any)["state"] != "reachable" {
		t.Fatalf("result %v", m["result"])
	}
	checkFixture(t, "status.json", out)
}

func TestJSONStatusServerDown(t *testing.T) {
	cases := []struct {
		err    error
		server string
	}{{fmt.Errorf("x: %w", control.ErrNotRunning), "not-running"}, {errors.New("boom"), "no-answer"}}
	for _, c := range cases {
		code, out, errOut := runStatusJSON(t, "inactive", nil, c.err)
		m := oneObject(t, out)
		if code != 1 || errOut != "" || m["server"] != c.server || m["service"] != "inactive" {
			t.Fatalf("%v: exit %d %v %q", c.err, code, m, errOut)
		}
		if _, has := m["result"]; has {
			t.Errorf("result present while the server is %s", c.server)
		}
		if c.server == "not-running" {
			checkFixture(t, "status_not_running.json", out)
		}
	}
}

// installed is the unit file at UnitPath: a file or a symlink to one counts, a dangling symlink and
// nothing do not. It is there in every state, server down included, and never calls systemctl.
func TestJSONStatusInstalled(t *testing.T) {
	linkTo := func(target string) func(*testing.T, string) {
		return func(t *testing.T, unit string) {
			if err := os.MkdirAll(filepath.Dir(unit), 0o755); err != nil {
				t.Fatal(err)
			}
			if err := os.Symlink(target, unit); err != nil {
				t.Fatal(err)
			}
		}
	}
	real := func(t *testing.T, unit string) {
		other := filepath.Join(t.TempDir(), "elsewhere.service")
		if err := os.WriteFile(other, []byte("[Unit]\n"), 0o644); err != nil {
			t.Fatal(err)
		}
		linkTo(other)(t, unit)
	}
	cases := []struct {
		name  string
		setup func(*testing.T, string)
		want  bool
	}{
		{"file", installUnit, true},
		{"absent", func(*testing.T, string) {}, false},
		{"symlink", real, true},
		{"dangling symlink", linkTo("/nonexistent/aristotle-server.service"), false},
	}
	for _, c := range cases {
		for _, down := range []bool{false, true} {
			var res *statusResult
			var qerr error
			if down {
				qerr = fmt.Errorf("x: %w", control.ErrNotRunning)
			} else {
				res = goldenStatus()
			}
			_, out, errOut := runStatusUnit(t, c.setup, "active", res, qerr, "--json")
			m := oneObject(t, out)
			if got, ok := m["installed"].(bool); !ok || got != c.want || errOut != "" {
				t.Errorf("%s (server down %v): installed %v, want %v; %q %q", c.name, down, m["installed"], c.want, out, errOut)
			}
			if _, porcelain, _ := runStatusUnit(t, c.setup, "active", res, qerr, "--porcelain"); strings.Contains(porcelain, "installed") {
				t.Errorf("%s: porcelain mentions installed: %q", c.name, porcelain)
			}
		}
	}
}

func TestJSONStatusNotInstalledFixture(t *testing.T) {
	code, out, errOut := runStatusUnit(t, func(*testing.T, string) {}, "inactive", nil, fmt.Errorf("x: %w", control.ErrNotRunning), "--json")
	if code != 1 || errOut != "" {
		t.Fatalf("exit %d stderr %q", code, errOut)
	}
	checkFixture(t, "status_not_installed.json", out)
}

// status --json decides exactly what status --porcelain decides: the server word and the exit code.
func TestJSONStatusMatchesPorcelain(t *testing.T) {
	answers := []error{nil, fmt.Errorf("x: %w", control.ErrNotRunning), errors.New("boom")}
	for _, active := range []string{"active", "inactive", "failed"} {
		for _, qerr := range answers {
			var res *statusResult
			if qerr == nil {
				res = goldenStatus()
			}
			jc, jout, _ := runStatusJSON(t, active, res, qerr)
			pc, pout := runStatusWith(t, active, res, qerr, "--porcelain")
			word := oneObject(t, jout)["server"].(string)
			if jc != pc || !strings.Contains(pout, "\nserver "+word+"\n") {
				t.Errorf("%s %v: json exit %d server %s; porcelain exit %d\n%s", active, qerr, jc, word, pc, pout)
			}
		}
	}
}

// ---- start, stop, restart

func TestLifecycleRunsSystemctlWithExactArgs(t *testing.T) {
	for _, verb := range []string{"start", "stop", "restart"} {
		f, _, _ := useFakes(t)
		noTerminal(t)
		f.out = map[string]string{isActiveCmd: "active\n"}
		code, out, errOut := runJSON(t, verb)
		if code != 0 || errOut != "" || out != "Service "+verb+" done; it is now active.\n" {
			t.Fatalf("%s: exit %d out %q err %q", verb, code, out, errOut)
		}
		if len(f.calls) != 2 || f.calls[0] != "systemctl --user "+verb+" aristotle-server.service" || f.calls[1] != isActiveCmd {
			t.Fatalf("%s: calls %v", verb, f.calls)
		}
	}
}

func TestLifecycleJSON(t *testing.T) {
	for _, c := range []struct{ verb, after string }{{"start", "active"}, {"stop", "inactive"}, {"restart", "active"}} {
		f, _, _ := useFakes(t)
		noTerminal(t)
		f.out = map[string]string{isActiveCmd: c.after + "\n"}
		code, out, errOut := runJSON(t, c.verb, "--json")
		if code != 0 || errOut != "" || out != `{"ok":true,"service":"`+c.after+`"}`+"\n" {
			t.Fatalf("%s: exit %d out %q err %q", c.verb, code, out, errOut)
		}
		if f.calls[0] != "systemctl --user "+c.verb+" aristotle-server.service" {
			t.Fatalf("%s: calls %v", c.verb, f.calls)
		}
		checkFixture(t, c.verb+".json", out)
	}
}

func TestLifecycleFailure(t *testing.T) {
	for _, asJSON := range []bool{false, true} {
		f, _, _ := useFakes(t)
		noTerminal(t)
		f.fail = map[string]bool{"systemctl --user restart aristotle-server.service": true}
		args := []string{"restart"}
		if asJSON {
			args = append(args, "--json")
		}
		code, out, errOut := runJSON(t, args...)
		if code != 1 {
			t.Fatalf("exit %d", code)
		}
		if asJSON {
			m := oneObject(t, out)
			if errOut != "" || m["error"] != "systemctl --user restart: exit status 1" {
				t.Fatalf("%v %q", m, errOut)
			}
		} else if out != "" || errOut != "error: systemctl --user restart: exit status 1\n" {
			t.Fatalf("out %q err %q", out, errOut)
		}
		if len(f.calls) != 1 {
			t.Fatalf("is-active asked after a failed restart: %v", f.calls)
		}
	}
}

// noTerminal points the remote-terminal test at an empty environment and a cgroup file without the unit.
func noTerminal(t *testing.T) {
	t.Helper()
	cg := filepath.Join(t.TempDir(), "cgroup")
	if err := os.WriteFile(cg, []byte("0::/user.slice/user-1000.slice/session-2.scope\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	useTerminalEnv(t, func(string) string { return "" }, cg)
}

func useTerminalEnv(t *testing.T, env func(string) string, cgroup string) {
	t.Helper()
	oldEnv, oldCg := getenv, cgroupFile
	getenv, cgroupFile = env, cgroup
	t.Cleanup(func() { getenv, cgroupFile = oldEnv, oldCg })
}

func TestStopAndRestartRefusedInsideARemoteTerminal(t *testing.T) {
	inUnit := filepath.Join(t.TempDir(), "cgroup")
	if err := os.WriteFile(inUnit, []byte("0::/user.slice/user-1000.slice/user@1000.service/app.slice/aristotle-server.service/term_1\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	marked := func(k string) string {
		if k == terminal.MarkerEnv {
			return "term_0001"
		}
		return ""
	}
	none := func(string) string { return "" }
	missing := filepath.Join(t.TempDir(), "no-such-cgroup")
	ways := []struct {
		name   string
		env    func(string) string
		cgroup string
	}{{"env marker", marked, missing}, {"cgroup names the unit", none, inUnit}}
	for _, w := range ways {
		for _, verb := range []string{"stop", "restart"} {
			for _, asJSON := range []bool{false, true} {
				t.Run(fmt.Sprintf("%s/%s/json=%v", w.name, verb, asJSON), func(t *testing.T) {
					f, _, _ := useFakes(t)
					useTerminalEnv(t, w.env, w.cgroup)
					args := []string{verb}
					if asJSON {
						args = append(args, "--json")
					}
					code, out, errOut := runJSON(t, args...)
					if code != 1 || len(f.calls) != 0 {
						t.Fatalf("exit %d, runner calls %v", code, f.calls)
					}
					if asJSON {
						if msg, _ := oneObject(t, out)["error"].(string); errOut != "" || !strings.Contains(msg, "refused") || !strings.Contains(msg, verb) {
							t.Fatalf("out %q err %q", out, errOut)
						}
					} else if out != "" || !strings.HasPrefix(errOut, "error: refused: "+verb) {
						t.Fatalf("out %q err %q", out, errOut)
					}
				})
			}
		}
		t.Run(w.name+"/start allowed", func(t *testing.T) {
			f, _, _ := useFakes(t)
			useTerminalEnv(t, w.env, w.cgroup)
			if code, out, errOut := runJSON(t, "start", "--json"); code != 0 || errOut != "" || !strings.Contains(out, `"ok":true`) {
				t.Fatalf("exit %d out %q err %q", code, out, errOut)
			}
			if f.calls[0] != "systemctl --user start aristotle-server.service" {
				t.Fatal(f.calls)
			}
		})
	}
}

func TestUsageListsTheNewCommandsAndFlag(t *testing.T) {
	_, out, _ := runJSON(t, "help")
	for _, want := range []string{"start | stop | restart", "--json", "[--no-qr] [--json]", "status", "version"} {
		if !strings.Contains(out, want) {
			t.Errorf("usage lacks %q:\n%s", want, out)
		}
	}
}

// ---- MINOR-2: a status answer with the optional fields absent (an older server, or one that has not
// finished its first scan). A parser must treat port, listeners and hermes as optional.

func TestJSONStatusWithOptionalFieldsAbsent(t *testing.T) {
	old := &statusResult{Version: "0.9", Addresses: []httpapi.Address{}}
	code, out, errOut := runStatusJSON(t, "active", old, nil)
	if code != 0 || errOut != "" {
		t.Fatalf("exit %d stderr %q", code, errOut)
	}
	checkFixture(t, "status_sparse.json", out)
	var got struct {
		Service string `json:"service"`
		Server  string `json:"server"`
		Result  *struct {
			Version   string            `json:"version"`
			Addresses []httpapi.Address `json:"addresses"`
			Port      *int              `json:"port"`
			Listeners *[]statusListener `json:"listeners"`
			Hermes    *hermesStatus     `json:"hermes"`
		} `json:"result"`
	}
	data, err := os.ReadFile(filepath.Join(fixtureDir, "status_sparse.json"))
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(data, &got); err != nil || got.Result == nil {
		t.Fatalf("decode the fixture: %v %+v", err, got)
	}
	if got.Result.Version != "0.9" || got.Result.Addresses == nil || len(got.Result.Addresses) != 0 {
		t.Errorf("version/addresses: %+v", got.Result)
	}
	if got.Result.Port != nil || got.Result.Listeners != nil || got.Result.Hermes != nil {
		t.Errorf("optional fields were not absent: %+v", got.Result)
	}
	for _, key := range []string{"port", "listeners", "hermes"} {
		if _, ok := oneObject(t, out)["result"].(map[string]any)[key]; ok {
			t.Errorf("result has %q", key)
		}
	}
}

// NIT-5: `service` is "unknown" when systemctl prints nothing.
func TestJSONStatusServiceUnknown(t *testing.T) {
	code, out, _ := runStatusJSON(t, "", nil, control.ErrNotRunning)
	if m := oneObject(t, out); code != 1 || m["service"] != "unknown" {
		t.Fatalf("exit %d %v", code, m)
	}
}

// ---- NIT-3: --json is one removed token on every command, pair included.

func TestPairJSONIsNotTakenAsTheName(t *testing.T) {
	called := false
	old := callServer
	callServer = func(string, any, any) error { called = true; return nil }
	t.Cleanup(func() { callServer = old })
	for _, args := range [][]string{{"pair", "--name", "--json"}, {"pair", "--json", "--name"}, {"pair", "--name", "-x"},
		{"pair", "--name", "--no-qr"}, {"pair", "-json"}, {"pair", "--json=true"}} {
		code, out, errOut := runJSON(t, args...)
		if code != 2 || out != "" || errOut == "" {
			t.Errorf("%v: exit %d out %q err %q", args, code, out, errOut)
		}
	}
	if called {
		t.Error("a bad pair command still called the server")
	}
}

func TestPairJSONAnywhere(t *testing.T) {
	for _, args := range [][]string{{"pair", "--json", "--name", "x"}, {"pair", "--name", "x", "--json"}, {"pair", "--no-qr", "--json"}} {
		stubControl(t, map[string]ctlAnswer{cmdPairStart: {res: jsonPairResult()}})
		code, out, errOut := runJSON(t, args...)
		if code != 0 || errOut != "" {
			t.Fatalf("%v: exit %d err %q", args, code, errOut)
		}
		oneObject(t, out)
	}
}

// ---- NIT-4: every per-command usage line mentions --json.

func TestUsageLinesMentionJSON(t *testing.T) {
	for _, args := range [][]string{{"pair", "x"}, {"status", "x"}, {"devices"}, {"terminal"}, {"start", "x"}, {"stop", "x"}, {"restart", "x"}} {
		code, out, errOut := runJSON(t, args...)
		if code != 2 || out != "" || !strings.Contains(errOut, "--json") {
			t.Errorf("%v: exit %d out %q err %q", args, code, out, errOut)
		}
	}
}

// MINOR-3: the lifecycle guard watches the terminal package's own marker variable.
func TestLifecycleGuardUsesTheTerminalMarker(t *testing.T) {
	f, _, _ := useFakes(t)
	useTerminalEnv(t, func(k string) string {
		if k == terminal.MarkerEnv {
			return "term_1"
		}
		return ""
	}, filepath.Join(t.TempDir(), "missing"))
	if code, _, _ := runJSON(t, "stop", "--json"); code != 1 || len(f.calls) != 0 {
		t.Fatalf("exit %d calls %v", code, f.calls)
	}
}
