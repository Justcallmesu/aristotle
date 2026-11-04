# TR7 — PC status card option A "Meters" (Q79)

route: std. Repo `/home/justcallmesu/project/personal/Aristotle`. Contract: `docs/design/v2/mockups.html` section 6 option A (open it in a
browser tool if you have one) and `docs/design/v2/design-notes.md` §5.7 (Compose code, colour roles, TalkBack sentence, icons, strings —
binding). Also `docs/spec.md` Q78, Q79, `docs/protocol.md` §16, `docs/v1-tickets/runs/TD3/report.md`. Current code:
`ui/sessions/PcStatusCard.kt`, `ui/sessions/SessionsScreen.kt` (passes the card's inputs; add the `network` parameter §5.7 asks for),
`data/PcStatus.kt` (formatting — reuse, extend if needed with tests). Device screenshot of today's card:
`/home/justcallmesu/.hermes/profiles/pm/cache/scratch/ui/91-pc.png`.

## Do
Rebuild `PcStatusCard` as option A in all 5 board states (collapsed, opened, charging + Hermes not running collapsed/opened, offline
"as of HH:MM"), light + dark; meters use M3 `LinearProgressIndicator` (determinate) per §5.7 colours; no verdigris; red only for Hermes not
running; whole toggle row ≥ 48 dp; one TalkBack sentence for the collapsed card; expanded state survives rotation; null fields hide
their meter/tile (never "null"). New strings EN + **ID** (translate them properly; append-only); new icons Material Symbols Outlined 400.
Pure functions for meter fractions (battery %, CPU %, RAM used/total, clamped 0..1, null-safe) with tests.

## Hard rules
Files: `ui/sessions/PcStatusCard.kt`, `ui/sessions/SessionsScreen.kt` (the call only), `data/PcStatus.kt`, `res/drawable/ic_*` (new only),
strings (append), tests, `docs/v1-tickets/runs/TR7/build-report.md`. No commits; never git checkout/restore/stash/reset. No adb.
Verify: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TR7` exits 0. Report ≤10 lines.
