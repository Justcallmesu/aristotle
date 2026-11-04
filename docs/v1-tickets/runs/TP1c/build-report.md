# TP1c build report
- Cause (PM diagnosis partly refuted): product `take()` already kept the last CPU value on a zero-delta pair (`CPUPercent` returns nil, `c.cpu` is only overwritten by non-nil). Real flake was a test race: the sampler goroutine's first baseline read could land after the test rewrote `proc/stat`, so prev=new and every pair was (new,new) -> value never appeared.
- Product: no behaviour change; added comment on the keep-last-value rule and an unexported `manual` field (no background goroutine; tests drive `take()`).
- Test: `TestStatusAssemblesEveryField` now calls `take()` explicitly (nil before two samples, 66.7 after, 66.7 kept after two zero-delta takes); no timers/poll loop.
- Files: server/internal/pcstatus/pcstatus.go, pcstatus_test.go.
- Verify: `go test ./internal/pcstatus/ -count=50 -race` ok (16.7s); `GATE_ONLY=server gates.sh` exit 0.
