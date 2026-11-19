# DS8b build report

All items done. Gate: all PASS (summary below). Nothing left open; no design.md key was invented.

## Changes (paths under `desktop/`)

1. **MINOR-1 / P22 — own grant notice**
   - `core/src/strings.rs`: new `Key::GrantTitle`/`GrantBody` (+ `Key::ALL` 29→31); EN/ID exactly as design.md `n_grant_t`/`n_grant_b`; `notice_text` gives `TerminalGranted` its own text, `{name}` = device name, `ADevice` fallback. `TerminalTurnedOn` keeps `TermOn*`.
   - `core/src/diff.rs:121` `terminal_access_widened`: turn-on and grants are independent branches. Grants notify whatever the switch says. Both in one tick = two notices.
2. **MINOR-2 — unknown vs none**
   - `core/src/snapshot.rs:71,214`: `terminal_granted: Option<Vec<String>>`, `None` on a failed read. `app/src/dto.rs`, `ui/src/ipc/types.ts`: same (`string[] | null`).
   - `core/src/diff.rs`: `None` on either side = no information, silent. `core/src/poller.rs:53-57`: baseline keeps the last good `enabled` and `granted` independently.
   - Devices (`views/devices/DevicesView.tsx`, `useLastGoodGrants`): keeps the last good set when the poll gives `null`.
   - Terminal (`views/terminal/useTerminal.ts`): `TerminalData.granted` holds the view's own read. Grants = poll ids, else own read, else `null`. Names = server name, else device list. `devicesKnown` flag added.
   - `TerminalLists.tsx`/`TerminalView.tsx`: "(no longer paired)" only when the devices read is known (else the id). The enable dialog's "no phone holds a grant" only on known data (`granted !== null`).
   - `StatusView.tsx:164`: null-safe.
3. **NITs**
   - `{ok:false}` text: `useCommand.ts` message is now the raw §22 word; `CommandFailed.tsx` shows it via the existing `svc_*` catalog (`error: failed` / `error: gagal`; raw word when no key). No new key.
   - Dead `terminal_status` replies removed from `views/status/shots/main.tsx` and `views/pair/preview/main.tsx`.
   - `.field-hint`/`.pair-hint` → shared `.hint` plus `.hint-field` / `.hint-tight` margin modifiers (`pair.css`, `PairView.tsx`, `PairPanels.tsx`).
   - Core test for "failed read, then revoke + new grant": one notice (the new grant only).
   - `docs/desktop/gates.sh`: floors 158/284 → 166/289 (new totals).

## Tests added (Rust +8 = 166, UI +5 = 289)
- `core/tests/diff.rs`: grant while off; enable+grant in one tick (two different notices); `None` grants = no information.
- `core/tests/strings.rs`: grant text EN/ID with name and fallback (the D7 test now covers `TurnedOn` only).
- `core/tests/state.rs`: failed terminal read → `granted == None`.
- `core/tests/poller_stamp.rs`: failed read then revoke + grant (one notice, "tablet"); restart baseline (no notice); grant while off notifies once.
- UI: Devices keeps the last good grants; Terminal falls back to its own read (+ dialog line absent); Terminal keeps rows after a null poll; failed devices read shows the id; neither source known shows `—`; `CommandFailed` EN/ID not-ok word.

## RED evidence
- UI: the tests were written before the UI change. Vitest run: 5 failed (Devices ×2, Terminal ×3), then 3 more (`useCommand`, `CommandFailed`, Status not-ok) before the code. All green after.
- Rust: the core change was edited before its tests (I did not see them RED first), so I proved each test with a mutant (reverted by copying the saved file back):
  - M1 grant text = D7 text → `the_grant_notice_uses_the_design_text_with_the_device_name` FAILED.
  - M2 grants only while the switch is on → `a_grant_while_the_switch_is_off_is_its_own_notice` FAILED.
  - M3 failed read → `Some([])` → `a_failed_read_then_a_revoke_and_a_new_grant…` and `a_failed_terminal_read_keeps_the_granted_baseline…` FAILED.
  - All restored; the full suite is green afterwards.

## Gate (`timeout 1800 bash docs/desktop/gates.sh docs/desktop/runs/DS8b/self-gate`)
```
PASS ui-ci (0)
PASS ui-lint (0)
PASS ui-typecheck (0)
PASS ui-test (0)
ui-tests passed=289
PASS ui-test-count (289)
PASS ui-build (0)
PASS desktop-fmt (0)
PASS desktop-clippy (0)
PASS desktop-test (0)
rust-tests passed=166 failed=0
PASS desktop-test-count (166)
PASS desktop-test-binaries (0)
PASS desktop-tray-build (0)
PASS desktop-tray-ldd (0)
PASS desktop-app-build (0)
PASS desktop-app-binary (0)
```
(One earlier run failed clippy on a single-element `for` in my strings test; fixed and re-run.)

## Left / notes
- The empty `desktop/desktop/` dir (review NIT-5) is outside the whitelist; not touched.
- No app or tray launched; no processes of mine remain (the `aristotle-server serve` pid 620 is the user's own service).
