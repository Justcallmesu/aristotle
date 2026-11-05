# TSC1c build report
- Rewrote `TestSchedulerPutDuringRunIsRaceFree` (`server/internal/stocks/scheduler_test.go`): interval job with an instant body, `tick` looped ~200 ms (+10 min per tick) while another goroutine PUTs (`update` On/EveryMin + `views`) in a loop; no channel orders PUT vs run goroutine. Production code unchanged.
- RED on d2e1255 (`git archive` into scratch, old test file + new test), `go test -race -count=3 -run TestSchedulerPutDuringRunIsRaceFree`:
  `WARNING: DATA RACE` … `scheduler.go:612` (write `*j = n` in update) vs `scheduler.go:382` / `:346` (unlocked read in tick/run goroutine) → `--- FAIL` / `FAIL`.
- GREEN on working tree, `go test -race -count=20 -run TestSchedulerPutDuringRunIsRaceFree ./internal/stocks`: `ok … 5.194s`. No remaining race found in TSC1b's fix. gofmt clean.
