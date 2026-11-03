# TD40 build report
- Changed: `android/app/src/main/AndroidManifest.xml` only (added `xmlns:tools` + 3 `tools:node="remove"` entries). build.gradle.kts untouched.
- Removed (all datatransport, the only telemetry stack merged): `TransportBackendDiscovery` (CCT backend meta-data), `JobInfoSchedulerService`, `AlarmManagerSchedulerBroadcastReceiver`.
- Kept: `GmsBarcodeScanningDelegateActivity`, `MlKitComponentDiscoveryService` with `VisionCommonRegistrar` and `CommonComponentRegistrar`, `MlKitInitProvider`, `GoogleApiActivity`, the `DEPENDENCIES=barcode_ui` meta-data. The scanner needs these. The two registrars are ML Kit core, and no datatransport registrar was merged, so there was nothing else to strip. The T08 line numbers 145-158 are stale.
- Opt-out: none. The official code-scanner page (developers.google.com/ml-kit/vision/barcode-scanning/code-scanner) documents no manifest flag or API for usage logging, so only the manifest strip is used.
- Proof: `merged-before.xml`, `merged-after.xml` and `merged-manifest.diff` are in this dir. The diff is exactly the 3 components deleted (lines 158-173) and nothing else. `datatransport` is gone from the after file.
- The scanner classes still resolve and the build passes: `GATE_ONLY=android gates.sh docs/v1-tickets/runs/TD40` exits 0 (`gate-summary.txt`).
- Fallback: `QrScanner.kt:35` maps `addOnFailureListener` to `ScanOutcome.Unavailable` (cancel → `Cancelled`). Not run or tested here.
- NOT-VERIFIED: the scanner still scans on the Poco (user device check; if it fails, revisit per Q58). I did not confirm that ML Kit runs quietly with the datatransport components missing, and I did not observe network traffic. Removing them should only stop usage uploads, but this is untested.
