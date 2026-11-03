# TD40 build brief — strip the code scanner's telemetry (Q58)

route: std. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/v1-tickets/runs/T08/review-report.md` (MINOR-1),
`docs/spec.md` Q58, `android/app/src/main/AndroidManifest.xml`, `android/app/build.gradle.kts`, and the merged manifest
(`android/app/build/intermediates/merged_manifest*/debug/**/AndroidManifest.xml` after an assemble).

## Do
1. List every component the Google code scanner (`play-services-code-scanner`) and its transitive deps merge in that exists for
   telemetry/usage reporting (datatransport `TransportBackendDiscovery`, `JobInfoSchedulerService`, `AlarmManagerSchedulerBroadcastReceiver`,
   CCT backend, Firebase/ML Kit `ComponentDiscoveryService` registrars for datatransport — check what is actually merged).
2. Remove those with `tools:node="remove"` in the app manifest. Do NOT remove what the scanner needs to work (module install / the
   scanner activity / `ModuleInstall` / `MlKitComponentDiscoveryService` entries the scanner requires) — when unsure, keep it and list it.
3. If ML Kit offers an official opt-out (manifest meta-data or API) for its usage logging, use it too (cite the doc).
4. Prove it: diff the merged manifest before/after (save both under `docs/v1-tickets/runs/TD40/`), confirm the scanner classes still
   resolve (assemble passes) and the app's existing scanner fallback still handles a scanner failure.
5. Add to `docs/v1-tickets/tickets.md` nothing; write the report only. Device check (the scanner still scans on the Poco) goes to the user.

## Hard rules
Files: `android/app/src/main/AndroidManifest.xml`, `android/app/build.gradle.kts` only if needed, `docs/v1-tickets/runs/TD40/**`.
server/ has another worker: do not touch. No commits; never git checkout/restore/stash/reset. Do not install on the phone.

## Verify
`GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TD40` exits 0.

## Report
`docs/v1-tickets/runs/TD40/build-report.md`, ≤15 lines: what was removed, what was kept and why, the opt-out used, NOT-VERIFIED.
