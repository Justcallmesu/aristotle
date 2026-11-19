# DS8b review: DS8 re-review fix round 2 (the uncommitted tree on top of 1b4ee32)

Reviewer: fresh context, deep. Scope: `git diff` / `git status` on 1b4ee32 (30 files, +332/-82).
I worked read-only on the repo. I edited no code and ran no git write commands. I did not launch the app or the tray, and I
did not run aristotle-server. Mutants and probes ran on a scratch copy (`~/.hermes/profiles/pm/cache/scratch/ds8b-review/`,
`mut.py`, plus a probe test appended to the copy's `poller_stamp.rs`). The copy has its own target dir and a copy of the
server's JSON fixtures.

## Verdict: **APPROVED-WITH-FINDINGS**

There is no BLOCKER or MAJOR.
- P22 is implemented as the spec states it, and the EN and ID text equals design.md.
- `terminal_granted: Option` is handled correctly by every reader. No path turns "unknown" into "no grants".
- The tests are meaningful: all 5 reviewer Rust mutants are killed, and 5 of 7 UI mutants are killed. Of the 2 UI mutants
  that survive, one is equivalent and the other is a small test gap (NIT-1).
- Nothing from DS8 has regressed.

The findings are one MINOR and several NITs/DF rows, listed below.

## Evidence (my own runs)

- `cargo test -p aristotle-core -p aristotle-tray -p aristotle-desktop` (CARGO_TARGET_DIR=desktop/target): all ok, 166 tests.
  `npx vitest run`: 16 files, **289/289**. Both totals match the PM gate (`pm-gate/gate-summary.txt`) and the floors in
  `gates.sh:18-19`.
- Reviewer mutants (scratch copy; each was restored, and the unmutated copy has a green baseline):

| Mutant | Result (killing tests) |
|---|---|
| R1 `poller.rs:55-57`: drop the `terminal_granted` fallback | KILLED (`a_failed_read_then_a_revoke_and_a_new_grant…`, `a_failed_terminal_read_keeps_the_granted_baseline…`, +1) |
| R2 `diff.rs:126`: grants only while `next.terminal_enabled == Some(true)` (the pre-P22 option b) | KILLED (`a_grant_while_the_switch_is_off_is_its_own_notice`, `a_grant_while_the_switch_is_off_notifies_once`) |
| R3 `strings.rs:264`: `{name}` fallback becomes `""` | KILLED (`the_grant_notice_uses_the_design_text_with_the_device_name`) |
| R4 `diff.rs:123`: turn-on fires on `None → Some(true)` | KILLED (`a_failed_terminal_read_is_not_a_transition`) |
| R5 `diff.rs:134`: the grant notice never carries a name | KILLED (3 diff tests + the revoke/grant poller test) |
| U1 `DevicesView.tsx:44-45`: no last-good set (`polled ?? []`) | KILLED ("keeps the last good grants…") |
| U2 `useTerminal.ts:109`: `ids === null` returns null (ignores its own read) | KILLED (2 Terminal tests) |
| U3 `useTerminal.ts:96`: `devicesKnown: true` | KILLED ("a failed devices read is not 'no longer paired'…") |
| U4 `useTerminal.ts:112`: drop the server's own name, use the device list only | **SURVIVED** (NIT-1) |
| U5 `CommandFailed.tsx:14`: no catalog lookup | KILLED (CommandFailed EN/ID test) |
| U6 `TerminalView.tsx:136`: old `noGrants` rule `(granted?.length ?? 0) === 0` | SURVIVED, **equivalent** (NIT-4) |
| U7 `useCommand.ts`: back to `service is <word>` | KILLED (useCommand + Status not-ok tests) |

- Probe (scratch `review_probe_grant_in_a_tick_whose_devices_read_failed`): baseline with `[]` grants and both devices known;
  next tick `devices list` fails and `terminal status` shows `[dev_a1]` → `[TerminalGranted { device_name: None }]`, i.e.
  "A device can open a shell…" although the baseline knows `dev_a1` = "pixel". See MINOR-1.
- Builder M1–M3 and PM B1–B2 (`pm-mutants.log`) agree with the above. The builder did not see the Rust tests fail before
  writing the fix, but R1–R5 show that each one fails when its part of the fix is reverted.

## Check 1: P22

| Case | Result | Where |
|---|---|---|
| Grant while off | one `TerminalGranted` | `diff.rs:126-137` (no switch condition); tests `diff.rs` `a_grant_while_the_switch_is_off_is_its_own_notice`, `poller_stamp.rs:387` |
| Grant while on | one `TerminalGranted` | same branch; existing test `new_grant_notifies_by_device_name` |
| Enable + grant in one tick | two different notices, `TurnedOn` then `Granted` | `diff.rs:123-137`; test `enable_and_grant_in_one_tick_is_two_different_notices` |
| Off→on | `TermOn*` / `n_d7_*`, unchanged | `strings.rs:257-260`; test `the_d7_notice_uses_the_design_text_and_no_name` |
| Tray restart | first poll = baseline, no notice (also when that first terminal read fails: the baseline stores `None`, so the next good read diffs against nothing) | `diff.rs:49-51`, `poller.rs:41-43`; test `existing_grants_after_a_tray_restart_are_the_baseline` |
| Server stop/start | stopped → `None` (`poller.rs:51` merges only while running), so existing grants are not re-announced | `diff.rs:126` |
| `{name}` | device name from `devices list`, else `ADevice` ("A device" / "Sebuah perangkat", design.md:507) | `strings.rs:261-269` |
| EN + ID text | equal to design.md:490-491, character for character | `strings.rs:167-170, 205-208`; test `strings.rs:243` |
| P11 | name only; no code, payload, fingerprint or token; no `{` left in the text | `diff.rs:11`, `strings.rs:261-269` |
| Open button | none (same as D7) | `diff.rs:34-39`, `notifier.rs:18`. design.md:525 says "none on the D7 one" only. See NIT-3 |

## Check 2: every reader of `terminal_granted: Option`

| Reader | Behaviour on `None` / `null` | Where |
|---|---|---|
| Snapshot | `None` when `terminal status` failed or the server is down (no longer `[]`) | `snapshot.rs:214`; test `state.rs` `a_failed_terminal_read_makes_the_grants_unknown_not_empty` |
| Tray diff | either side `None` → silent | `diff.rs:126`; test `an_unknown_grant_list_is_no_information` |
| Poller baseline | keeps the last good `enabled` and `granted` independently while the server runs | `poller.rs:54-57`. Failed read, then revoke a1 + grant b2: **exactly one** notice (`tablet`), then silence (`poller_stamp.rs:356`, R1 killed) |
| App DTO | `Option<Vec<String>>` → JSON `null` | `dto.rs:43-44,71`; `backend.rs:168` |
| TS type | `string[] \| null` | `types.ts:111-112` |
| Status | `?.length ?? null` → `—` (and the row is `—` anyway when `enabled` is null) | `StatusView.tsx:162-164,186` |
| Devices | last good set kept in a ref; before any good read the set is empty, so the column shows `—` (design 4.2 "— where none exists") | `DevicesView.tsx:21,41-46` |
| Terminal list | poll ids, else the view's own `terminal status` read, else `null` → `—` and no add row | `useTerminal.ts:94,108-114`, `TerminalLists.tsx:33-35,53` |
| "No phone holds a grant" | only when `granted !== null && length === 0` | `TerminalView.tsx:136` |
| "(no longer paired)" | only when the devices read is known, else the id is shown | `TerminalLists.tsx:39-43`, `useTerminal.ts:96` (but see NIT-1) |
| Sidebar | reads `terminal_enabled` only | `Sidebar.tsx:31` |

No path turns `None` into "no grants" in the UI. The Terminal view rereads on `polledEnabled` change (`useTerminal.ts:86-89`), so
the fallback read is fresh when the poll's read fails.

## Check 3: the `{ok:false}` text ("error: failed" / "error: gagal")

The text is truthful but weak. I suggest a DF row rather than a change now.
- The banner says `act_failed_b` "The message from the server is below", and the block reads `error: failed`. The server
  sent no error message, though. It sent `{ok:false, service:"failed"}`: the command ran, and the unit ended in that state.
  Calling a state word an "error" text drops the subject (failed *what*?).
- The ID text is a tautology in mixed language: `error: gagal` sits under "Perintah gagal".
- A raw word reads worse still: `error: activating` suggests the error *is* activating.
- The design pattern elsewhere uses the JSON field name as the code-block prefix: `warning:`, `problem:`, `qr_error:`
  (design 4.6/4.8).
- **DF suggestion**: render the not-ok block as `service: <raw §22 word>`. That is the CLI's own field, untranslated like
  every other code block, so there is no language mix. If a sentence is wanted, add one design.md key (e.g.
  `act_notok_b` "The service is {word} after the command." / "Layanan {word} setelah perintah.", `{word}` through `svc_*`).
  No new key was added in DS8b, which is correct.

## Check 4: tests and regressions

- The tests fail when the fix is reverted (mutant table above). The UI tests were seen RED first (build report). The Rust tests
  were proven by mutants (builder, PM and reviewer).
- No DS8 regression:
  - Devices still makes no `terminal_status` call (`DevicesView.test.tsx:69`).
  - D7 has no Open action.
  - The banner placement tests pass.
  - The hint classes keep their 12/16 `--on-surface-v` through `.hint`, and only the margins move to `.hint-field`/`.hint-tight`
    (`pair.css:8,45`).
  - The dead `terminal_status` fakes are gone, and nothing else calls them (grep).

## Findings

### MINOR-1: a grant in a tick whose `devices list` read failed says "A device" although the name is known
- `diff.rs:128` takes names from `next.devices` only. The poller passes the raw snapshot as `next` (`poller.rs:31-33`), while
  the baseline `prev` still holds the last good device list (`poller.rs:52`).
- Probe: `[TerminalGranted { device_name: None }]` for `dev_a1` = "pixel". P22 says "`{name}` = device name, `ADevice`
  fallback", and the fallback is meant for an unknown id, not for a transient read failure.
- `TerminalOpened` has the same pattern at `diff.rs:108`. It predates DS8b.
- Fix (DF):
  - `let devices = next.devices.as_deref().or(prev.devices.as_deref()).unwrap_or(&[]);` in both places, plus a poller test
    (the probe above);
  - or carry the server's `granted[].name` (`server/internal/app/terminalcmds.go:63-66`) in the snapshot and prefer it.

### NIT-1: the server's own empty name *is* "no longer paired", but the view shows the id when the devices read failed
- `terminalcmds.go:65` documents `Name: "" when the device is no longer paired`. When the view's own read lists the id with
  `name: ""`, that is known data, yet `TerminalLists.tsx:43` shows the id whenever `devicesKnown` is false.
- The test `TerminalView.test.tsx:382` pins exactly that case (server name `""`, devices `null` → id). The id then appears
  twice, in the `<strong>` and in the `kv-sub` line.
- U4 survived: no test checks that the server name wins, or that it fills the gap when the devices read fails.
- Fix (DF): `t_gone` when the own read contains the id with an empty name, or when the devices list is known and lacks it. Show
  the id (once) only when neither source knows. Add a test where the devices read is `null` and the server name is "pixel".

### NIT-2: stale comments
- `snapshot.rs:70` still says "(empty when that read failed)"; it is `None` now.
- `strings.rs:79`: `TermOn*` is no longer sent "on a new grant".
- `TerminalLists.tsx:9`: null now means that neither the poll nor the view's own read is known.
- `diff.rs:22,24,33` say D7/P20 and "the D7 notice" for the grant notice too.

### NIT-3 (design gap): the Open button on the grant notice
design.md:525 removes Open only from "the D7 one". The code also drops it for `n_grant_*` (`diff.rs:34-39`). This is
defensible, but nothing records it. The PM should decide, then state it in design.md §6. Open → Terminal would let the user
remove an unexpected grant at once.

### NIT-4: unreachable guard
- `TerminalView.tsx:136` `granted !== null` cannot be false while a dialog is open:
  - Turn on is locked while `data === null` (`TerminalView.tsx:50`);
  - with `data` set, `granted` is never null (`useTerminal.ts:17,109`).
- U6 is therefore equivalent. The guard is harmless; keep it as documentation or drop it.

### NIT-5: Devices writes a ref during render
- `DevicesView.tsx:42-45` sets `last.current` in the render body. React's rules discourage this; it works here only because
  it is idempotent.
- Structurally, the last-good rule now lives in three places: `Poller::baseline_for`, the Devices ref and the Terminal own-read
  fallback.
- Consider (DF): keep the last good `terminal_enabled`/`terminal_granted` once in the app `Backend` or the snapshot store, as
  the DS8 report suggested. Devices, Status and Terminal then read one value.

### NIT-6: test hygiene
- `core/tests/strings.rs:225-239`: a bare `{ … }` block is left over from the removed loop.
- `core/tests/poller_stamp.rs:388` flips the switch with `.replace("true", "false")` over the whole JSON. This is fragile; pass
  `enabled` to `terminal_json` instead.

### FYI: flaky pre-existing UI test (not DS8b)
- `ServiceView.test.tsx:231` "opens the stop dialog once the first poll says Stop is available" failed 2 times in 12 runs
  on an unmutated copy of this tree:
  - once inside a full run while cargo was compiling in parallel;
  - once in 3 standalone runs.
  - After that, 0 of 8 failed.
- The file last changed in 4051790 (DS8). I did not capture the failure text. The likely cause is the dialog's focus
  (`toHaveFocus` right after `findByRole`).
- DF row: triage it (flaky-test-triage) before it reddens a gate.

## Files
- Written: `docs/desktop/runs/DS8b/review-report.md` (this file). Nothing else in the repo was touched. Scratch copy:
  `~/.hermes/profiles/pm/cache/scratch/ds8b-review/`.
