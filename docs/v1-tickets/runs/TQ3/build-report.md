# TQ3 build report — review-Q80 minors
Gate: `gates.sh docs/v1-tickets/runs/TQ3` exits 0 (server + android PASS). No commits, no adb.
- MINOR-1: `CrashText.describe` keeps the message only for classes in `io.github.justcallmesu.aristotle.*`; others get the class name. Tests added/updated in `CrashReportsTest`.
- MINOR-2: `Diagnostics.loadOffer` split into `sweepShares()` (at start) and `claimCrash()` (once per process via AtomicBoolean, called from `CrashReportDialog`'s LaunchedEffect). Test: headless start leaves the report; repeated claim keeps it.
- MINOR-3: `messageActions` keys `pointerInput` on `role` only; host and target are read through `rememberUpdatedState`. Compose gesture, not unit-testable here (no UI test setup); needs a device check while a reply streams.
- MINOR-5: `carryCpu()` + `PcStatus.cpuStale`; the poller carries the last CPU (same host only) into a reading with `cpu_percent: null` but other data; the card dims that meter. Tests in `PcStatusPollerTest`.
- disk.path: server always sends `"~"` (`DiskPath`); protocol §16 updated (also notes cpu null → phone keeps last value); `TestDisk` asserts it.
- NITs: CPU uses new string `pc_cpu_meter_value` (en+in; `pc_cpu_value` already existed, so a new name); address tile in Atkinson Mono 500 (built from `AtkinsonMonoFamily`, theme untouched); Hermes red line hidden when stale; `forgetPc` (Revoked and the other forget paths) now clears connection log + diagnostics like Unpair.
- Not done: the `AppLogSourceGuardTest` indirect-line NIT, CHANGELOG MINOR-4 (not in brief).
