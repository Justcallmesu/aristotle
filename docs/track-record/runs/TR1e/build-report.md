# TR1e build report (server fix round for TR1d)

- **MAJOR fixed:** failure dates moved to `stocks-track-failures.json` (new `trackfails.go`: versioned, atomic, unusable → logged/no failures/never overwritten, newer → read-only). `stocks-verdicts.json` is the c74df38 v1 shape again (`TestVerdictsFileStaysC74df38Shape` decodes it with a copy of that strict decoder). A leftover `failures` field is moved once (`TestFailuresLeftInVerdictsFileAreMoved`) and dropped by the next verdicts write.
- **Entry removal:** entry cleared on a score and when all of a code's unscored records are given up (`TestSuccessRemovesFailureEntry`, `TestGiveUpRemovesFailureEntry`).
- **Hard limit:** only when the code also failed on an earlier date since its last success. Added a `since` field next to `first`/`last` (a streak restart must not forget it). Reviewer's 7-weeks probe: `TestHardLimitNeedsAnEarlierFailure`. `TestTrackJobRecent422Waits` now expects no give-up on the first run, the Sept record on the second day.
- **Mutants (scratch copy `cache/scratch/tr1e`, all KILLED):** last never advances → `TestFailureStreakOfThreeDaysCrossingTheLimit`; hard 29 / 31 → `TestHardLimitBoundary` (29, 30, 31 days); no clear on success; hard limit ignoring history; no clear on give-up; newer file written.
- **NITs:** `noteFailure` is now called from `failCode` in `runTrackScore` (`giveUp` only decides); newer-version failures file test; §20.5 "an unparsable body"; §20.5 rewritten for the new file, removal and hard limit.
- **Files:** `server/internal/stocks/{trackfails.go,trackfails_test.go (new),track.go,track_test.go,verdicts.go}`, `docs/protocol.md`. P16b in spec.md already carried the amendment; not touched.
- **Gate:** `GATE_ONLY=server gates.sh` PASS (gofmt, vet, 772 tests); `go test -race -count=3 ./internal/stocks/...` ok.
- **Note:** a failed write of the failures file is only logged (the streak is then lost at a restart); it does not turn the run into `state_error`.
