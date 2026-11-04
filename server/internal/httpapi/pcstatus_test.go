package httpapi

import (
	"context"
	"encoding/json"
	"log/slog"
	"net/http"
	"testing"

	"github.com/justcallmesu/aristotle/server/internal/pcstatus"
)

func TestPCStatusNeedsTokenAndMatchesSection16(t *testing.T) {
	rm := newRemote(t)
	pct := 14.2
	up, yes := int64(86400), true
	status := pcstatus.Status{At: "2026-11-04T17:40:00+07:00", Battery: &pcstatus.Battery{Percent: 82, Charging: true},
		CPUPercent: &pct, Memory: &pcstatus.Memory{UsedBytes: 9771728896, TotalBytes: 16624144384},
		Disk:          &pcstatus.Disk{Path: "/home/u", FreeBytes: 1, TotalBytes: 2},
		HermesRunning: &yes, TailscaleUp: &yes, UptimeSeconds: &up}
	called := 0
	rm.h = NewRemoteRouter("t", slog.New(slog.NewTextHandler(rm.logs, nil)), Deps{Codes: rm.codes, Devices: rm.store,
		PCStatus: func(context.Context) pcstatus.Status { called++; return status }})
	p := rm.pair(t, "", "")
	if rec := rm.do(http.MethodGet, "/v1/pc-status", "", ""); rec.Code != http.StatusUnauthorized || called != 0 {
		t.Fatalf("no token = %d (collector called %d times)", rec.Code, called)
	}
	rec := rm.do(http.MethodGet, "/v1/pc-status", "", p.Token)
	if rec.Code != http.StatusOK || rec.Header().Get("Cache-Control") != "no-store" {
		t.Fatalf("status = %d %q", rec.Code, rec.Header().Get("Cache-Control"))
	}
	var got map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatal(err)
	}
	for _, k := range []string{"at", "battery", "cpu_percent", "memory", "disk", "hermes_running", "tailscale_up", "uptime_seconds"} {
		if _, ok := got[k]; !ok {
			t.Errorf("missing key %q in %s", k, rec.Body.String())
		}
	}
	if len(got) != 8 {
		t.Errorf("keys = %d, want 8: %s", len(got), rec.Body.String())
	}
	if b := got["battery"].(map[string]any); b["percent"] != 82.0 || b["charging"] != true {
		t.Errorf("battery = %v", b)
	}
	if m := got["memory"].(map[string]any); m["used_bytes"] != 9771728896.0 || m["total_bytes"] != 16624144384.0 {
		t.Errorf("memory = %v", m)
	}
	if rm.do(http.MethodPost, "/v1/pc-status", "", p.Token).Code != http.StatusNotFound {
		t.Error("POST /v1/pc-status accepted")
	}
}

// Without a provider the route is absent (404 behind a valid token), and null fields stay null.
func TestPCStatusNullsAndAbsent(t *testing.T) {
	rm := newRemote(t)
	p := rm.pair(t, "", "")
	if rec := rm.do(http.MethodGet, "/v1/pc-status", "", p.Token); rec.Code != http.StatusNotFound {
		t.Fatalf("no provider = %d", rec.Code)
	}
	rm.h = NewRemoteRouter("t", slog.New(slog.NewTextHandler(rm.logs, nil)), Deps{Codes: rm.codes, Devices: rm.store,
		PCStatus: func(context.Context) pcstatus.Status { return pcstatus.Status{At: "x"} }})
	var got map[string]any
	rec := rm.do(http.MethodGet, "/v1/pc-status", "", p.Token)
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil || rec.Code != 200 {
		t.Fatal(rec.Code, err)
	}
	for k, v := range got {
		if k != "at" && v != nil {
			t.Errorf("%s = %v, want null", k, v)
		}
	}
}
