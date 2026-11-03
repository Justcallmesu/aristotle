# T10 build brief — background connection (foreground service) + notifications + Xiaomi guide

route: deep

Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`, `docs/spec.md` Q5, Q18 (notification spirit), Q27, Q44,
`docs/design.md` (notifications, Xiaomi guide), `docs/design/mockups.html` screen 6 (Xiaomi battery guide), `docs/protocol.md` §2
(events: which ones mean "turn finished", "approval requested", "clarify requested"; message/turn lifecycle), §6, §12 (client
obligations), then `android/.../data/{LinkManager,AppContainer,NetworkWatch}.kt` and the manifest.

## Goal
After the user leaves the app, the Hermes link stays alive and the phone notifies him when a turn finishes or Hermes asks something,
until he stops it. Works on the Poco (HyperOS) once the guide's settings are applied.

## Requirements
1. Foreground service, type `specialUse` (sideloaded, spec Q5) with the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` manifest property
   explaining "keeps the user's own Hermes agent session link open"; permissions `FOREGROUND_SERVICE`,
   `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS` (runtime request on 13+, explained first). Started when the app goes to
   the background while paired and online-capable; it OWNS nothing new — it keeps the process-wide LinkManager alive. A persistent
   low-importance notification "Connected to <PC> via Wi-Fi/Tailscale" / "Reconnecting…" / "PC offline since HH:MM" with a Stop
   action (stops the service and the link until the app is opened again) and tapping opens the app.
2. Event notifications (separate channels, EN/ID names): **Turn finished** in a session (one per turn; collapse per session; title
   = session title, text = first line of the final assistant message, max 120 chars, no tool output), **Hermes needs you** for an
   approval or clarify request (high importance; tapping opens the app — answering from the notification is T12). Do not notify for
   sessions the user is looking at in the app right now. Find the exact event names/fields in `docs/protocol.md` and the captured frames
   `docs/spikes/frames/`; to receive events for a session the link must have resumed it — design the minimal rule: subscribe to
   sessions that are `running` in `session.list` (and new ones that start, via the list-changed event) — and document it.
   Never put secrets/system prompt text in notifications; redaction already removes system_prompt.
3. Doze/background rules: no wake locks; rely on the foreground service + the server's 25 s pings. On `onTaskRemoved` keep running.
   Boot start is NOT in scope.
4. Xiaomi guide screen per mockup 6: shown once after the first successful pairing on Xiaomi/Redmi/POCO devices (manufacturer check),
   and from settings later: deep links to the app's battery settings ("No restrictions") and Autostart (use the documented intents,
   with a fallback to the app info page if an intent does not resolve). It cannot verify the settings; it explains why.
5. Settings entry (minimal): "Keep connected in background" switch (default on) and "Show the battery guide again".
6. The server's keepalive/client obligations stay met while in the background (hand frames off the OkHttp thread).

## Tests
Pure: notification text builder (truncation, collapse key), which-sessions-to-subscribe rule, manufacturer check, service state ->
notification text mapping. Robolectric is not in the project; do not add it — keep logic pure and the Android glue thin.

## Hard rules
Files: `android/**`, `docs/protocol.md` (only a short "background subscription rule" note in §2 if needed),
`docs/v1-tickets/runs/T10/build-report.md`. No commits. Phone NOT attached: no device testing; list what must be checked on the
device (DC rows) in the report. No SDK installs.

## Verify
`GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/T10` exits 0.

## Report
`docs/v1-tickets/runs/T10/build-report.md`, ≤40 lines.
