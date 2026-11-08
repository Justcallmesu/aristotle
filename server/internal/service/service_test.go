package service

import (
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

type call struct{ name, args string }

type fakeRunner struct {
	calls  []call
	output map[string]string // by joined args
	fail   map[string]bool
}

func (f *fakeRunner) Run(stdout, _ io.Writer, name string, args ...string) error {
	j := strings.Join(args, " ")
	f.calls = append(f.calls, call{name, j})
	if o, ok := f.output[j]; ok {
		io.WriteString(stdout, o)
	}
	if f.fail[j] {
		return errors.New("exit status 1")
	}
	return nil
}

func (f *fakeRunner) list() []string {
	var s []string
	for _, c := range f.calls {
		s = append(s, c.name+" "+c.args)
	}
	return s
}

func newMgr(t *testing.T, bin string) (*Manager, *fakeRunner) {
	t.Helper()
	f := &fakeRunner{}
	return &Manager{Dir: filepath.Join(t.TempDir(), "systemd", "user"), Bin: bin, Runner: f, Out: io.Discard, Err: io.Discard}, f
}

func TestUnitText(t *testing.T) {
	got, err := UnitText("/opt/a/aristotle-server")
	if err != nil {
		t.Fatal(err)
	}
	for _, w := range []string{"Type=simple\n", "ExecStart=/opt/a/aristotle-server serve\n", "Restart=on-failure\n", "RestartSec=5\n",
		"NoNewPrivileges=yes\n", "UMask=0077\n", "Delegate=yes\n", "WantedBy=default.target\n"} {
		if !strings.Contains(got, w) {
			t.Errorf("missing %q in\n%s", w, got)
		}
	}
	for _, bad := range []string{"HERMES_HOME", "StateDirectory", "Environment"} {
		if strings.Contains(got, bad) {
			t.Errorf("unit must not contain %q", bad)
		}
	}
}

func TestUnitTextQuotesPath(t *testing.T) {
	got, err := UnitText(`/home/a b/100%/bin`)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(got, `ExecStart="/home/a b/100%%/bin" serve`) {
		t.Fatalf("got %s", got)
	}
	if _, err := UnitText("relative/bin"); err == nil {
		t.Fatal("relative path accepted")
	}
	if _, err := UnitText("/a\nExecStart=/evil"); err == nil {
		t.Fatal("newline accepted")
	}
}

func TestUnitDirXDG(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", "/x/cfg")
	if d, _ := UnitDir(); d != "/x/cfg/systemd/user" {
		t.Fatal(d)
	}
	t.Setenv("XDG_CONFIG_HOME", "")
	t.Setenv("HOME", "/h")
	if d, _ := UnitDir(); d != "/h/.config/systemd/user" {
		t.Fatal(d)
	}
}

func TestInstallWritesAndEnables(t *testing.T) {
	m, f := newMgr(t, "/opt/aristotle-server")
	if err := m.Install(false, false); err != nil {
		t.Fatal(err)
	}
	want, _ := UnitText(m.Bin)
	b, err := os.ReadFile(m.UnitPath())
	if err != nil || string(b) != want {
		t.Fatalf("unit %q err %v", b, err)
	}
	got := strings.Join(f.list(), "|")
	if got != "systemctl --user daemon-reload|systemctl --user enable aristotle-server.service" {
		t.Fatal(got)
	}
}

func TestInstallStart(t *testing.T) {
	m, f := newMgr(t, "/opt/aristotle-server")
	if err := m.Install(false, true); err != nil {
		t.Fatal(err)
	}
	if l := f.list(); len(l) != 3 || l[2] != "systemctl --user start aristotle-server.service" {
		t.Fatal(l)
	}
}

func TestInstallIdempotentSameUnit(t *testing.T) {
	m, _ := newMgr(t, "/opt/aristotle-server")
	if err := m.Install(false, false); err != nil {
		t.Fatal(err)
	}
	if err := m.Install(false, false); err != nil {
		t.Fatalf("identical unit refused: %v", err)
	}
}

func TestInstallRefusesDifferentUnitUnlessForce(t *testing.T) {
	m, f := newMgr(t, "/opt/aristotle-server")
	os.MkdirAll(m.Dir, 0o755)
	os.WriteFile(m.UnitPath(), []byte("old\n"), 0o644)
	err := m.Install(false, false)
	if !errors.Is(err, ErrUnitDiffers) {
		t.Fatalf("err %v", err)
	}
	if b, _ := os.ReadFile(m.UnitPath()); string(b) != "old\n" || len(f.calls) != 0 {
		t.Fatal("refused install changed something")
	}
	if err := m.Install(true, false); err != nil {
		t.Fatal(err)
	}
	if b, _ := os.ReadFile(m.UnitPath()); string(b) == "old\n" {
		t.Fatal("force did not replace")
	}
}

func TestInstallEnableFailure(t *testing.T) {
	m, f := newMgr(t, "/opt/aristotle-server")
	f.fail = map[string]bool{"--user enable aristotle-server.service": true}
	if err := m.Install(false, true); err == nil || !strings.Contains(err.Error(), "enable") {
		t.Fatal(err)
	}
	for _, c := range f.calls {
		if strings.Contains(c.args, "start") {
			t.Fatal("started after failed enable")
		}
	}
}

func TestUninstall(t *testing.T) {
	m, f := newMgr(t, "/opt/aristotle-server")
	if err := m.Install(false, false); err != nil {
		t.Fatal(err)
	}
	f.calls = nil
	if err := m.Uninstall(); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(m.UnitPath()); !os.IsNotExist(err) {
		t.Fatal("unit still there")
	}
	got := strings.Join(f.list(), "|")
	if got != "systemctl --user disable --now aristotle-server.service|systemctl --user daemon-reload" {
		t.Fatal(got)
	}
}

func TestUninstallWhenNotInstalled(t *testing.T) {
	m, f := newMgr(t, "/opt/aristotle-server")
	f.fail = map[string]bool{"--user disable --now aristotle-server.service": true}
	if err := m.Uninstall(); err != nil {
		t.Fatalf("missing unit should be fine: %v", err)
	}
}

func TestState(t *testing.T) {
	m, f := newMgr(t, "/opt/aristotle-server")
	f.output = map[string]string{"--user is-active aristotle-server.service": "inactive\n"}
	f.fail = map[string]bool{"--user is-active aristotle-server.service": true}
	if s := m.State("is-active"); s != "inactive" {
		t.Fatal(s)
	}
	if s := m.State("is-enabled"); s != "unknown" {
		t.Fatal(s)
	}
}

func TestLogs(t *testing.T) {
	m, f := newMgr(t, "/opt/aristotle-server")
	m.Logs(false)
	m.Logs(true)
	got := strings.Join(f.list(), "|")
	if got != "journalctl --user -u aristotle-server|journalctl --user -u aristotle-server -f" {
		t.Fatal(got)
	}
}

// The whole unit, byte for byte: any change to it is deliberate (an installed unit that differs needs
// install --force).
func TestUnitTextGolden(t *testing.T) {
	got, err := UnitText("/opt/a/aristotle-server")
	if err != nil {
		t.Fatal(err)
	}
	const want = `[Unit]
Description=Aristotle server (relay between the Aristotle app and Hermes)

[Service]
Type=simple
ExecStart=/opt/a/aristotle-server serve
Restart=on-failure
RestartSec=5
NoNewPrivileges=yes
UMask=0077
Delegate=yes

[Install]
WantedBy=default.target
`
	if got != want {
		t.Fatalf("unit text:\n%s\nwant:\n%s", got, want)
	}
}

// systemd.exec(5): in user mode (no CAP_SYS_ADMIN) these options are only honoured by implicitly enabling
// PrivateUsers=, which hides other processes' /proc/<pid>/environ from the server and breaks the PC-terminal
// check (terminal.Hub.InTerminal fails closed). None may appear in the unit (TF1).
func TestUnitTextNoUserNamespaceOptions(t *testing.T) {
	got, err := UnitText("/opt/a/aristotle-server")
	if err != nil {
		t.Fatal(err)
	}
	for _, line := range strings.Split(got, "\n") {
		key := strings.TrimSpace(strings.SplitN(line, "=", 2)[0])
		for _, bad := range []string{"PrivateTmp", "PrivateUsers", "PrivateDevices", "PrivateNetwork", "PrivateMounts", "ProtectHome",
			"ProtectSystem", "ProtectKernel", "ProtectControlGroups", "ProtectClock", "ProtectHostname", "ProtectProc", "ProcSubset",
			"RestrictNamespaces", "ReadOnlyPaths", "ReadWritePaths", "InaccessiblePaths", "TemporaryFileSystem", "BindPaths",
			"BindReadOnlyPaths", "MountAPIVFS", "RootDirectory", "RootImage", "MemoryDenyWriteExecute", "LockPersonality",
			"RestrictSUIDSGID", "RestrictRealtime", "SystemCallFilter", "CapabilityBoundingSet"} {
			if strings.HasPrefix(key, bad) {
				t.Errorf("unit must not set %q (implies a private user namespace in a user unit): %q", bad, line)
			}
		}
	}
}

func TestInstalledUnitDelegates(t *testing.T) {
	cfg := t.TempDir()
	t.Setenv("XDG_CONFIG_HOME", cfg)
	if InstalledUnitDelegates() {
		t.Fatal("no unit file, yet delegates")
	}
	dir := filepath.Join(cfg, "systemd", "user")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	old := "[Service]\nExecStart=/bin/x serve\nUMask=0077\n"
	if err := os.WriteFile(filepath.Join(dir, UnitName), []byte(old), 0o644); err != nil {
		t.Fatal(err)
	}
	if InstalledUnitDelegates() {
		t.Fatal("a pre-T19a unit counts as delegating")
	}
	unit, _ := UnitText("/bin/x")
	if err := os.WriteFile(filepath.Join(dir, UnitName), []byte(unit), 0o644); err != nil {
		t.Fatal(err)
	}
	if !InstalledUnitDelegates() {
		t.Fatal("the current unit does not count as delegating")
	}
}

func TestInstallForceRestartsRunningService(t *testing.T) {
	m, f := newMgr(t, "/opt/aristotle-server")
	f.output = map[string]string{"--user is-active aristotle-server.service": "active\n"}
	if err := m.Install(true, false); err != nil {
		t.Fatal(err)
	}
	got := strings.Join(f.list(), "|")
	if !strings.HasSuffix(got, "systemctl --user restart aristotle-server.service") || !m.Restarted {
		t.Fatalf("calls %s restarted %v", got, m.Restarted)
	}
	for _, c := range f.calls {
		if c.args == "--user start aristotle-server.service" {
			t.Fatal("start after restart")
		}
	}
}

func TestInstallForceRestartFailureSaysUnitUpdated(t *testing.T) {
	m, f := newMgr(t, "/opt/aristotle-server")
	f.output = map[string]string{"--user is-active aristotle-server.service": "active\n"}
	f.fail = map[string]bool{"--user restart aristotle-server.service": true}
	err := m.Install(true, false)
	if !errors.Is(err, ErrRestartFailed) || m.Restarted {
		t.Fatalf("err %v restarted %v", err, m.Restarted)
	}
	for _, w := range []string{"exit status 1", m.UnitPath() + " was updated and enabled", "likely down", "journalctl --user -u aristotle-server"} {
		if !strings.Contains(err.Error(), w) {
			t.Errorf("missing %q in %q", w, err)
		}
	}
	if _, serr := os.Stat(m.UnitPath()); serr != nil {
		t.Fatal("unit not written", serr)
	}
}

func TestInstallForceInactiveDoesNotRestart(t *testing.T) {
	m, f := newMgr(t, "/opt/aristotle-server")
	f.output = map[string]string{"--user is-active aristotle-server.service": "inactive\n"}
	if err := m.Install(true, false); err != nil {
		t.Fatal(err)
	}
	for _, c := range f.calls {
		if strings.Contains(c.args, "restart") || strings.Contains(c.args, "start") {
			t.Fatalf("unexpected %v", c)
		}
	}
	if m.Restarted {
		t.Fatal("Restarted set")
	}
}

func TestInstallWithoutForceNeverRestarts(t *testing.T) {
	m, f := newMgr(t, "/opt/aristotle-server")
	f.output = map[string]string{"--user is-active aristotle-server.service": "active\n"}
	if err := m.Install(false, false); err != nil {
		t.Fatal(err)
	}
	if got := strings.Join(f.list(), "|"); got != "systemctl --user daemon-reload|systemctl --user enable aristotle-server.service" {
		t.Fatal(got)
	}
}

func TestInstallKeepsSymlinkedUnit(t *testing.T) {
	m, f := newMgr(t, "/opt/aristotle-server")
	os.MkdirAll(m.Dir, 0o755)
	target := filepath.Join(t.TempDir(), "managed.service")
	os.WriteFile(target, []byte("old\n"), 0o644)
	if err := os.Symlink(target, m.UnitPath()); err != nil {
		t.Fatal(err)
	}
	for _, force := range []bool{false, true} {
		err := m.Install(force, false)
		if !errors.Is(err, ErrUnitSymlink) || !strings.Contains(err.Error(), target) {
			t.Fatalf("force=%v err %v", force, err)
		}
	}
	if fi, _ := os.Lstat(m.UnitPath()); fi.Mode()&os.ModeSymlink == 0 {
		t.Fatal("symlink replaced by a file")
	}
	if b, _ := os.ReadFile(target); string(b) != "old\n" || len(f.calls) != 0 {
		t.Fatal("refused install changed something")
	}
	want, _ := UnitText(m.Bin)
	os.WriteFile(target, []byte(want), 0o644)
	if err := m.Install(true, false); err != nil {
		t.Fatalf("matching symlinked unit: %v", err)
	}
	if fi, _ := os.Lstat(m.UnitPath()); fi.Mode()&os.ModeSymlink == 0 {
		t.Fatal("symlink replaced by a file")
	}
}

func TestInstallRefusesDanglingSymlink(t *testing.T) {
	m, _ := newMgr(t, "/opt/aristotle-server")
	os.MkdirAll(m.Dir, 0o755)
	os.Symlink(filepath.Join(t.TempDir(), "missing"), m.UnitPath())
	if err := m.Install(true, false); !errors.Is(err, ErrUnitSymlink) {
		t.Fatal(err)
	}
}
