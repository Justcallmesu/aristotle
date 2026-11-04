// Package pcstatus reads the PC's battery, CPU, memory, disk and uptime for GET /v1/pc-status
// (docs/protocol.md §16). Linux only; every reader returns nil when its source is missing or
// unparseable. Each reader takes a root directory that holds sys/ and proc/, so tests use a fake tree.
package pcstatus

import (
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
)

// Battery is the PC battery (§16 "battery").
type Battery struct {
	Percent  int  `json:"percent"`
	Charging bool `json:"charging"`
}

// Memory is RAM in use (MemTotal - MemAvailable).
type Memory struct {
	UsedBytes  uint64 `json:"used_bytes"`
	TotalBytes uint64 `json:"total_bytes"`
}

// Disk is the filesystem of the user's home.
type Disk struct {
	Path       string `json:"path"`
	FreeBytes  uint64 `json:"free_bytes"`
	TotalBytes uint64 `json:"total_bytes"`
}

func readTrim(path string) (string, bool) {
	b, err := os.ReadFile(path)
	if err != nil {
		return "", false
	}
	return strings.TrimSpace(string(b)), true
}

// ReadBattery reads the first power supply of type Battery (ADP*, AC and peripherals are ignored).
func ReadBattery(root string) *Battery {
	dirs, _ := filepath.Glob(filepath.Join(root, "sys", "class", "power_supply", "*"))
	sort.Strings(dirs)
	for _, d := range dirs {
		if t, ok := readTrim(filepath.Join(d, "type")); !ok || !strings.EqualFold(t, "Battery") {
			continue
		}
		if scope, _ := readTrim(filepath.Join(d, "scope")); strings.EqualFold(scope, "Device") {
			continue // a wireless mouse, not the PC
		}
		capText, ok := readTrim(filepath.Join(d, "capacity"))
		if !ok {
			continue
		}
		pct, err := strconv.Atoi(capText)
		if err != nil || pct < 0 || pct > 100 {
			continue
		}
		status, _ := readTrim(filepath.Join(d, "status"))
		charging := strings.EqualFold(status, "Charging") || strings.EqualFold(status, "Full")
		return &Battery{Percent: pct, Charging: charging}
	}
	return nil
}

// CPUTimes is one /proc/stat reading of the aggregate cpu line.
type CPUTimes struct{ Total, Idle uint64 }

// ReadCPUTimes parses the first ("cpu ") line of proc/stat.
func ReadCPUTimes(root string) *CPUTimes {
	b, err := os.ReadFile(filepath.Join(root, "proc", "stat"))
	if err != nil {
		return nil
	}
	line, _, _ := strings.Cut(string(b), "\n")
	f := strings.Fields(line)
	if len(f) < 5 || f[0] != "cpu" {
		return nil
	}
	var total, idle uint64
	for i, s := range f[1:] {
		if i >= 8 { // user..steal; guest is already inside user
			break
		}
		v, err := strconv.ParseUint(s, 10, 64)
		if err != nil {
			return nil
		}
		total += v
		if i == 3 || i == 4 { // idle, iowait
			idle += v
		}
	}
	return &CPUTimes{Total: total, Idle: idle}
}

// CPUPercent is the busy share between two readings, one decimal; nil when no time passed.
func CPUPercent(prev, cur *CPUTimes) *float64 {
	if prev == nil || cur == nil || cur.Total <= prev.Total || cur.Idle < prev.Idle {
		return nil
	}
	dTotal, dIdle := cur.Total-prev.Total, cur.Idle-prev.Idle
	if dIdle > dTotal {
		return nil
	}
	p := float64(dTotal-dIdle) * 100 / float64(dTotal)
	p = float64(int(p*10+0.5)) / 10
	return &p
}

// ReadMemory reads proc/meminfo (kB values).
func ReadMemory(root string) *Memory {
	b, err := os.ReadFile(filepath.Join(root, "proc", "meminfo"))
	if err != nil {
		return nil
	}
	var total, avail uint64
	var haveT, haveA bool
	for _, line := range strings.Split(string(b), "\n") {
		key, rest, ok := strings.Cut(line, ":")
		if !ok || (key != "MemTotal" && key != "MemAvailable") {
			continue
		}
		f := strings.Fields(rest)
		if len(f) == 0 {
			return nil
		}
		v, err := strconv.ParseUint(f[0], 10, 64)
		if err != nil {
			return nil
		}
		if len(f) > 1 && strings.EqualFold(f[1], "kB") {
			v *= 1024
		}
		if key == "MemTotal" {
			total, haveT = v, true
		} else {
			avail, haveA = v, true
		}
	}
	if !haveT || !haveA || total == 0 || avail > total {
		return nil
	}
	return &Memory{UsedBytes: total - avail, TotalBytes: total}
}

// ReadUptime reads the first field of proc/uptime, whole seconds.
func ReadUptime(root string) *int64 {
	s, ok := readTrim(filepath.Join(root, "proc", "uptime"))
	if !ok {
		return nil
	}
	f := strings.Fields(s)
	if len(f) == 0 {
		return nil
	}
	v, err := strconv.ParseFloat(f[0], 64)
	if err != nil || v < 0 {
		return nil
	}
	n := int64(v)
	return &n
}

// ReadDisk reports the filesystem holding path (the user's home).
func ReadDisk(path string) *Disk {
	if path == "" {
		return nil
	}
	free, total, ok := statfs(path)
	if !ok || total == 0 {
		return nil
	}
	return &Disk{Path: path, FreeBytes: free, TotalBytes: total}
}
