# TL2 — debug log + crash screen (Q80b)

route: deep (privacy). Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/spec.md` Q50, Q51, Q73, **Q80**, then grep how the
app logs today (`android.util.Log`, any wrapper), `AristotleApp`/`Application` class, `MainActivity.kt`, `ui/settings/*` (About sub-page),
`data/ConnectionLog.kt` (TH1: a bounded private log with a write queue — reuse its pattern), `res/xml` backup rules.

## Do
1. **AppLog**: one small logging front door the app uses (route existing `Log.*` calls through it where they are app logs). Ring buffer on
   disk, app-private, excluded from backup AND device transfer, bounded (e.g. 512 KB, 2 files rotated), written off the main thread, also
   forwards to logcat in debug builds. **Redaction is enforced at the door** (pure, tested): tokens / bearer values, pairing codes,
   `token_sealed`, full fingerprints (keep 8 hex), anything after `password`/`secret`/`value` keys, URLs' query strings, and NO message /
   prompt / reasoning / tool-output text ever (callers pass event names + ids, never content). Add a test that greps the main source set
   for `Log.` calls with string templates of content-bearing variables if feasible, or document the rule in a KDoc and review it.
2. **Crash capture**: an `UncaughtExceptionHandler` that writes the throwable (class, message redacted, stack) + app version + Android
   version + device model to a crash file, then chains to the previous handler (the app still dies normally).
3. **Crash screen**: on the next launch, a dialog over the app (after the app lock, never above it): "Aristotle closed unexpectedly" ·
   [Send report] [Dismiss]. Send report shares ONE text file (crash + last N log lines) through the Android share sheet via a
   `FileProvider` (add it; scoped to a cache subdir); Dismiss deletes the crash file. Nothing uploads automatically.
4. **Settings › About › "Send debug log"**: shares the current log file the same way; a short line says what it contains and what it
   never contains.
5. Unpair clears the log too.
Tests: redaction (each rule), ring bounds/rotation, crash-file lifecycle (write, offer once, dismiss deletes), FileProvider authority/paths.

## Files
`android/**` except `ui/theme/**`, `ui/components/**`, `ui/chat/**`, `ui/sessions/**` (other workers) — if you must touch a file there to
route a `Log` call, list it and keep it to that one line.
Hard rules: no commits; never git checkout/restore/stash/reset. No adb. Another worker edits other android files now (TR7:
`ui/sessions/PcStatusCard.kt`, `SessionsScreen.kt`, `data/PcStatus.kt`; and the other ticket of this pair): edit only your files.
strings append-only (EN + ID, translate properly). Verify: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TL2`
exits 0 (if it fails only in another worker's files, say so). Report `docs/v1-tickets/runs/TL2/build-report.md` ≤12 lines.
