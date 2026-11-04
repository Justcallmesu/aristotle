# TD3 — PC status card: 3 more beautiful options (Q78)

route: deep (design). Repo `/home/justcallmesu/project/personal/Aristotle`. Follow the `claude-design` skill ("Mockups for a native app").
The user: "make the pc status card more beautiful, its kinda bare and rough". Current device screenshot:
`/home/justcallmesu/.hermes/profiles/pm/cache/scratch/ui/91-pc.png` (card expanded: "58% CPU 27% RAM 7.0 / 11.5 GB" row with a battery
icon, then plain text lines: "192.168.100.88 · via Wi-Fi", "34 GB free of 184 GB", "Hermes Running", "Tailscale Up", "Up 28 m").
Code: `android/app/src/main/java/io/github/justcallmesu/aristotle/ui/sessions/PcStatusCard.kt`, data `data/PcStatus.kt`, contract
`docs/protocol.md` §16. Design system: `docs/design/v2/design-notes.md` (Soft shapes, palette A, verdigris = live only, red = errors only,
Atkinson Next/Mono, Literata titles), `docs/design.md`.

## Deliver
Add a section "PC status card" to `docs/design/v2/mockups.html` (keep everything else) with **3 options**, each collapsed AND expanded,
light AND dark, inside the Home C header (title + "pm" avatar above, "Live now" below), using these real values: battery 58% not
charging (and a charging variant), CPU 27%, RAM 7.0 / 11.5 GB, disk 34 GB free of 184 GB, Hermes Running (and a Not running variant),
Tailscale Up, uptime 28 m, address 192.168.100.88 · Wi-Fi; plus an offline "as of 17:40" variant. Ideas (your call, differ in
composition): (1) three mini meters (label · value · thin linear bar) in a row, expanded = 2-column tiles with icons; (2) a ring/arc
gauge trio; (3) a compact "machine" line with status dots and a sparkline-free bar strip. Only Compose Material 3-buildable (LinearProgress
Indicator, CircularProgressIndicator, Surface, Icon — no blur/gradients), ≥48 dp touch, TalkBack sentence. Recommend one; add the Compose
mapping to `design-notes.md` §5.7.

## Hard rules
Only `docs/design/v2/**` and `docs/v1-tickets/runs/TD3/report.md` (≤8 lines). No app code, no git state changes, no adb. Verify the
HTML opens without console errors.
