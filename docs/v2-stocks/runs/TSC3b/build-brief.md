# TSC3b — fix round for TSC3 (review CHANGES-REQUIRED)

route: deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/v2-stocks/runs/TSC3/review-report.md` (MAJOR-1 + MINOR 1-5 + NIT, with file:line),
`runs/TSC3/build-brief.md` + `build-report.md`, spec Q106, `docs/protocol.md` §20.3, committed server `server/internal/stocks/screener_setups.go`
(`git show HEAD:...` — the working tree has TBA1's uncommitted server edits; do not read or edit those).
## Do
1. MAJOR-1: `SetupLabels.forCode` merges a code's candidate setups AND flag setups for watchlist/holding rows, bearish first (Q106: a bearish signal
   must never be hidden on the user's own rows); fixture with one code in both lists; replace the test that locks in the old behaviour.
2. MINOR-1: refresh uses the scan windows, not `market.state == closed`, so the 16:00/16:10 scans are picked up.
3. MINOR-2: the stale notice's Scan now honours the cooldown (disabled with the countdown, like ⋮).
4. MINOR-3: cooldown from the server's manual-scan time (use what §20.3 exposes; if nothing exposes it, follow `too_soon.retry_after` only and
   do not guess) — not from the shown mode's last_run.
5. MINOR-4: selecting a mode that has not loaded shows a loading/empty state for THAT mode, never the other mode's list.
6. MINOR-5: the 3 s follow stops after N (e.g. 10) consecutive errors and falls back to the normal rule.
7. NIT if trivial.
## Hard rules
`android/**` and the report only. TBA1 edits `server/**` in parallel — not yours. strings append-only EN+ID. No adb; never connect to a server. No
commits; never git checkout/restore/stash/reset. Gate after your LAST edit: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v2-stocks/runs/TSC3b`
exits 0. Report ≤ 12 lines at `runs/TSC3b/build-report.md` (with tests seen RED before each fix).
