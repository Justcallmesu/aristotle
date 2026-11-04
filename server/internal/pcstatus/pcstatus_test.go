package pcstatus

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func put(t *testing.T, root, rel, body string) {
	t.Helper()
	p := filepath.Join(root, rel)
	if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(p, []byte(body), 0o644); err != nil {
		t.Fatal(err)
	}
}

func supply(t *testing.T, root, name, typ, capacity, status string) {
	put(t, root, "sys/class/power_supply/"+name+"/type", typ+"\n")
	if capacity != "" {
		put(t, root, "sys/class/power_supply/"+name+"/capacity", capacity+"\n")
	}
	if status != "" {
		put(t, root, "sys/class/power_supply/"+name+"/status", status+"\n")
	}
}

func TestBattery(t *testing.T) {
	root := t.TempDir()
	if ReadBattery(root) != nil {
		t.Error("missing sys tree must be nil")
	}
	supply(t, root, "AC", "Mains", "", "")
	supply(t, root, "ADP1", "Mains", "100", "Charging")
	if ReadBattery(root) != nil {
		t.Error("only AC/ADP: want nil")
	}
	supply(t, root, "BAT0", "Battery", "82", "Charging")
	if b := ReadBattery(root); b == nil || b.Percent != 82 || !b.Charging {
		t.Errorf("charging = %+v", b)
	}
	supply(t, root, "BAT0", "Battery", "100", "Full")
	if b := ReadBattery(root); b == nil || !b.Charging {
		t.Errorf("full = %+v", b)
	}
	for _, st := range []string{"Discharging", "Not charging", "Unknown", ""} {
		supply(t, root, "BAT0", "Battery", "40", st)
		if b := ReadBattery(root); b == nil || b.Percent != 40 || b.Charging {
			t.Errorf("status %q = %+v", st, b)
		}
	}
	for _, c := range []string{"abc", "101", "-1", ""} {
		supply(t, root, "BAT0", "Battery", c, "Charging")
		if c == "" {
			_ = os.Remove(filepath.Join(root, "sys/class/power_supply/BAT0/capacity"))
		}
		if ReadBattery(root) != nil {
			t.Errorf("capacity %q must be nil", c)
		}
	}
	// A peripheral battery (scope Device) is not the PC.
	other := t.TempDir()
	supply(t, other, "hidpp_battery_0", "Battery", "50", "Discharging")
	put(t, other, "sys/class/power_supply/hidpp_battery_0/scope", "Device\n")
	if ReadBattery(other) != nil {
		t.Error("device-scope battery must be ignored")
	}
}

func TestCPU(t *testing.T) {
	root := t.TempDir()
	if ReadCPUTimes(root) != nil {
		t.Error("missing file")
	}
	put(t, root, "proc/stat", "cpu  100 0 100 700 100 0 0 0 0 0\ncpu0 1 1 1 1 1 0 0 0 0 0\n")
	a := ReadCPUTimes(root)
	if a == nil || a.Total != 1000 || a.Idle != 800 {
		t.Fatalf("a = %+v", a)
	}
	put(t, root, "proc/stat", "cpu  200 0 200 750 150 0 0 0 0 0\n")
	b := ReadCPUTimes(root)
	if p := CPUPercent(a, b); p == nil || *p != 66.7 { // dTotal 300, dIdle 100
		t.Errorf("percent = %v", p)
	}
	if CPUPercent(a, a) != nil || CPUPercent(nil, b) != nil || CPUPercent(b, a) != nil {
		t.Error("no elapsed time or a missing sample must be nil")
	}
	for _, g := range []string{"", "garbage", "cpu  1 2", "cpu  a b c d e", "intr 1 2 3 4 5"} {
		put(t, root, "proc/stat", g)
		if ReadCPUTimes(root) != nil {
			t.Errorf("garbage %q must be nil", g)
		}
	}
}

func TestMemory(t *testing.T) {
	root := t.TempDir()
	if ReadMemory(root) != nil {
		t.Error("missing file")
	}
	put(t, root, "proc/meminfo", "MemTotal:       16000 kB\nMemFree:         100 kB\nMemAvailable:    4000 kB\n")
	if m := ReadMemory(root); m == nil || m.TotalBytes != 16000*1024 || m.UsedBytes != 12000*1024 {
		t.Errorf("mem = %+v", m)
	}
	for _, g := range []string{"", "junk", "MemTotal: 100 kB\n", "MemAvailable: 1 kB\n", "MemTotal: x kB\nMemAvailable: 1 kB\n",
		"MemTotal: 10 kB\nMemAvailable: 20 kB\n", "MemTotal:\nMemAvailable: 1 kB\n"} {
		put(t, root, "proc/meminfo", g)
		if ReadMemory(root) != nil {
			t.Errorf("garbage %q must be nil", g)
		}
	}
}

func TestUptime(t *testing.T) {
	root := t.TempDir()
	if ReadUptime(root) != nil {
		t.Error("missing file")
	}
	put(t, root, "proc/uptime", "86400.57 1234.00\n")
	if u := ReadUptime(root); u == nil || *u != 86400 {
		t.Errorf("uptime = %v", u)
	}
	for _, g := range []string{"", "soon", "-5 1"} {
		put(t, root, "proc/uptime", g)
		if ReadUptime(root) != nil {
			t.Errorf("garbage %q must be nil", g)
		}
	}
}

func TestDisk(t *testing.T) {
	dir := t.TempDir()
	d := ReadDisk(dir)
	if d == nil || d.Path != "~" || d.TotalBytes == 0 || d.FreeBytes > d.TotalBytes {
		t.Errorf("disk = %+v", d)
	}
	if ReadDisk(filepath.Join(dir, "nope")) != nil || ReadDisk("") != nil {
		t.Error("missing path must be nil")
	}
}

func fakeRoot(t *testing.T) string {
	root := t.TempDir()
	put(t, root, "proc/stat", "cpu  100 0 100 700 100 0 0 0 0 0\n")
	put(t, root, "proc/meminfo", "MemTotal: 1000 kB\nMemAvailable: 250 kB\n")
	put(t, root, "proc/uptime", "42.0 1.0\n")
	supply(t, root, "BAT0", "Battery", "55", "Discharging")
	return root
}

func TestStatusAssemblesEveryField(t *testing.T) {
	home := t.TempDir()
	c := &Collector{Root: fakeRoot(t), Home: home, manual: true, Idle: time.Minute,
		Hermes:    func(context.Context) (bool, error) { return false, nil },
		Tailscale: func() bool { return true },
		Now:       func() time.Time { return time.Date(2026, 11, 4, 17, 40, 0, 0, time.FixedZone("", 7*3600)) }}
	st := c.Status(context.Background())
	if st.At != "2026-11-04T17:40:00+07:00" {
		t.Errorf("at = %q", st.At)
	}
	if st.Battery == nil || st.Memory == nil || st.Disk == nil || st.UptimeSeconds == nil || *st.UptimeSeconds != 42 {
		t.Errorf("status = %+v", st)
	}
	if st.HermesRunning == nil || *st.HermesRunning || st.TailscaleUp == nil || !*st.TailscaleUp {
		t.Errorf("hermes/tailscale = %v %v", st.HermesRunning, st.TailscaleUp)
	}
	if st.CPUPercent != nil {
		t.Errorf("cpu before two samples = %v", *st.CPUPercent)
	}
	// Move the counters; a value exists once two samples exist.
	c.take()
	put(t, c.Root, "proc/stat", "cpu  200 0 200 750 150 0 0 0 0 0\n")
	c.take()
	st = c.Status(context.Background())
	if st.CPUPercent == nil || *st.CPUPercent != 66.7 {
		t.Fatalf("cpu = %v", st.CPUPercent)
	}
	// Unchanged counters (zero total delta) must not erase the last value.
	c.take()
	c.take()
	st = c.Status(context.Background())
	if st.CPUPercent == nil || *st.CPUPercent != 66.7 {
		t.Errorf("cpu after zero-delta pairs = %v", st.CPUPercent)
	}
}

func TestStatusNullsWhenUnreadable(t *testing.T) {
	c := &Collector{Root: t.TempDir(), Home: filepath.Join(t.TempDir(), "gone"),
		Hermes: func(context.Context) (bool, error) { return false, errors.New("timeout") }}
	st := c.Status(context.Background())
	if st.At == "" || st.Battery != nil || st.CPUPercent != nil || st.Memory != nil || st.Disk != nil ||
		st.HermesRunning != nil || st.TailscaleUp != nil || st.UptimeSeconds != nil {
		t.Errorf("want only `at`: %+v", st)
	}
}

func TestStatusOtherOSIsNulls(t *testing.T) {
	c := &Collector{Root: fakeRoot(t), Home: t.TempDir(), NonLinux: true}
	st := c.Status(context.Background())
	if st.Battery != nil || st.Memory != nil || st.Disk != nil || st.UptimeSeconds != nil || c.SamplerRunning() {
		t.Errorf("non-linux = %+v", st)
	}
}

func waitFor(t *testing.T, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatalf("timed out waiting for %s", what)
		}
		time.Sleep(5 * time.Millisecond)
	}
}

func TestSamplerLifecycle(t *testing.T) {
	c := &Collector{Root: fakeRoot(t), SampleInterval: 5 * time.Millisecond, Idle: 60 * time.Millisecond}
	if c.SamplerRunning() {
		t.Fatal("sampler runs before any request")
	}
	c.Status(context.Background())
	if !c.SamplerRunning() {
		t.Fatal("first request must start the sampler")
	}
	// Requests keep it alive past the idle time.
	for range 10 {
		time.Sleep(15 * time.Millisecond)
		c.Status(context.Background())
	}
	if !c.SamplerRunning() {
		t.Fatal("sampler stopped while requests arrive")
	}
	waitFor(t, "sampler to stop after the idle time", func() bool { return !c.SamplerRunning() })
	// A later request starts it again, with no stale CPU value.
	if st := c.Status(context.Background()); st.CPUPercent != nil {
		t.Errorf("stale cpu after restart: %v", *st.CPUPercent)
	}
	if !c.SamplerRunning() {
		t.Fatal("request after idle must restart the sampler")
	}
	waitFor(t, "second stop", func() bool { return !c.SamplerRunning() })
}
