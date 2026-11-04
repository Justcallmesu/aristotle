# TR2 — v2 redesign build

route: deep.

## Goal
Home C per design-notes §5.1, §5.6 and §10 TR2: header row with the title and the profile avatar top right — **user decision: the profile NAME is BESIDE the avatar on the same row** (not under; labelLarge, onSurfaceVariant, max width ~120 dp, ellipsized; the avatar+name is ONE 48 dp touch target opening TODAY's profile sheet, unchanged content, Soft shapes); live sessions as wide cards; history in the time-column ledger, one line, NO message count (long-press sheet may keep it); bottom app bar with search, Scheduled, ⋮ (Terminal when allowed, Settings) and New chat FAB; offline/compat/what's-new notices as in the notes; the offline-dimmed avatar; empty and loading states. Keep search, pins, paging, pull-to-refresh, rename, profile fallback behaviour.

## Your files (only these)
`ui/sessions/**` (SessionsScreen, SessionRows, SessionSheets, OfflineBanner, CompatibilityBanner, WhatsNewCard, any new file there), their tests

## Common
Repo `/home/justcallmesu/project/personal/Aristotle`. The contract is the v2 board `docs/design/v2/mockups.html` (open it in a browser
tool if you have one; it is self-contained) and `docs/design/v2/design-notes.md` — read the sections named below fully; the Compose
mapping there is binding. Also `docs/spec.md` Q67, Q69, Q74-Q76, `docs/design.md` (§3 rewritten by TR1), `docs/v1-tickets/runs/TR1/
build-report.md` (new shapes `FieldShape`, `ComposerShape`, `SquareIconButtonShape`, `ButtonShape`; `ui/components/GroupedRows.kt`,
`ui/components/AristotleSlider.kt` — use them, never edit `ui/theme/**` or `ui/components/**`; need a new token? private val in your file,
list it). Real device screenshots of today's look: `/home/justcallmesu/.hermes/profiles/pm/cache/scratch/ui/*.png`.

Four other workers build the other screens at the same time (TR2 home, TR3 chat transcript + chat top bar, TR4 composer, TR5 model sheet,
TR6 settings + prompt sheet): edit ONLY your files. strings.xml: append new keys at the END only, both values/ and values-in/. Keep
behaviour; existing tests stay green; add tests for new pure logic. Gradle is shared: if a gate run collides, wait and rerun. No commits;
never git checkout/restore/stash/reset. No adb.
Verify: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TR2` exits 0 (if it fails only in another worker's files,
say so). Report `docs/v1-tickets/runs/TR2/build-report.md` ≤15 lines: what matches the board, what deviates and why, files, tests.
