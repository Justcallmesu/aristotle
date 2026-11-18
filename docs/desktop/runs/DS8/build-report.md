# DS8 build report — views-wave review fixes

Brief: `build-brief.md`. Input: `runs/views-wave/review-report.md`. Spec P20, P21; design.md 4.2, 4.3, 4.4, 4.7, 4.8.

## Gate (`timeout 1800 bash docs/desktop/gates.sh docs/desktop/runs/DS8/self-gate`)

```
PASS ui-ci (0)
PASS ui-lint (0)
PASS ui-typecheck (0)
PASS ui-test (0)
ui-tests passed=284
PASS ui-test-count (284)
PASS ui-build (0)
PASS desktop-fmt (0)
PASS desktop-clippy (0)
PASS desktop-test (0)
rust-tests passed=158 failed=0
PASS desktop-test-count (158)
PASS desktop-test-binaries (0)
PASS desktop-tray-build (0)
PASS desktop-tray-ldd (0)
PASS desktop-app-build (0)
PASS desktop-app-binary (0)
```

Floors raised to the new totals: `MIN_TESTS=158` (was 148), `MIN_UI_TESTS=284` (was 225) in `docs/desktop/gates.sh:18-19`.

## RED evidence

- **Rust:** tests were written first against a stub (field and `Notice` variants present, no diff logic). `cargo test -p aristotle-core --test diff` then failed 4 tests (`terminal_switched_on_notifies_once`, `new_grant_notifies_by_device_name`, `grant_of_an_unknown_device_has_no_name`, `a_removed_grant_is_silent_and_enable_plus_grant_gives_two_notices`). `has_open_action` was RED as a compile error (`no method named has_open_action`).
- **UI:** the source change went in before the new tests for most items, so RED comes from **mutants** (scratch run `scratch/ds8_mut.py`, each applied to the tree and reverted; `diff -r` against a backup confirmed the tree was restored). Each mutant failed the named test(s):

| Mutant | Killed by |
|---|---|
| M1 `dis_other` → `off(null)` | 12 failures: `serviceButtons.test` (every non-active word), ServiceView "service activating…dis_other" per word |
| M2 Start for any non-`active` word (old rule) | 28 failures: `canStart` / `serviceButtons` per word |
| M3 Reconnect sends `logs_stop` first | logs "Reconnect starts again with fresh lines" (now asserts 0 stops) |
| M4 turn-off decides from the poll | Terminal "a terminal opened since the last poll still gets the D19-8 dialog" |
| M5 intent read before the listener | `intent.test` "registers the listener before it reads…" |
| M6 `qr_error` hides the code | Pair "an encode failure with a payload keeps the code…" |
| M7 `{ok:false}` has no text | useCommand "names the service word…", Status "a resolved { ok: false }" |
| M8 stray `--pair` intent kept | Pair "a --pair hand-off that arrives while the server is down is dropped…" (MINOR-6) |
| M9 cap note always `en-US` | Logs "writes the cap with the locale's thousands separator" |
| M10 `.banner-slot` wrapper back | 6 failures: placement tests in Service, Terminal, Pair, Devices, Status, CommandFailed |
| M11 Status granted not from the snapshot | Status "terminal on: open and granted counts from the snapshot…" |
| M12 Devices ignores `terminal_granted` | 2 Devices tests |
| M13 NeedsServer old rule | 5 NeedsServer per-word tests |

Also seen RED when the grants source moved: 10 existing tests (Status, Devices, Terminal) failed until their fixtures carried `terminal_granted`.

## Per item

**1. MAJOR-1 / P20 (D7 notification by the tray)**
- `core/src/snapshot.rs:70-72,211-214`: `Snapshot.terminal_granted: Vec<String>`, from the `terminal status` call the poller already makes.
- `core/src/diff.rs`: new `Notice::TerminalTurnedOn` and `Notice::TerminalGranted { device_name }`. `terminal_access_widened` emits on `terminal_enabled` false→true and per new granted id. Name comes from the devices list, `None` for an unknown id. The first snapshot is the baseline. A failed terminal read on either side (`terminal_enabled == None`) is silent. `Notice::has_open_action()` is false for both D7 notices (design 4.8: no **Open** button); `tray/src/notifier.rs` uses it.
- `core/src/poller.rs:50-58`: a failed `terminal status` keeps the previous enabled and granted pair as the baseline, so a change during the failure still notifies once.
- `core/src/strings.rs`: `notice_text` maps both notices to `TermOnTitle/TermOnBody`. EN/ID text already equals design `n_d7_t`/`n_d7_b`; a test pins both languages. The body is static, so the device name does not appear in it (P11); it is carried for the fallback and a future use.
- `app/src/dto.rs`, `ui/src/ipc/types.ts`: `terminal_granted` reaches the UI snapshot.
- Tests: `core/tests/diff.rs` (+8), `strings.rs` (+1), `state.rs` (+2 assertions), `poller_stamp.rs` (+1; the older failed-terminal test now expects 3 notices because the fixture also turns the switch on and grants), `app/src/backend.rs` (+1 assertion).

**2. MINOR-2 / P21** — `ui/src/state/serviceButtons.ts` (moved from `views/service/`): `canStart(status, world)` = installed, no mismatch, service `inactive|failed`. Used by `StatusView.tsx`, `ServiceView` (through `serviceButtons`) and `components/NeedsServer.tsx`. Tests: `state/serviceButtons.test.ts` (8 words each), StatusView (8 words), NeedsServer (8 words), ServiceView.

**3. MINOR-3** — `serviceButtons` returns `{key:"dis_other", params:{word}}`; `ServiceView.tsx` shows it with the catalog `svc_*` word, raw if missing. `dis_other` added to `en.json`/`id.json` exactly as design.md §string table. Tests: ServiceView per word (EN) and ID.

**4. MINOR-4** — `components/CommandFailed.tsx` is the bare `Banner` (no `.banner-slot`). Removed `FailedBanner`, `.kv-failed`, `.tm-view .banner-slot`; Terminal's problem/warning banners lost their wrappers too. Placement everywhere: right after `ViewHeader`, above the content (Pair and Devices moved from above the h1). Tests assert `header.nextElementSibling === alert` and no `.banner-slot` parent on Status, Service, Pair, Devices, Terminal.

**5. MINOR-5**
- (a) `src/format/time.ts` (+ `dash.ts`): `parseTime`, `clock`, `dayMonth`, `dayMonthYear`, `relativeTime`, `relativeText`, `clockText`, `dmyText`, `minutesSince`. Deleted `devices/time.ts`, `terminal/format.ts`, and `seenText` and its month tables from `facts.ts`. Tests moved to `format/time.test.ts` (+3).
- (b, c) `styles/components.css`: `.kv-*` grouped/state rows and one `.hint` class. `rows.css` deleted; `.reason-hint` ×2, `.svc-hint`, `.tm-hint`, `.status-working-hint` replaced. Terminal's `.tm-state/.tm-row/.tm-group/.tm-sub/.tm-end` replaced by `kv-*`. `status-working-hint` margin went from 8 to 10 px.
- (d) Status, Devices and Terminal read grants from `snapshot.terminal_granted`; `useGrantedCount` and `useGrantedIds` deleted. Terminal keeps its own `terminal status` read for `enabled`/`open`/`warning`/`problem`. Terminal grant names come from the devices list (`t_gone` fallback), and the list shows `—` when the poll could not read the terminal.
- (e) Moved to shared homes: `state/facts.ts`, `state/useServiceAction.ts`, `components/Rows.tsx`, `test/views.tsx` (was `status/testing.tsx`).

**6. MINOR-6** — Pair test "a --pair hand-off that arrives while the server is down is dropped…" (M8 kills it).

**7. NITs**
- NIT-1: `shownFor` uses `!pair.qr_payload` only; `qr_error` shows under Details beside the code (`PairPanels.tsx`).
- NIT-2: `qr.ts` casts removed.
- NIT-3: cap note uses `id-ID`/`en-US`.
- NIT-4: `useCommand` gives `{ok:false,service}` the text `service is <word>`, so the code block reads `error: service is failed`. **No new key**: `act_failed_b` says "message … below", which that line satisfies. If the reply has no service word it falls back to `error: not-ok`.
- NIT-5: Reconnect sends `logs_start` only; the stream effect no longer re-runs on reconnect.
- NIT-6: `IntentProvider` awaits the listener, then reads the intent (`state/intent.test.tsx`).
- NIT-7: turn-off re-reads `terminal status` before choosing (`useTerminal.freshOpen`, falls back to the poll if the read fails); the dialog count is the fresh one; the switch is locked while checking.
- NIT-11: `PairFlow` split; `views/pair/PairPanels.tsx` holds `CodePanel`, `ExpiredPanel`, `NoListenerPanel`, `Addresses`. `PairView.tsx` is 160 lines (was 271).
- NIT 8, 9, 10: left as briefed.

**8. fakeTransport** — `setReply(command, value)`; a function value is called with the args (may return a promise or throw), so held, delayed and failing replies need no patching. `gate()` is built on it. Replaced every hand-patched `transport.invoke` in the Snapshot, shared, Pair, Devices and Terminal tests.

## Screenshots (`docs/desktop/runs/DS8/shots/`, 960×640, light + dark, 16 PNGs)
`status-start-failed`, `status-activating-no-start` (new scene `status-activating`), `status-stopped-start`, `service-other-word` (now shows the `dis_other` line), `service-failed`, `pair-failed`, `dev-failed`, `terminal-failed`. The banner now sits flush with the content under the header in all of them. Vite dev server (127.0.0.1:5199) stopped.

## Left / notes
- Not a mismatch, but for the PM: design.md says the D7 notification body is static, so the device name from the diff is unused by `notice_text`. Not changed (design.md is not mine to edit).
- Live D7 check (enable and grant with a real phone) still needs the user, as the review listed.
- `app/gen/schemas/*` unchanged; no app, tray or real server was launched. The `aristotle-server serve` process on the machine (pid 652) is the user's own service.
