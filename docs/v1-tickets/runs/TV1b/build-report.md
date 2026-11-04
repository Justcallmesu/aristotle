# TV1b build report

Gate: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TV1b` → `PASS android`, server SKIP.

- `build.gradle.kts`: `versionName = "1.0.0"`, `versionCode = 10000` (scheme in a comment).
- New `data/Compatibility.kt`: `PHONE_PROTOCOL = 1`, `ServerInfo(version?, protocol, minPhoneProtocol)` with `ServerInfo.OLD` (no block: version unknown, protocol 1), pure `compatibilityOf()` → Compatible / ServerTooOld / AppTooOld, and `ServerVersions` (a StateFlow holding the last `/v1/me` block).
- `ServerClient.terminalAllowed` already calls `/v1/me` on every connect. It now also parses `server{version,protocol,min_phone_protocol}` and records it in `ServerVersions`. A missing block records `ServerInfo.OLD`.
- Banner: new `ui/sessions/CompatibilityBanner.kt`, one line in `SessionsScreen.kt` under the offline banner. It is a persistent error-container card, not a block.
- About: "Aristotle 1.0.0 (10000)" is the existing Version row, now on the new string `settings_app_version_value`. A new row shows "PC server <v> · protocol <N>", or "unknown" before first contact or for an old server (no `version`).
- Strings are EN + ID, appended. The old `settings_version_value` is kept but now unused.
- Tests: `CompatibilityTest` (6 cases) and 2 new `ServerClientTest` cases (block parsed; missing block → OLD).
- Deviation: the server info is a process-wide `ServerVersions` object, not threaded through `AppContainer` or `AristotleApp`. Those files are outside the brief's list. The cost is global state. It is not cleared when the user switches PC, so About can show the previous PC's version until the next `/v1/me`.
- Not touched: `ui/chat/**`, `data/chat/**`, `server/**`. No adb, no commits. Not checked on a device.
