package app

import (
	"bytes"
	"context"
	"errors"
	"io"
	"log/slog"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

type fakeRunner struct {
	mu    sync.Mutex
	calls []string
	out   map[string]string
}

func (f *fakeRunner) Run(stdout, _ io.Writer, name string, args ...string) error {
	j := name + " " + strings.Join(args, " ")
	f.mu.Lock()
	f.calls = append(f.calls, j)
	f.mu.Unlock()
	if o, ok := f.out[j]; ok {
		io.WriteString(stdout, o)
	}
	return nil
}

// useFakes points the service commands at a fake runner, a temp unit dir and
// a temp state dir.
func useFakes(t *testing.T) (*fakeRunner, string, string) {
	t.Helper()
	cfg, state := t.TempDir(), t.TempDir()
	t.Setenv("XDG_CONFIG_HOME", cfg)
	t.Setenv("XDG_STATE_HOME", state)
	f := &fakeRunner{}
	oldR, oldE, oldI := serviceRunner, executable, stdin
	serviceRunner = f
	executable = func() (string, error) { return "/opt/aristotle-server", nil }
	t.Cleanup(func() { serviceRunner, executable, stdin = oldR, oldE, oldI })
	return f, filepath.Join(cfg, "systemd", "user", "aristotle-server.service"), filepath.Join(state, "aristotle")
}

func TestInstallCommand(t *testing.T) {
	f, unit, _ := useFakes(t)
	var out, errOut bytes.Buffer
	if code := Run([]string{"install", "--start"}, "v", &out, &errOut); code != 0 {
		t.Fatalf("code %d: %s", code, errOut.String())
	}
	b, err := os.ReadFile(unit)
	if err != nil || !strings.Contains(string(b), "ExecStart=/opt/aristotle-server serve") {
		t.Fatalf("unit %q %v", b, err)
	}
	if len(f.calls) != 3 || f.calls[2] != "systemctl --user start aristotle-server.service" {
		t.Fatal(f.calls)
	}
	if !strings.Contains(out.String(), "loginctl enable-linger $USER") {
		t.Fatal(out.String())
	}
	for _, c := range f.calls {
		if strings.Contains(c, "linger") {
			t.Fatal("must not run enable-linger")
		}
	}
}

func TestInstallRefusesDifferent(t *testing.T) {
	_, unit, _ := useFakes(t)
	os.MkdirAll(filepath.Dir(unit), 0o755)
	os.WriteFile(unit, []byte("other\n"), 0o644)
	var out, errOut bytes.Buffer
	if code := Run([]string{"install"}, "v", &out, &errOut); code != 1 || !strings.Contains(errOut.String(), "--force") {
		t.Fatalf("code %d err %q", code, errOut.String())
	}
	if code := Run([]string{"install", "--force"}, "v", &out, &errOut); code != 0 {
		t.Fatalf("force code %d", code)
	}
}

func makeState(t *testing.T, dir string) {
	t.Helper()
	os.MkdirAll(dir, 0o700)
	os.WriteFile(filepath.Join(dir, "devices.json"), []byte("{}"), 0o600)
}

func TestUninstallKeepsState(t *testing.T) {
	f, unit, state := useFakes(t)
	makeState(t, state)
	os.MkdirAll(filepath.Dir(unit), 0o755)
	os.WriteFile(unit, []byte("x"), 0o644)
	var out, errOut bytes.Buffer
	if code := Run([]string{"uninstall"}, "v", &out, &errOut); code != 0 {
		t.Fatalf("code %d %s", code, errOut.String())
	}
	if _, err := os.Stat(unit); !os.IsNotExist(err) {
		t.Fatal("unit remains")
	}
	if _, err := os.Stat(filepath.Join(state, "devices.json")); err != nil {
		t.Fatal("state removed without --purge")
	}
	if f.calls[0] != "systemctl --user disable --now aristotle-server.service" {
		t.Fatal(f.calls)
	}
}

func TestPurgeConfirmation(t *testing.T) {
	_, _, state := useFakes(t)
	makeState(t, state)
	var out, errOut bytes.Buffer

	stdin = strings.NewReader("n\n")
	if code := Run([]string{"uninstall", "--purge"}, "v", &out, &errOut); code != 1 {
		t.Fatalf("declined code %d", code)
	}
	if _, err := os.Stat(state); err != nil {
		t.Fatal("state removed after declining")
	}

	stdin = strings.NewReader("y\n")
	if code := Run([]string{"uninstall", "--purge"}, "v", &out, &errOut); code != 0 {
		t.Fatalf("confirmed code %d %s", code, errOut.String())
	}
	if _, err := os.Stat(state); !os.IsNotExist(err) {
		t.Fatal("state not removed")
	}

	makeState(t, state)
	stdin = strings.NewReader("")
	if code := Run([]string{"uninstall", "--purge", "--yes"}, "v", &out, &errOut); code != 0 {
		t.Fatalf("--yes code %d", code)
	}
	if _, err := os.Stat(state); !os.IsNotExist(err) {
		t.Fatal("state not removed with --yes")
	}
	if code := Run([]string{"uninstall", "--yes"}, "v", &out, &errOut); code != 2 {
		t.Fatalf("--yes without --purge code %d", code)
	}
}

func TestStatusServerDown(t *testing.T) {
	f, _, _ := useFakes(t)
	f.out = map[string]string{
		"systemctl --user is-active aristotle-server.service":  "inactive\n",
		"systemctl --user is-enabled aristotle-server.service": "enabled\n",
	}
	var out, errOut bytes.Buffer
	if code := Run([]string{"status"}, "v", &out, &errOut); code != 0 {
		t.Fatalf("code %d", code)
	}
	for _, w := range []string{"inactive", "enabled", "not running"} {
		if !strings.Contains(out.String(), w) {
			t.Errorf("missing %q in %q", w, out.String())
		}
	}
}

func TestLogsCommand(t *testing.T) {
	f, _, _ := useFakes(t)
	var out, errOut bytes.Buffer
	if code := Run([]string{"logs", "-f"}, "v", &out, &errOut); code != 0 {
		t.Fatal(code)
	}
	if len(f.calls) != 1 || f.calls[0] != "journalctl --user -u aristotle-server -f" {
		t.Fatal(f.calls)
	}
}

func TestWatchSignalsSecondExits130(t *testing.T) {
	sigs := make(chan os.Signal, 2)
	cancelled := make(chan struct{})
	codes := make(chan int, 1)
	go watchSignals(sigs, func() { close(cancelled) }, func(c int) { codes <- c })
	sigs <- os.Interrupt
	select {
	case <-cancelled:
	case <-time.After(2 * time.Second):
		t.Fatal("first signal did not cancel")
	}
	select {
	case c := <-codes:
		t.Fatalf("exited on first signal: %d", c)
	case <-time.After(50 * time.Millisecond):
	}
	sigs <- os.Interrupt
	select {
	case c := <-codes:
		if c != 130 {
			t.Fatalf("code %d", c)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("second signal did not exit")
	}
}

func TestHTTPErrorsGoThroughSlog(t *testing.T) {
	var buf syncBuf
	log := slog.New(slog.NewTextHandler(&buf, nil))
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() {
		done <- serveListener(ctx, ln, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			panic("boom") // net/http logs this through Server.ErrorLog
		}), log)
	}()
	resp, err := http.Get("http://" + ln.Addr().String() + "/")
	if err == nil {
		resp.Body.Close()
	}
	deadline := time.Now().Add(2 * time.Second)
	for !strings.Contains(buf.String(), "boom") && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	cancel()
	if e := <-done; e != nil && !errors.Is(e, http.ErrServerClosed) {
		t.Fatal(e)
	}
	got := buf.String()
	if !strings.Contains(got, "boom") || !strings.Contains(got, "level=") {
		t.Fatalf("panic not logged via slog: %q", got)
	}
}

type syncBuf struct {
	mu sync.Mutex
	b  bytes.Buffer
}

func (s *syncBuf) Write(p []byte) (int, error) { s.mu.Lock(); defer s.mu.Unlock(); return s.b.Write(p) }
func (s *syncBuf) String() string              { s.mu.Lock(); defer s.mu.Unlock(); return s.b.String() }
