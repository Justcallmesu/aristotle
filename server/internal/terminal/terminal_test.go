//go:build linux

package terminal

import (
	"context"
	"encoding/json"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"testing"
	"time"

	"github.com/coder/websocket"
)

func TestEchoRoundTripBannerExitAndCleanLogs(t *testing.T) {
	h := newHarness(t, interactive, nil)
	p := h.open(t, h.token)
	p.waitOutput(t, "^"+regexpQuote(Banner))
	p.send(t, "echo SECRET_$((40+2))_MARK\n")
	p.waitOutput(t, "SECRET_42_MARK")
	p.send(t, "exit 3\n")
	code, _, texts := p.waitClose(t, 5*time.Second)
	if code != CodeExit {
		t.Fatalf("close %d, want 1000", code)
	}
	if len(texts) != 1 || texts[0] != `{"type":"exit","code":3}` {
		t.Fatalf("texts %q, want the exit message", texts)
	}
	waitLog(t, h, "terminal closed")
	logs := h.logs.String()
	for _, want := range []string{"terminal open", "terminal exit", "exit_code=3", "close_code=1000", "closed_by=shell", "bytes_in=", "bytes_out=", "terminal_id=term_"} {
		if !strings.Contains(logs, want) {
			t.Errorf("log lacks %q:\n%s", want, logs)
		}
	}
	for _, secret := range []string{"SECRET", "40+2", "/bin/sh", h.token} {
		if strings.Contains(logs, secret) {
			t.Errorf("log holds terminal content or a secret %q:\n%s", secret, logs)
		}
	}
}

func regexpQuote(s string) string {
	var b strings.Builder
	for _, r := range s {
		if strings.ContainsRune(`\.+*?()|[]{}^$`, r) {
			b.WriteRune('\\')
		}
		b.WriteRune(r)
	}
	return b.String()
}

func waitLog(t *testing.T, h *harness, what string) {
	t.Helper()
	for deadline := time.Now().Add(5 * time.Second); time.Now().Before(deadline); time.Sleep(5 * time.Millisecond) {
		if strings.Contains(h.logs.String(), what) {
			return
		}
	}
	t.Fatalf("no %q in log:\n%s", what, h.logs.String())
}

// Disabled, ungranted and revoked devices are refused before any upgrade, and no shell starts.
func TestRefusedBeforeUpgrade(t *testing.T) {
	h := newHarness(t, interactive, nil)
	other, otherToken, _ := h.store.Add("other") // paired, never granted
	_ = other
	cases := []struct {
		name, token string
		prep        func()
		status      int
		body        string
	}{
		{"not granted", otherToken, func() {}, http.StatusForbidden, `{"error":"terminal_not_granted"}`},
		{"disabled", h.token, func() { changes(t)(h.grants.SetEnabled(false)) }, http.StatusForbidden, `{"error":"terminal_disabled"}`},
		{"revoked", h.token, func() { _, _ = h.store.Revoke(h.dev) }, http.StatusUnauthorized, `{"error":"unauthorized"}`},
	}
	for _, c := range cases {
		c.prep()
		_, resp, err := h.dial(t, c.token)
		if err == nil || resp == nil || resp.StatusCode != c.status || strings.TrimSpace(readBody(resp)) != c.body {
			t.Fatalf("%s: err %v resp %v, want %d %s", c.name, err, resp, c.status, c.body)
		}
	}
	if n := h.spawns.Load(); n != 0 {
		t.Fatalf("%d shells started for refused devices", n)
	}
}

// A revoke, disable or ungrant that lands after the HTTP check but before register finds no terminal to
// close; the re-check after register catches it: the socket closes and no shell ever starts.
func TestRaceBeforeRegisterStartsNoShell(t *testing.T) {
	cases := []struct {
		name   string
		act    func(h *harness)
		code   websocket.StatusCode
		reason string
	}{
		{"revoke", func(h *harness) { _, _ = h.store.Revoke(h.dev) }, CodeRevoked, "revoked"},
		{"disable", func(h *harness) { _, _ = h.grants.SetEnabled(false) }, CodeForbidden, "terminal_disabled"},
		{"ungrant", func(h *harness) { _, _ = h.grants.Ungrant(h.dev) }, CodeForbidden, "terminal_not_granted"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			var h *harness
			h = newHarness(t, interactive, func(o *Options) {
				o.beforeRegister = func() { c.act(h) }
			})
			p := h.open(t, h.token)
			code, reason, texts := p.waitClose(t, 5*time.Second)
			if code != c.code || reason != c.reason {
				t.Fatalf("close %d %q, want %d %q", code, reason, c.code, c.reason)
			}
			if len(texts) != 1 || !strings.Contains(texts[0], `"type":"closing"`) || !strings.Contains(texts[0], c.reason) {
				t.Fatalf("texts %q", texts)
			}
			if n := h.spawns.Load(); n != 0 {
				t.Fatalf("a shell started (%d)", n)
			}
		})
	}
}

// Disable, ungrant and revoke close a running terminal at once and kill its processes.
func TestDisableUngrantRevokeKillRunningShell(t *testing.T) {
	cases := []struct {
		name   string
		act    func(h *harness)
		code   websocket.StatusCode
		reason string
	}{
		{"disable", func(h *harness) { _, _ = h.grants.SetEnabled(false) }, CodeForbidden, "terminal_disabled"},
		{"ungrant", func(h *harness) { _, _ = h.grants.Ungrant(h.dev) }, CodeForbidden, "terminal_not_granted"},
		{"revoke", func(h *harness) { _, _ = h.store.Revoke(h.dev) }, CodeRevoked, "revoked"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			h := newHarness(t, script(`trap "" HUP; sleep 300 & echo bg=$!; wait`), func(o *Options) { o.KillGrace = time.Minute })
			p := h.open(t, h.token)
			bg := atoi(t, p.waitOutput(t, `bg=(\d+)`)[1])
			shell := int(h.only(t).pid.Load())
			start := time.Now()
			c.act(h)
			code, reason, _ := p.waitClose(t, 2*time.Second)
			if code != c.code || reason != c.reason {
				t.Fatalf("close %d %q, want %d %q", code, reason, c.code, c.reason)
			}
			// KillGrace is a minute and the shell ignores SIGHUP: only the immediate kill meets this.
			gone(t, bg, 2*time.Second)
			gone(t, shell, 2*time.Second)
			if d := time.Since(start); d > 3*time.Second {
				t.Fatalf("took %v", d)
			}
		})
	}
}

// Disable races with many terminals starting: whatever the interleaving, no process survives.
func TestRacingDisableLeavesNoProcess(t *testing.T) {
	marker := "T19A_RACE_" + strings.ReplaceAll(t.Name(), "/", "_") + "_" + time.Now().Format("150405.000000")
	t.Setenv("T19A_RACE_MARKER", marker)
	h := newHarness(t, script(`sleep 300 & wait`), nil)
	for round := range 15 {
		_, _ = h.grants.SetEnabled(true)
		var wg sync.WaitGroup
		for range 3 {
			wg.Go(func() {
				c, _, err := h.dial(t, h.token)
				if err != nil {
					return // refused at the HTTP check
				}
				_, _, _ = c.Read(context.Background())
			})
		}
		time.Sleep(time.Duration(round) * time.Millisecond)
		_, _ = h.grants.SetEnabled(false)
		wg.Wait()
	}
	for deadline := time.Now().Add(5 * time.Second); len(h.hub.all()) > 0 && time.Now().Before(deadline); {
		time.Sleep(10 * time.Millisecond)
	}
	if n := len(h.hub.all()); n != 0 {
		t.Fatalf("%d terminals left", n)
	}
	time.Sleep(100 * time.Millisecond)
	if left := survivors("T19A_RACE_MARKER=" + marker); len(left) != 0 {
		t.Fatalf("processes survived: %v", left)
	}
	if h.spawns.Load() == 0 {
		t.Log("no shell started in any round (all refused); the race window was not reached")
	}
}

// Idle time counts input only: output keeps flowing, yet the terminal closes 30 minutes after the last
// keystroke, and a keystroke resets the count.
func TestIdleTimeoutCountsInputOnly(t *testing.T) {
	h := newHarness(t, script(`while :; do echo tick; sleep 0.02; done`), func(o *Options) { o.CheckEvery = 5 * time.Millisecond })
	p := h.open(t, h.token)
	p.waitOutput(t, "tick")
	h.clock.Add(20 * time.Minute)
	p.send(t, "x")
	waitFor(t, "input recorded", func() bool { return h.only(t).bytesIn.Load() == 1 })
	h.clock.Add(29 * time.Minute) // 49 min open, 29 since the input, output all along
	p.stillOpen(t, 100*time.Millisecond)
	h.clock.Add(time.Minute + time.Second)
	code, reason, texts := p.waitClose(t, 5*time.Second)
	if code != CodeTimeLimit || reason != "idle_timeout" {
		t.Fatalf("close %d %q, want 4408 idle_timeout", code, reason)
	}
	if len(texts) != 1 || texts[0] != `{"type":"closing","reason":"idle_timeout"}` {
		t.Fatalf("texts %q", texts)
	}
}

func TestMaxSessionLength(t *testing.T) {
	h := newHarness(t, script(`cat >/dev/null`), func(o *Options) { o.CheckEvery = 5 * time.Millisecond })
	p := h.open(t, h.token)
	h.only(t)
	for i := range 16 { // input every 29 min: never idle
		h.clock.Add(29 * time.Minute)
		p.send(t, "k")
		waitFor(t, "input", func() bool { return h.only(t).bytesIn.Load() == int64(i+1) })
	}
	p.stillOpen(t, 50*time.Millisecond) // 7 h 44 min
	h.clock.Add(16 * time.Minute)
	code, reason, _ := p.waitClose(t, 5*time.Second)
	if code != CodeTimeLimit || reason != "max_session" {
		t.Fatalf("close %d %q, want 4408 max_session", code, reason)
	}
}

func waitFor(t *testing.T, what string, cond func() bool) {
	t.Helper()
	for deadline := time.Now().Add(5 * time.Second); time.Now().Before(deadline); time.Sleep(2 * time.Millisecond) {
		if cond() {
			return
		}
	}
	t.Fatalf("timed out waiting for %s", what)
}

func TestPerDeviceAndTotalCaps(t *testing.T) {
	h := newHarness(t, script(`cat >/dev/null`), nil)
	open := func(token string) *phone {
		p := h.open(t, token)
		return p
	}
	a1, a2 := open(h.token), open(h.token)
	waitFor(t, "two terminals", func() bool { return len(h.hub.all()) == 2 })
	if code, reason, _ := open(h.token).waitClose(t, 5*time.Second); code != CodeTooMany || reason != "too_many_terminals" {
		t.Fatalf("third for one device: %d %q, want 4430", code, reason)
	}
	_, b := h.addDevice(t, "b")
	_, c := h.addDevice(t, "c")
	open(b)
	open(b)
	waitFor(t, "four terminals", func() bool { return len(h.hub.all()) == 4 })
	if code, _, _ := open(c).waitClose(t, 5*time.Second); code != CodeTooMany {
		t.Fatalf("fifth in total: %d, want 4430", code)
	}
	a1.c.Close(websocket.StatusNormalClosure, "")
	a1.waitClose(t, 5*time.Second)
	waitFor(t, "three terminals", func() bool { return len(h.hub.all()) == 3 })
	open(c).stillOpen(t, 100*time.Millisecond)
	_ = a2
}

func TestClampSize(t *testing.T) {
	for _, c := range []struct{ cols, rows, wantC, wantR int }{
		{80, 24, 80, 24}, {0, 0, 20, 5}, {-5, 1, 20, 5}, {1000, 1000, 500, 200}, {70000, 70000, 500, 200}, {20, 5, 20, 5}, {500, 200, 500, 200},
	} {
		if gc, gr := clampSize(c.cols, c.rows); gc != c.wantC || gr != c.wantR {
			t.Errorf("clamp(%d,%d) = %d,%d want %d,%d", c.cols, c.rows, gc, gr, c.wantC, c.wantR)
		}
	}
}

func TestResizeIsClampedOnThePTY(t *testing.T) {
	h := newHarness(t, interactive, nil)
	p := h.open(t, h.token)
	p.waitOutput(t, "Aristotle terminal")
	p.control(t, map[string]any{"type": "resize", "cols": 1000, "rows": 1})
	p.send(t, "stty size\n")
	p.waitOutput(t, `\b5 500\b`)
	p.control(t, map[string]any{"type": "resize", "cols": 132, "rows": 43})
	p.control(t, map[string]any{"type": "future_thing"}) // ignored
	p.send(t, "stty size\n")
	p.waitOutput(t, `\b43 132\b`)
	if err := p.c.Write(context.Background(), websocket.MessageText, []byte("not json PRIVATE")); err != nil {
		t.Fatal(err)
	}
	if code, reason, _ := p.waitClose(t, 5*time.Second); code != CodeBadControl || reason != "bad_control_frame" {
		t.Fatalf("close %d %q, want 1008", code, reason)
	}
	waitLog(t, h, "terminal closed")
	if strings.Contains(h.logs.String(), "PRIVATE") {
		t.Fatal("control frame text reached the log")
	}
}

func TestFrameCap(t *testing.T) {
	h := newHarness(t, script(`cat >/dev/null`), nil)
	p := h.open(t, h.token)
	h.only(t)
	p.send(t, strings.Repeat("a", MaxFrameBytes)) // at the cap: fine
	p.stillOpen(t, 100*time.Millisecond)
	_ = p.c.Write(context.Background(), websocket.MessageBinary, make([]byte, MaxFrameBytes+1))
	if code, _, _ := p.waitClose(t, 5*time.Second); code != CodeTooBig {
		t.Fatalf("close %d, want 1009", code)
	}
}

func TestByteRateLetsTypingThroughAndSlowsPastes(t *testing.T) {
	r := newByteRate(InputBytesPerSecond, InputBurstBytes)
	now := time.Unix(0, 0)
	for i := range 2000 { // 2000 keystrokes, 100 per second: never delayed
		if d := r.take(now.Add(time.Duration(i)*10*time.Millisecond), 1); d != 0 {
			t.Fatalf("keystroke %d delayed %v", i, d)
		}
	}
	r = newByteRate(InputBytesPerSecond, InputBurstBytes)
	if d := r.take(now, InputBurstBytes); d != 0 {
		t.Fatalf("first burst delayed %v", d)
	}
	if d := r.take(now, InputBurstBytes); d != time.Second {
		t.Fatalf("second burst at once: wait %v, want 1s", d)
	}
	// One second refills the burst and pays the debt: half a burst more is half a second.
	if d := r.take(now.Add(time.Second), InputBurstBytes/2); d != 500*time.Millisecond {
		t.Fatalf("after the debt: wait %v, want 0.5s", d)
	}
}

// A shell that writes as fast as it can (yes) to a phone that does not read: the server stops reading
// the PTY, so its output count and its memory stay flat; once the phone reads, output flows again.
func TestBackpressureBoundsMemory(t *testing.T) {
	h := newHarness(t, script(`exec yes`), nil)
	c, _, err := h.dial(t, h.token) // never read until the end
	if err != nil {
		t.Fatal(err)
	}
	s := h.only(t)
	runtime.GC()
	var before runtime.MemStats
	runtime.ReadMemStats(&before)
	time.Sleep(time.Second)
	stalled := s.bytesOut.Load()
	time.Sleep(500 * time.Millisecond)
	if now := s.bytesOut.Load(); now != stalled {
		t.Fatalf("output kept flowing to a phone that does not read: %d -> %d", stalled, now)
	}
	if stalled > 32<<20 {
		t.Fatalf("%d bytes sent before stalling", stalled)
	}
	runtime.GC()
	var after runtime.MemStats
	runtime.ReadMemStats(&after)
	if grow := int64(after.HeapInuse) - int64(before.HeapInuse); grow > 8<<20 {
		t.Fatalf("heap grew %d bytes while stalled", grow)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	for s.bytesOut.Load() < stalled+(1<<20) {
		if _, _, err := c.Read(ctx); err != nil {
			t.Fatal(err)
		}
	}
	c.CloseNow()
}

// Closing the socket ends every process of the shell's session: a background job, a job in its own
// process group, and one that ignores SIGHUP (SIGKILL after KillGrace).
func TestCloseKillsTheSession(t *testing.T) {
	h := newHarness(t, script(`
sleep 300 & echo a=$!
(trap "" HUP; exec sleep 301) & echo b=$!
set -m 2>/dev/null; sleep 302 & echo c=$!
wait`), nil)
	p := h.open(t, h.token)
	m := p.waitOutput(t, `a=(\d+)[\s\S]*b=(\d+)[\s\S]*c=(\d+)`)
	shell := int(h.only(t).pid.Load())
	pids := []int{atoi(t, m[1]), atoi(t, m[2]), atoi(t, m[3])}
	if pg, _ := syscall.Getpgid(pids[2]); pg == shell {
		t.Log("sh has no job control here: the third job shares the shell's group")
	}
	p.c.Close(websocket.StatusNormalClosure, "")
	for _, pid := range append(pids, shell) {
		gone(t, pid, 3*time.Second)
	}
	waitLog(t, h, "terminal closed")
	for _, want := range []string{"method=session", "killed=1", "may survive"} { // the sleep that ignores SIGHUP
		if !strings.Contains(h.logs.String(), want) {
			t.Fatalf("log lacks %q:\n%s", want, h.logs.String())
		}
	}
}

// With a delegated cgroup (systemd Delegate=yes), even a process that left the session (setsid) dies
// with its terminal, and the cgroup is removed. To run it for real:
//
//	ARISTOTLE_TEST_CGROUP_DELEGATED=1 systemd-run --user --scope -p Delegate=yes ./terminal.test -test.run Cgroup
func TestCloseKillsTheCgroup(t *testing.T) {
	unit := ""
	if os.Getenv("ARISTOTLE_TEST_CGROUP_DELEGATED") == "1" { // the runner vouches: this scope has Delegate=yes
		if path, err := procCgroup(os.Getpid()); err == nil {
			unit = filepath.Base(path)
		}
	}
	if cg, why := detectCgroup(unit); cg == nil {
		t.Skipf("no delegated cgroup here (%s); run the test binary with ARISTOTLE_TEST_CGROUP_DELEGATED=1 under "+
			"systemd-run --user --scope -p Delegate=yes", why)
	}
	h := newHarness(t, script(`setsid sleep 303 & sleep 0.2; echo ready; wait`), func(o *Options) {
		o.NoCgroup, o.DelegatedUnit = false, unit
	})
	if h.hub.cg == nil {
		t.Fatal("hub found no cgroup")
	}
	p := h.open(t, h.token)
	p.waitOutput(t, "ready")
	s := h.only(t)
	if s.proc.cg == nil {
		t.Fatal("shell not started in a cgroup")
	}
	escaped := 0
	for _, pid := range sessionMembersOrAll(s) {
		if info, _ := readProcStat(pid); info.sid != int(s.pid.Load()) {
			escaped = pid
		}
	}
	if escaped == 0 {
		t.Fatal("no setsid process found in the cgroup")
	}
	if how, in := h.hub.InTerminal(escaped); !in || (how != "env" && how != "cgroup") {
		t.Fatalf("InTerminal(setsid child) = %q %v", how, in)
	}
	dir := s.proc.cg.dir
	p.c.Close(websocket.StatusNormalClosure, "")
	gone(t, escaped, 3*time.Second)
	waitFor(t, "cgroup removed", func() bool { _, err := os.Stat(dir); return os.IsNotExist(err) })
	waitLog(t, h, "method=cgroup")
}

// sessionMembersOrAll lists the processes in the terminal's cgroup.
func sessionMembersOrAll(s *session) []int {
	b, _ := os.ReadFile(s.proc.cg.dir + "/cgroup.procs")
	var out []int
	for _, f := range strings.Fields(string(b)) {
		if n, err := strconv.Atoi(f); err == nil {
			out = append(out, n)
		}
	}
	return out
}

// InTerminal sees a terminal process by its marker, and one whose marker was removed by its ancestry.
func TestInTerminal(t *testing.T) {
	h := newHarness(t, script(`sleep 300 & echo f=$!; env -u ARISTOTLE_TERMINAL sleep 301 & echo g=$!
env -u ARISTOTLE_TERMINAL setsid sleep 302 & echo h=$!; sleep 0.2; echo ready; wait`), nil)
	p := h.open(t, h.token)
	m := p.waitOutput(t, `f=(\d+)[\s\S]*g=(\d+)[\s\S]*h=(\d+)[\s\S]*ready`)
	// Left the session and dropped the marker, but its parent is still the shell.
	if how, in := h.hub.InTerminal(atoi(t, m[3])); !in || !strings.HasPrefix(how, "ancestry:term_") {
		t.Fatalf("setsid child without marker: %q %v", how, in)
	}
	if how, in := h.hub.InTerminal(atoi(t, m[1])); !in || how != "env" {
		t.Fatalf("marked child: %q %v", how, in)
	}
	if how, in := h.hub.InTerminal(atoi(t, m[2])); !in || !(strings.HasPrefix(how, "ancestry:term_") || strings.HasPrefix(how, "session:term_")) {
		t.Fatalf("unmarked child: %q %v", how, in)
	}
	if how, in := h.hub.InTerminal(os.Getpid()); in {
		t.Fatalf("the test process itself: %q", how)
	}
	marked := exec.Command("sleep", "30")
	marked.Env = append(os.Environ(), "ARISTOTLE_TERMINAL=forged")
	if err := marked.Start(); err != nil {
		t.Fatal(err)
	}
	defer func() { marked.Process.Kill(); marked.Wait() }()
	waitFor(t, "exec of sleep", func() bool { // until exec has set up the new environment, /proc shows another
		env, _ := os.ReadFile("/proc/" + strconv.Itoa(marked.Process.Pid) + "/environ")
		return strings.Contains(string(env), "ARISTOTLE_TERMINAL=forged")
	})
	if how, in := h.hub.InTerminal(marked.Process.Pid); !in || how != "env" {
		t.Fatalf("marked outsider: %q %v", how, in)
	}
	for _, pid := range []int{0, -1, 1 << 30} {
		if _, in := h.hub.InTerminal(pid); !in {
			t.Fatalf("unreadable peer %d not treated as inside", pid)
		}
	}
}

func TestShutdownClosesGoingAwayAndKills(t *testing.T) {
	h := newHarness(t, script(`sleep 300 & echo bg=$!; wait`), nil)
	p := h.open(t, h.token)
	bg := atoi(t, p.waitOutput(t, `bg=(\d+)`)[1])
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := h.hub.Shutdown(ctx); err != nil {
		t.Fatal(err)
	}
	if code, _, _ := p.waitClose(t, time.Second); code != CodeGoingAway {
		t.Fatalf("close %d, want 1001", code)
	}
	gone(t, bg, time.Second)
	if code, _, _ := h.open(t, h.token).waitClose(t, 5*time.Second); code != CodeGoingAway {
		t.Fatalf("new terminal during shutdown: %d", code)
	}
}

func TestKeepaliveClosesWithoutPong(t *testing.T) {
	h := newHarness(t, script(`cat >/dev/null`), func(o *Options) {
		o.PingInterval, o.PongTimeout = 50*time.Millisecond, 100*time.Millisecond
	})
	c, _, err := h.dial(t, h.token) // never reads: pings go unanswered
	if err != nil {
		t.Fatal(err)
	}
	_ = c
	waitLog(t, h, "close_reason=pong_timeout")
}

func TestKeepaliveKeepsReadingPhone(t *testing.T) {
	h := newHarness(t, script(`cat >/dev/null`), func(o *Options) {
		o.PingInterval, o.PongTimeout = 20*time.Millisecond, 100*time.Millisecond
	})
	h.open(t, h.token).stillOpen(t, 400*time.Millisecond)
}

// blockedPTY is a program that never reads its input, on a raw PTY: once the PTY's input buffers are
// full, a write to the master blocks.
const blockedPTY = `stty raw -echo; echo ready; exec sleep 300`

// pasteIntoBlockedPTY sends n 64 KiB frames (one is more than the PTY holds) without waiting for them.
func pasteIntoBlockedPTY(c *websocket.Conn, n int) {
	go func() {
		for range n {
			if c.Write(context.Background(), websocket.MessageBinary, make([]byte, MaxFrameBytes)) != nil {
				return
			}
		}
	}()
}

// T19a review m5: a phone that vanishes while a PTY write is blocked is dropped by keepalive (pong
// timeout), not left to the 30 min idle limit: the blocked write does not stop the phone being read.
func TestKeepaliveDropsVanishedPhoneWhilePTYWriteBlocks(t *testing.T) {
	h := newHarness(t, script(blockedPTY), func(o *Options) {
		o.PingInterval, o.PongTimeout = 50*time.Millisecond, 200*time.Millisecond
	})
	c, _, err := h.dial(t, h.token)
	if err != nil {
		t.Fatal(err)
	}
	var out strings.Builder
	for !strings.Contains(out.String(), "ready") { // read only until the PTY is raw, then never again
		_, msg, err := c.Read(context.Background())
		if err != nil {
			t.Fatal(err)
		}
		out.Write(msg)
	}
	s := h.only(t)
	pasteIntoBlockedPTY(c, 2)
	for deadline := time.Now().Add(10 * time.Second); ; time.Sleep(10 * time.Millisecond) {
		if why, _ := s.closeState(); why == closePongLost {
			break
		} else if why.code != 0 || time.Now().After(deadline) {
			t.Fatalf("close %+v, want pong_timeout within 10s", why)
		}
	}
	waitLog(t, h, "close_reason=pong_timeout")
	if in := s.bytesIn.Load(); in >= 2*MaxFrameBytes {
		t.Fatalf("the PTY took all %d bytes: no write was blocked", in)
	}
}

// The same blocked PTY with a phone that is there: it keeps its terminal.
func TestKeepaliveKeepsLivePhoneWhilePTYWriteBlocks(t *testing.T) {
	h := newHarness(t, script(blockedPTY), func(o *Options) {
		o.PingInterval, o.PongTimeout = 50*time.Millisecond, 200*time.Millisecond
	})
	p := h.open(t, h.token)
	p.waitOutput(t, "ready")
	s := h.only(t)
	pasteIntoBlockedPTY(p.c, 3) // the reader also waits on the byte rate twice
	p.stillOpen(t, 3500*time.Millisecond)
	if in := s.bytesIn.Load(); in >= 3*MaxFrameBytes {
		t.Fatalf("the PTY took all %d bytes: no write was blocked", in)
	}
}

func TestInputQueueIsBoundedInBytes(t *testing.T) {
	q := newInputQueue(10)
	big := make([]byte, 25)
	if !q.tryPush(big) {
		t.Fatal("one frame must always fit in an empty queue")
	}
	if q.tryPush([]byte("x")) {
		t.Fatal("queued past the bound")
	}
	done := make(chan struct{})
	pushed := make(chan bool)
	go func() { pushed <- q.push([]byte("abc"), done) }()
	if msg := q.next(done); len(msg) != 25 {
		t.Fatalf("next = %d bytes", len(msg))
	}
	select {
	case <-pushed:
		t.Fatal("room freed before the frame was written")
	case <-time.After(50 * time.Millisecond):
	}
	q.written(25)
	if !<-pushed {
		t.Fatal("push failed")
	}
	if msg := q.next(done); string(msg) != "abc" {
		t.Fatalf("next = %q", msg)
	}
	close(done)
	if q.next(done) != nil {
		t.Fatal("next after done")
	}
}

// T19a review m1: the shell does not get the desktop session's variables, except those the config keeps.
func TestShellDoesNotInheritTheDesktopSession(t *testing.T) {
	t.Setenv("DISPLAY", ":7")
	t.Setenv("DBUS_SESSION_BUS_ADDRESS", "unix:path=/run/user/1/bus")
	t.Setenv("SSH_AUTH_SOCK", "/run/user/1/agent")
	h := newHarness(t, script(`echo "d=[$DISPLAY] b=[$DBUS_SESSION_BUS_ADDRESS] s=[$SSH_AUTH_SOCK]"; sleep 30`), func(o *Options) {
		o.KeepEnv = []string{"SSH_AUTH_SOCK"}
	})
	p := h.open(t, h.token)
	p.waitOutput(t, `d=\[\] b=\[\] s=\[/run/user/1/agent\]`)
}

func TestMeFlags(t *testing.T) {
	h := newHarness(t, interactive, nil)
	_, otherToken, _ := h.store.Add("other")
	me := func(token string) string {
		req, _ := http.NewRequest("GET", h.srv.URL+"/v1/me", nil)
		req.Header.Set("Authorization", "Bearer "+token)
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		var v struct {
			Terminal json.RawMessage `json:"terminal"`
		}
		_ = json.NewDecoder(resp.Body).Decode(&v)
		return string(v.Terminal)
	}
	if got := me(h.token); got != `{"enabled":true,"granted":true}` {
		t.Fatalf("granted: %s", got)
	}
	if got := me(otherToken); got != `{"enabled":true,"granted":false}` {
		t.Fatalf("not granted: %s", got)
	}
	_, _ = h.grants.SetEnabled(false)
	if got := me(h.token); got != `{"enabled":false,"granted":true}` {
		t.Fatalf("disabled: %s", got)
	}
}
