# TM1 report — batch-46 review minors

Gate: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TM1` → PASS android (exit 0).

- AppLock: an auth check older than 2 min (`AppLock.CHECK_CAP_MILLIS`) is treated as failed on the next return: time away counts. A late `authEnded` of an abandoned check no longer ends a newer one. Old "never ends" test was renamed (it did call `authEnded`); new tests really never end the check, plus within-cap and late-end cases.
- `ServerVersions` is per PC id (`record(pcId, info)`, `of`, `all`, `clear(pcId)`, `clearAll`). `/v1/me` writes under the PC asked (`ServerClient.terminalAllowed(token, pcId)`). About and banner read the active PC. Unpair clears that PC; re-pair clears the old id only, never the new one. Tests: unpair/re-pair scope, new PC's early record survives replace, late old-PC reply does not show for the new PC.
- Banner: `surfaceContainerHighest`, `ic_error` icon in `error`, `onSurface` text. What's-new card: `surfaceContainerLow`. Both added to design.md §5 Banners (the brief says §4; banners live in §5).
- NIT: opening What's new from About now dismisses the card (`AristotleApp.kt`).
- Not done: other NITs (generator leaks, quote on rotate, board row, import order, CHANGELOG wording) — not in my file list or not one-liners; CHANGELOG lines :36/:44 still need rewording.
- Outside the listed files but needed to compile: `AppContainer.kt` (`clearAll`), `SessionsScreen.kt` (`pcId` param), `AristotleApp.kt` (pass pcId), `TerminalSocketTest.kt`, `ServerClientTest.kt` (new signature).
