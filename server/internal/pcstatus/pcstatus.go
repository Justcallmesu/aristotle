package pcstatus

import (
	"context"
	"log/slog"
	"os"
	"runtime"
	"sync"
	"time"
)

const (
	// DefaultSampleInterval is the CPU sample window (§16: ≤ 5 s).
	DefaultSampleInterval = 5 * time.Second
	// DefaultIdle stops the sampler this long after the last request.
	DefaultIdle    = 60 * time.Second
	firstSampleGap = time.Second
)

// Status is the §16 response. Every field but At may be null.
type Status struct {
	At            string   `json:"at"`
	Battery       *Battery `json:"battery"`
	CPUPercent    *float64 `json:"cpu_percent"`
	Memory        *Memory  `json:"memory"`
	Disk          *Disk    `json:"disk"`
	HermesRunning *bool    `json:"hermes_running"`
	TailscaleUp   *bool    `json:"tailscale_up"`
	UptimeSeconds *int64   `json:"uptime_seconds"`
}

// Collector gathers a Status. Zero values of the optional fields pick the real system.
type Collector struct {
	Root           string                              // directory with sys/ and proc/; default "/"
	Home           string                              // disk path; default the user's home directory
	Hermes         func(context.Context) (bool, error) // nil: hermes_running is null
	Tailscale      func() bool                         // nil: tailscale_up is null
	Log            *slog.Logger
	SampleInterval time.Duration
	Idle           time.Duration
	Now            func() time.Time
	NonLinux       bool // tests: act as another OS

	mu      sync.Mutex
	running bool
	lastReq time.Time
	prev    *CPUTimes
	cpu     *float64
	logged  map[string]bool
	wake    chan struct{}
	manual  bool // tests: no background sampler; the test calls take itself
}

func (c *Collector) root() string {
	if c.Root == "" {
		return "/"
	}
	return c.Root
}

func (c *Collector) now() time.Time {
	if c.Now != nil {
		return c.Now()
	}
	return time.Now()
}

func (c *Collector) interval() time.Duration {
	if c.SampleInterval > 0 {
		return c.SampleInterval
	}
	return DefaultSampleInterval
}

func (c *Collector) idle() time.Duration {
	if c.Idle > 0 {
		return c.Idle
	}
	return DefaultIdle
}

func (c *Collector) linux() bool { return runtime.GOOS == "linux" && !c.NonLinux }

// debugOnce logs the first failure of a source at debug level.
func (c *Collector) debugOnce(name string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.logged == nil {
		c.logged = map[string]bool{}
	}
	if c.logged[name] || c.Log == nil {
		return
	}
	c.logged[name] = true
	c.Log.Debug("pc status: source unreadable", "source", name)
}

// SamplerRunning reports whether the background CPU sampler is alive.
func (c *Collector) SamplerRunning() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.running
}

// touch records a request and starts the sampler when it is not running.
func (c *Collector) touch() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.lastReq = c.now()
	if c.running || !c.linux() {
		return
	}
	c.running = true
	if c.manual {
		return
	}
	go c.sample()
}

func (c *Collector) sample() {
	gap := firstSampleGap
	if iv := c.interval(); iv < gap {
		gap = iv
	}
	c.take()
	timer := time.NewTimer(gap)
	defer timer.Stop()
	for range timer.C {
		c.take()
		c.mu.Lock()
		if c.now().Sub(c.lastReq) > c.idle() {
			c.running, c.prev, c.cpu = false, nil, nil
			c.mu.Unlock()
			return
		}
		c.mu.Unlock()
		timer.Reset(c.interval())
	}
}

func (c *Collector) take() {
	cur := ReadCPUTimes(c.root())
	c.mu.Lock()
	defer c.mu.Unlock()
	if cur == nil {
		c.prev, c.cpu = nil, nil
		return
	}
	// CPUPercent is nil for a zero-total-delta pair; keep the last real value then.
	if p := CPUPercent(c.prev, cur); p != nil {
		c.cpu = p
	}
	c.prev = cur
}

// Status collects every field now. The CPU value is the sampler's latest window (null until two
// samples exist).
func (c *Collector) Status(ctx context.Context) Status {
	c.touch()
	st := Status{At: c.now().Format(time.RFC3339)}
	if tf := c.Tailscale; tf != nil {
		v := tf()
		st.TailscaleUp = &v
	}
	if hf := c.Hermes; hf != nil {
		if v, err := hf(ctx); err == nil {
			st.HermesRunning = &v
		} else {
			c.debugOnce("hermes")
		}
	}
	if !c.linux() {
		return st
	}
	st.Battery = ReadBattery(c.root())
	if st.Battery == nil {
		c.debugOnce("battery")
	}
	c.mu.Lock()
	st.CPUPercent = c.cpu
	c.mu.Unlock()
	if st.CPUPercent == nil {
		c.debugOnce("cpu")
	}
	if st.Memory = ReadMemory(c.root()); st.Memory == nil {
		c.debugOnce("memory")
	}
	home := c.Home
	if home == "" {
		home, _ = os.UserHomeDir()
	}
	if st.Disk = ReadDisk(home); st.Disk == nil {
		c.debugOnce("disk")
	}
	if st.UptimeSeconds = ReadUptime(c.root()); st.UptimeSeconds == nil {
		c.debugOnce("uptime")
	}
	return st
}
