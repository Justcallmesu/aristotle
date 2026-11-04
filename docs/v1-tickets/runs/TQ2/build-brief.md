# TQ2 — quote while a chat loads (Q59)

route: std. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/spec.md` Q44-Q46, **Q59**, `docs/design.md` (quote screen
exception Q46), `docs/v1-tickets/runs/TQ1/build-report.md`, then the quote screen code (`ui/quote/**` or wherever TQ1 put it — grep
`quotes.json`), and in `ui/chat/ChatScreen.kt` the "Loading the conversation…" state.

## Do
While a chat's history loads (the "Loading the conversation…" state), show a quote from the same set in the same style as the launch
quote screen, but inline in the transcript area (top bar and composer stay). Do not repeat the quote shown at launch in the same process.
If the history arrives in under ~300 ms, don't flash a quote (keep the current quiet line). Fade out when the transcript appears. Reuse
the TQ1 quote picker; make it a small shared function if it is private. Tests for the pick/no-repeat and the delay rule (pure).

## Not yours
`ui/settings/**`, `data/PcRecords.kt`, `data/Compatibility.kt`, `server/**`. In `ChatScreen.kt` change only the loading state.

Hard rules: strings.xml append-only (both values/ and values-in/); another worker edits android at the same time (see 'Not yours').
No commits; never git checkout/restore/stash/reset. No adb. Verify: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TQ2`
exits 0 (if it fails only in the other worker's files, say so). Report `docs/v1-tickets/runs/TQ2/build-report.md` ≤15 lines.
