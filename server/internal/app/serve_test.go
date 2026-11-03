package app

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/justcallmesu/aristotle/server/internal/config"
	"github.com/justcallmesu/aristotle/server/internal/listen"
	"github.com/justcallmesu/aristotle/server/internal/pairing"
)

type lockedBuffer struct {
	mu  sync.Mutex
	buf bytes.Buffer
}

func (b *lockedBuffer) Write(p []byte) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.Write(p)
}

func (b *lockedBuffer) String() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.String()
}

// startTestServer runs a server whose state lives in a short temp dir (unix
// socket paths are limited to 108 bytes) and that sees no network interfaces.
func startTestServer(t *testing.T) (*server, *lockedBuffer) {
	t.Helper()
	return startTestServerWith(t, func() ([]listen.Iface, error) { return nil, nil })
}

// startTestServerWith runs a server that sees the interfaces scan returns;
// its LAN/Tailscale listeners bind loopback ports instead of those addresses.
func startTestServerWith(t *testing.T, scan func() ([]listen.Iface, error)) (*server, *lockedBuffer) {
	t.Helper()
	root, err := os.MkdirTemp("", "ar")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { os.RemoveAll(root) })
	t.Setenv("XDG_STATE_HOME", root)
	t.Setenv("XDG_CONFIG_HOME", root)
	logs := &lockedBuffer{}
	log := slog.New(slog.NewTextHandler(logs, nil))
	stateDir, err := config.EnsureStateDir(log)
	if err != nil {
		t.Fatal(err)
	}
	cfg := config.Default()
	cfg.Port = 0 // loopback on an ephemeral port
	srv, err := newServer(cfg, stateDir, "t", log, scan)
	if err != nil {
		t.Fatal(err)
	}
	srv.listen.Bind = func(listen.Endpoint, int) (net.Listener, error) { return net.Listen("tcp", "127.0.0.1:0") }
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- srv.run(ctx) }()
	t.Cleanup(func() {
		cancel()
		if err := <-done; err != nil {
			t.Error(err)
		}
		if _, err := os.Lstat(filepath.Join(stateDir, "control.sock")); !os.IsNotExist(err) {
			t.Errorf("control socket left behind: %v", err)
		}
	})
	waitForSocket(t, filepath.Join(stateDir, "control.sock"))
	return srv, logs
}

func waitForSocket(t *testing.T, path string) {
	t.Helper()
	for deadline := time.Now().Add(5 * time.Second); time.Now().Before(deadline); time.Sleep(10 * time.Millisecond) {
		if _, err := os.Stat(path); err == nil {
			return
		}
	}
	t.Fatal("control socket never appeared")
}

func run(t *testing.T, args ...string) (int, string, string) {
	t.Helper()
	var out, errOut bytes.Buffer
	code := Run(args, "t", &out, &errOut)
	return code, out.String(), errOut.String()
}

func TestPairDevicesRevokeEndToEnd(t *testing.T) {
	srv, logs := startTestServer(t)
	code, out, errOut := run(t, "pair", "--name", "test")
	if code != 0 {
		t.Fatalf("pair exit %d: %s", code, errOut)
	}
	m := regexp.MustCompile(`Pairing code:\s+([0-9A-Z]{8})`).FindStringSubmatch(out)
	if m == nil || !strings.Contains(out, srv.cert.Fingerprint) || !strings.Contains(out, "Expires:") {
		t.Fatalf("pair output:\n%s", out)
	}
	pairCode := m[1]

	api := srv.remoteHandler(listen.LAN)
	rec := httptest.NewRecorder()
	api.ServeHTTP(rec, httptest.NewRequest("POST", "/v1/pair", strings.NewReader(`{"code":"`+pairCode+`","device_name":"phone"}`)))
	if rec.Code != http.StatusCreated {
		t.Fatalf("pair = %d %s", rec.Code, rec.Body.String())
	}
	var paired struct {
		DeviceID string `json:"device_id"`
		Token    string `json:"token"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &paired); err != nil {
		t.Fatal(err)
	}

	me := func() int {
		req := httptest.NewRequest("GET", "/v1/me", nil)
		req.Header.Set("Authorization", "Bearer "+paired.Token)
		rec := httptest.NewRecorder()
		api.ServeHTTP(rec, req)
		return rec.Code
	}
	if c := me(); c != http.StatusOK {
		t.Fatalf("me = %d", c)
	}

	code, out, _ = run(t, "devices", "list")
	if code != 0 || !strings.Contains(out, paired.DeviceID) || !strings.Contains(out, "test") {
		t.Fatalf("list exit %d:\n%s", code, out)
	}
	code, out, _ = run(t, "devices", "revoke", paired.DeviceID)
	if code != 0 || !strings.Contains(out, "Revoked "+paired.DeviceID) {
		t.Fatalf("revoke exit %d: %s", code, out)
	}
	if c := me(); c != http.StatusUnauthorized {
		t.Fatalf("me after revoke = %d", c)
	}
	if code, _, errOut = run(t, "devices", "revoke", paired.DeviceID); code != 1 || !strings.Contains(errOut, "no device") {
		t.Fatalf("second revoke exit %d: %s", code, errOut)
	}
	if code, out, _ = run(t, "devices", "list"); code != 0 || !strings.Contains(out, "No paired devices") {
		t.Fatalf("list after revoke: %s", out)
	}

	all := logs.String()
	for _, secret := range []string{pairCode, paired.Token} {
		if strings.Contains(all, secret) {
			t.Fatalf("server log contains a secret:\n%s", all)
		}
	}
}

func TestPairRejectsBadName(t *testing.T) {
	startTestServer(t)
	code, _, errOut := run(t, "pair", "--name", strings.Repeat("n", 65))
	if code != 1 || !strings.Contains(errOut, "--name") {
		t.Fatalf("exit %d: %s", code, errOut)
	}
}

func TestCLIWithoutServer(t *testing.T) {
	root, _ := os.MkdirTemp("", "ar")
	t.Cleanup(func() { os.RemoveAll(root) })
	t.Setenv("XDG_STATE_HOME", root)
	for _, args := range [][]string{{"pair"}, {"devices", "list"}, {"devices", "revoke", "dev_x"}} {
		code, _, errOut := run(t, args...)
		if code != 1 || !strings.Contains(errOut, "not running") {
			t.Errorf("%v: exit %d %q", args, code, errOut)
		}
	}
	if _, err := os.Stat(filepath.Join(root, "aristotle")); !os.IsNotExist(err) {
		t.Error("CLI created the state dir")
	}
}

func TestSecondServerRefused(t *testing.T) {
	srv, _ := startTestServer(t)
	logs := &lockedBuffer{}
	other, err := newServer(srv.cfg, srv.stateDir, "t", slog.New(slog.NewTextHandler(logs, nil)),
		func() ([]listen.Iface, error) { return nil, nil })
	if err == nil || !strings.Contains(err.Error(), "already running") {
		t.Fatalf("err = %v", err)
	}
	if other != nil {
		t.Fatal("second server was built")
	}
	if strings.Contains(logs.String(), "tls certificate") {
		t.Fatal("second server loaded the certificate before taking the lock")
	}
}

func TestPrintPairing(t *testing.T) {
	now := time.Date(2026, 11, 3, 10, 0, 0, 0, time.Local)
	var buf bytes.Buffer
	printPairing(&buf, pairStartResult{
		Code: "ABCD2345", ExpiresAt: now.Add(pairing.TTL), Fingerprint: "AAAA BBBB",
		Addresses: []pairAddr{
			{URL: "https://192.168.1.61:8760", Kind: "lan", Iface: "wlan0", DefaultRoute: true},
			{URL: "https://10.0.0.2:8760", Kind: "lan", Iface: "eth0"},
			{URL: "http://100.74.61.96:8760", Kind: "tailscale", Iface: "tailscale0"},
		},
	}, now)
	out := buf.String()
	for _, want := range []string{"ABCD2345", "10:05:00", "5m0s", "AAAA BBBB", fmt.Sprintf("%d wrong tries", pairing.MaxAttemptsPerPeer)} {
		if !strings.Contains(out, want) {
			t.Errorf("output missing %q:\n%s", want, out)
		}
	}
	lines := addrLines(out)
	want := [][]string{
		{"https://192.168.1.61:8760", "wlan0", "LAN,", "HTTPS,", "default", "route"},
		{"https://10.0.0.2:8760", "eth0", "LAN,", "HTTPS"},
		{"http://100.74.61.96:8760", "tailscale0", "Tailscale,", "HTTP"},
	}
	if len(lines) != len(want) {
		t.Fatalf("address lines %q\n%s", lines, out)
	}
	for i := range want {
		if !slices.Equal(lines[i], want[i]) {
			t.Errorf("line %d = %q, want %q", i, lines[i], want[i])
		}
	}
}

// addrLines returns the fields of each "  http..." line of pair output.
func addrLines(out string) [][]string {
	var lines [][]string
	for _, l := range strings.Split(out, "\n") {
		if f := strings.Fields(l); len(f) > 0 && strings.HasPrefix(f[0], "http") {
			lines = append(lines, f)
		}
	}
	return lines
}

func prefixes(ss ...string) []netip.Prefix {
	out := make([]netip.Prefix, len(ss))
	for i, s := range ss {
		out[i] = netip.MustParsePrefix(s)
	}
	return out
}

// The pair command lists what the server bound: the default-route interface
// first, Tailscale after, each with its interface name; no docker bridge and
// no Tailscale-range address outside tailscale0.
func TestPairOutputOrderAndInterfaces(t *testing.T) {
	ifaces := []listen.Iface{
		{Name: "lo", Up: true, Loopback: true, Addrs: prefixes("127.0.0.1/8")},
		{Name: "tailscale0", Up: true, Tun: true, Addrs: prefixes("100.74.61.96/32", "fd7a:115c:a1e0::d628:3d61/128")},
		{Name: "br-2c441c1f2fd0", Up: true, Addrs: prefixes("172.28.0.1/16")},
		{Name: "docker0", Up: true, Addrs: prefixes("172.17.0.1/16")},
		{Name: "eth0", Up: true, Physical: true, Addrs: prefixes("10.0.0.2/24", "100.90.0.1/10")},
		{Name: "wlan0", Up: true, Physical: true, DefaultRoute: true, Addrs: prefixes("192.168.1.61/24")},
	}
	srv, _ := startTestServerWith(t, func() ([]listen.Iface, error) { return ifaces, nil })
	for deadline := time.Now().Add(5 * time.Second); len(srv.listen.Active()) < 4 && time.Now().Before(deadline); {
		time.Sleep(10 * time.Millisecond)
	}
	code, out, errOut := run(t, "pair")
	if code != 0 {
		t.Fatalf("pair exit %d: %s", code, errOut)
	}
	var got [][2]string
	for _, f := range addrLines(out) {
		got = append(got, [2]string{f[0], f[1]})
	}
	want := [][2]string{
		{"https://192.168.1.61:0", "wlan0"},
		{"https://10.0.0.2:0", "eth0"},
		{"http://100.74.61.96:0", "tailscale0"},
		{"http://[fd7a:115c:a1e0::d628:3d61]:0", "tailscale0"},
	}
	if !slices.Equal(got, want) {
		t.Fatalf("addresses %v\nwant %v\n%s", got, want, out)
	}
	if !strings.Contains(out, "default route") {
		t.Fatalf("default route not marked:\n%s", out)
	}
}
