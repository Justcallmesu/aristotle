package app

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"testing"
	"time"

	"github.com/justcallmesu/aristotle/server/internal/control"
	"github.com/justcallmesu/aristotle/server/internal/devices"
	"github.com/justcallmesu/aristotle/server/internal/httpapi"
	"github.com/justcallmesu/aristotle/server/internal/terminal"
)

// Golden tests of the plain (non --json) output of every command the desktop ticket (DS1) touches.
// They were written before the --json code and pass on the old code: plain output stays byte for byte.

// ctlAnswer is what the stub control socket answers for one command.
type ctlAnswer struct {
	res any
	err error
}

// stubControl replaces callServer with canned answers per control command, and records the calls.
func stubControl(t *testing.T, answers map[string]ctlAnswer) *[]string {
	t.Helper()
	var calls []string
	old := callServer
	callServer = func(cmd string, args, out any) error {
		calls = append(calls, cmd)
		a, ok := answers[cmd]
		if !ok {
			return fmt.Errorf("stub: no answer for %s", cmd)
		}
		if a.err != nil {
			return a.err
		}
		raw, err := json.Marshal(a.res)
		if err != nil {
			return err
		}
		return json.Unmarshal(raw, out)
	}
	t.Cleanup(func() { callServer = old })
	return &calls
}

// utc makes stamp() and the Expires line independent of the machine's time zone.
func utc(t *testing.T) {
	t.Helper()
	old := time.Local
	time.Local = time.UTC
	t.Cleanup(func() { time.Local = old })
}

var (
	goldenPixel = devices.Public{ID: "dev_a1", Name: "pixel",
		CreatedAt: time.Date(2026, 11, 1, 10, 0, 0, 0, time.UTC), LastSeenAt: time.Date(2026, 11, 18, 9, 30, 0, 0, time.UTC)}
	goldenTablet = devices.Public{ID: "dev_b2", Name: "tablet",
		CreatedAt: time.Date(2026, 11, 5, 8, 15, 0, 0, time.UTC), LastSeenAt: time.Date(2026, 11, 17, 22, 45, 0, 0, time.UTC)}
	goldenOpen = terminal.Open{ID: "term_0001", DeviceID: "dev_a1", Listener: "lan",
		Since: time.Date(2026, 11, 18, 9, 0, 0, 0, time.UTC), PID: 4242}
)

func goldenTerminalStatus(enabled bool, changed bool, warning string) terminalStatusResult {
	return terminalStatusResult{Enabled: enabled, Changed: changed, Warning: warning,
		Granted: []grantedDevice{{ID: "dev_a1", Name: "pixel"}},
		Open:    []terminal.Open{goldenOpen}}
}

func goldenStatus() *statusResult {
	return &statusResult{Version: "1.2.3", Port: 8760,
		Addresses: []httpapi.Address{
			{URL: "https://192.168.1.5:8760", Kind: "lan", Iface: "wlan0"},
			{URL: "https://100.74.61.96:8760", Kind: "tailscale", Iface: "tailscale0"}},
		Listeners: []statusListener{
			{Kind: "lan", Iface: "wlan0", IP: "192.168.1.5", Subnet: "192.168.1.0/24"},
			{Kind: "tailscale", Iface: "tailscale0", IP: "100.74.61.96"}},
		Hermes: &hermesStatus{State: hermesReachable}}
}

type goldenCase struct {
	name    string
	args    []string
	answers map[string]ctlAnswer
	code    int
	out     string
	errOut  string
}

func TestPlainOutputGolden(t *testing.T) {
	utc(t)
	cases := []goldenCase{
		{name: "devices list", args: []string{"devices", "list"},
			answers: map[string]ctlAnswer{cmdDevicesList: {res: []devices.Public{goldenPixel, goldenTablet}}},
			out:     "ID      NAME    PAIRED            LAST SEEN\ndev_a1  pixel   2026-11-01 10:00  2026-11-18 09:30\ndev_b2  tablet  2026-11-05 08:15  2026-11-17 22:45\n"},
		{name: "devices list empty", args: []string{"devices", "list"},
			answers: map[string]ctlAnswer{cmdDevicesList: {res: []devices.Public{}}},
			out:     "No paired devices.\n"},
		{name: "devices list error", args: []string{"devices", "list"},
			answers: map[string]ctlAnswer{cmdDevicesList: {err: errors.New("boom")}},
			code:    1, errOut: "error: boom\n"},
		{name: "devices revoke", args: []string{"devices", "revoke", "dev_a1"},
			answers: map[string]ctlAnswer{cmdDevicesRevoke: {res: goldenPixel}},
			out:     "Revoked dev_a1 (pixel). Its token stops working on the next request; its relays and terminals were closed.\n"},
		{name: "devices revoke error", args: []string{"devices", "revoke", "dev_x"},
			answers: map[string]ctlAnswer{cmdDevicesRevoke: {err: errors.New("no device with id dev_x")}},
			code:    1, errOut: "error: no device with id dev_x\n"},
		{name: "devices usage", args: []string{"devices"}, code: 2,
			errOut: devicesUsage + "\n"},
		{name: "grant", args: []string{"devices", "grant-terminal", "dev_a1"},
			answers: map[string]ctlAnswer{cmdGrantTerminal: {res: withDevice(goldenTerminalStatus(true, true, ""), goldenPixel)}},
			out:     "Granted the remote terminal to dev_a1 (pixel): it gets a shell with full access to your account.\n"},
		{name: "grant while disabled", args: []string{"devices", "grant-terminal", "dev_a1"},
			answers: map[string]ctlAnswer{cmdGrantTerminal: {res: withDevice(goldenTerminalStatus(false, true, "careful"), goldenPixel)}},
			out:     "Granted the remote terminal to dev_a1 (pixel): it gets a shell with full access to your account\nonce the terminal is enabled (aristotle-server terminal enable).\n",
			errOut:  "warning: careful\n"},
		{name: "grant again", args: []string{"devices", "grant-terminal", "dev_a1"},
			answers: map[string]ctlAnswer{cmdGrantTerminal: {res: withDevice(goldenTerminalStatus(true, false, ""), goldenPixel)}},
			out:     "dev_a1 already holds a terminal grant.\n"},
		{name: "ungrant", args: []string{"devices", "ungrant-terminal", "dev_a1"},
			answers: map[string]ctlAnswer{cmdUngrantTerminal: {res: goldenTerminalStatus(true, true, "")}},
			out:     "Removed the terminal grant of dev_a1; its open terminals were closed.\n"},
		{name: "ungrant error", args: []string{"devices", "ungrant-terminal", "dev_a1"},
			answers: map[string]ctlAnswer{cmdUngrantTerminal: {err: errors.New("device dev_a1 has no terminal grant")}},
			code:    1, errOut: "error: device dev_a1 has no terminal grant\n"},
		{name: "terminal status", args: []string{"terminal", "status"},
			answers: map[string]ctlAnswer{cmdTerminalStatus: {res: goldenTerminalStatus(true, false, "")}},
			out:     "Terminal: enabled\nGranted devices:\n  dev_a1  pixel\nOpen terminals:\n  term_0001  dev_a1  lan  since 2026-11-18 09:00  pid 4242\n"},
		{name: "terminal status empty", args: []string{"terminal", "status"},
			answers: map[string]ctlAnswer{cmdTerminalStatus: {res: terminalStatusResult{Granted: []grantedDevice{}, Problem: "bad json"}}},
			out:     "Terminal: disabled\n  terminal.json cannot be used, so the terminal stays disabled: bad json\n  Fix or remove the file (removing it also removes every grant), then run terminal enable again.\nGranted devices: none\nOpen terminals: none\n"},
		{name: "terminal enable", args: []string{"terminal", "enable"},
			answers: map[string]ctlAnswer{cmdTerminalEnable: {res: goldenTerminalStatus(true, true, "disk slow")}},
			out:     "Remote terminal enabled. A granted phone now gets a shell with FULL access to your account\n(files, SSH keys, Hermes); revoking the phone later does not undo what was done. See docs/terminal.md.\nTerminal: enabled\nGranted devices:\n  dev_a1  pixel\nOpen terminals:\n  term_0001  dev_a1  lan  since 2026-11-18 09:00  pid 4242\n",
			errOut:  "warning: disk slow\n"},
		{name: "terminal disable", args: []string{"terminal", "disable"},
			answers: map[string]ctlAnswer{cmdTerminalDisable: {res: terminalStatusResult{Granted: []grantedDevice{}}}},
			out:     "Remote terminal disabled. Open terminals were closed and their processes killed.\nTerminal: disabled\nGranted devices: none\nOpen terminals: none\n"},
		{name: "terminal error", args: []string{"terminal", "status"},
			answers: map[string]ctlAnswer{cmdTerminalStatus: {err: fmt.Errorf("x: %w", control.ErrNotRunning)}},
			code:    1, errOut: "error: x: " + control.ErrNotRunning.Error() + "\n"},
		{name: "terminal usage", args: []string{"terminal", "on"}, code: 2, errOut: terminalUsage + "\n"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) { checkGolden(t, c) })
	}
}

func withDevice(r terminalStatusResult, d devices.Public) terminalStatusResult {
	r.Device = &d
	return r
}

func checkGolden(t *testing.T, c goldenCase) {
	t.Helper()
	stubControl(t, c.answers)
	var out, errOut bytes.Buffer
	code := Run(c.args, "1.2.3", &out, &errOut)
	if code != c.code || out.String() != c.out || errOut.String() != c.errOut {
		t.Fatalf("exit %d, want %d\n out %q\nwant %q\n err %q\nwant %q", code, c.code, out.String(), c.out, errOut.String(), c.errOut)
	}
}

func TestPlainVersionGolden(t *testing.T) {
	var out, errOut bytes.Buffer
	if code := Run([]string{"version"}, "1.2.3", &out, &errOut); code != 0 ||
		out.String() != "aristotle-server 1.2.3 (unknown) protocol 1\n" || errOut.Len() != 0 {
		t.Fatalf("exit %d out %q err %q", code, out.String(), errOut.String())
	}
}

func TestPlainStatusGolden(t *testing.T) {
	runIt := func(t *testing.T, active string, answer *statusResult, qerr error, args ...string) (int, string, string) {
		t.Helper()
		return runStatusCmd(t, active, answer, qerr, args...)
	}
	t.Run("running", func(t *testing.T) {
		code, out, errOut := runIt(t, "active", goldenStatus(), nil)
		want := "Service:  active\nEnabled:  enabled\nServer:   running, version 1.2.3\nAddresses:\n" +
			"  https://192.168.1.5:8760   wlan0       LAN, HTTPS\n  https://100.74.61.96:8760  tailscale0  Tailscale, HTTPS\nHermes:   reachable\n"
		if code != 0 || out != want || errOut != "" {
			t.Fatalf("exit %d\n got %q\nwant %q err %q", code, out, want, errOut)
		}
	})
	t.Run("not running", func(t *testing.T) {
		code, out, _ := runIt(t, "inactive", nil, fmt.Errorf("x: %w", control.ErrNotRunning))
		if code != 1 || out != "Service:  inactive\nEnabled:  enabled\nServer:   not running (no control socket)\n" {
			t.Fatalf("exit %d %q", code, out)
		}
	})
	t.Run("no answer", func(t *testing.T) {
		code, out, _ := runIt(t, "active", nil, errors.New("timeout"))
		if code != 1 || out != "Service:  active\nEnabled:  enabled\nServer:   no answer (timeout)\n" {
			t.Fatalf("exit %d %q", code, out)
		}
	})
	t.Run("usage", func(t *testing.T) {
		code, out, errOut := runIt(t, "active", nil, nil, "--bogus")
		if code != 2 || out != "" || errOut != "usage: aristotle-server status [--porcelain | --json]\n" {
			t.Fatalf("exit %d %q %q", code, out, errOut)
		}
	})
}

// The pair output (QR code and manual data) with a fixed clock, so it can be compared whole.
func TestPlainPairingGolden(t *testing.T) {
	utc(t)
	now := time.Date(2026, 11, 18, 10, 0, 0, 0, time.UTC)
	res := pairStartResult{Code: "K7Q2M9XD", ExpiresAt: now.Add(5 * time.Minute), Fingerprint: testFingerprint,
		Addresses: []pairAddr{{Address: httpapi.Address{URL: "https://192.168.1.5:8760", Kind: "lan", Iface: "wlan0"}, DefaultRoute: true}}}
	var noQR bytes.Buffer
	printPairing(&noQR, res, now, false)
	want := "Pairing code:  K7Q2M9XD\nExpires:       10:05:00 (in 5m0s); single use; 5 wrong tries lock out that address\n\n" +
		"Addresses (enter one in the app):\n  https://192.168.1.5:8760  wlan0  LAN, HTTPS, default route\n\n" +
		"Certificate fingerprint (the app shows it for every address; they must match):\n  " + testFingerprint + "\n"
	if noQR.String() != want {
		t.Fatalf("got %q\nwant %q", noQR.String(), want)
	}
	var withQR bytes.Buffer
	printPairing(&withQR, res, now, true)
	sum := sha256.Sum256(withQR.Bytes())
	if got := hex.EncodeToString(sum[:]); got != goldenPairQRSum {
		t.Fatalf("pair output with QR changed (sha256 %s):\n%s", got, withQR.String())
	}
}

// goldenPairQRSum is the sha256 of the pair output with the QR code for TestPlainPairingGolden's fixture.
const goldenPairQRSum = "73d04dde809e2fed5ffb27c18cd93b96012c937b9428c5e9190e16bc044062d3"

// Plain pair through Run, with the control call stubbed: the failure path.
func TestPlainPairErrorGolden(t *testing.T) {
	stubControl(t, map[string]ctlAnswer{cmdPairStart: {err: errors.New("refused: pair")}})
	var out, errOut bytes.Buffer
	if code := Run([]string{"pair"}, "v", &out, &errOut); code != 1 || out.Len() != 0 || errOut.String() != "error: refused: pair\n" {
		t.Fatalf("exit %d out %q err %q", code, out.String(), errOut.String())
	}
	out.Reset()
	errOut.Reset()
	if code := Run([]string{"pair", "extra"}, "v", &out, &errOut); code != 2 ||
		errOut.String() != "usage: aristotle-server pair [--name NAME] [--no-qr] [--json]\n" {
		t.Fatalf("usage: exit %d err %q", code, errOut.String())
	}
}
