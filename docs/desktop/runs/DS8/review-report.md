# DS8 re-review: views-wave fix round 1 (tree on top of c1047d9)

Reviewer: fresh context, deep. Scope: the uncommitted tree (`git status --short`, `git diff`) on c1047d9.
Read-only on the repo. No code was edited, no git write commands were run, the app and tray were not launched, and
aristotle-server was not run. Probes and mutants ran on a scratch copy
(`~/.hermes/profiles/pm/cache/scratch/ds8-review/`, `mut.py`, `desktop/core/tests/review_extra.rs`), never on the tree.

## Verdict: **APPROVED-WITH-FINDINGS**

There is no BLOCKER or MAJOR. The P20 tray logic is correct in every scenario the brief listed. P21 agrees between the tray and
the UI for every service word. Every prior finding the brief named is closed. Two MINORs remain:
- one is a design/spec gap: a grant made while the switch is off produces the "turned on" text;
- one is a small regression: when the window's poll fails to read the terminal, it no longer keeps the last good grants
  (design 4.2).

## Evidence (own runs)

- `cargo test -p aristotle-core -p aristotle-tray` (CARGO_TARGET_DIR=desktop/target): all ok. `npx vitest run`: 16 files,
  **284/284**. The PM gate summary agrees (158 Rust / 284 UI, all PASS).
- P20 probes (scratch `review_extra.rs`, a fake CLI that answers by command). **6/6 pass**:
  - Baseline `[a1]`, then two failed `terminal status` reads, then `[b2]` (a1 revoked, b2 granted): exactly one
    `TerminalGranted{tablet}`, then silence.
  - Switch off, then a failed read, then on: one `TerminalTurnedOn`, then silence.
  - A failed `status` poll keeps the baseline. The next good poll gives `TurnedOn` and `Granted` once.
  - Tray restart, including a first poll whose terminal read fails: the next good read is the baseline, so no re-notify.
  - Server stop: only `ServerStopped`. On restart, existing grants are not notified again: `baseline_for` stores
    `terminal_enabled=None` while stopped (`poller.rs:51`), and `diff.rs:122-124` is silent when either side is `None`.
  - A grant while the switch is off gives `TerminalGranted`. This is MINOR-1 below.
- Reviewer mutants (scratch, each restored):

| Mutant | Result |
|---|---|
| R1 failed read keeps `enabled` but drops `terminal_granted` (`poller.rs:56`) | KILLED (`a_failed_terminal_read_keeps_the_granted_baseline…`) |
| R2 pre-DS8 baseline line (`enabled.or(prev)` only) | KILLED (same test) |
| R3 grant notices only while the switch is on (`diff.rs:129`) | **SURVIVED**, see MINOR-1 |
| U1 = builder M4: turn-off decides from the poll | KILLED (Terminal "a terminal opened since the last poll…") |
| U2 = builder M5: intent read before the listener | KILLED (`intent.test` "registers the listener before…") |
| U3 Devices ignores `snapshot.terminal_granted` | KILLED (2 Devices tests) |

- Screenshots spot-checked: `dev-failed-light` (the banner sits flush under the header, above the table) and
  `service-other-word-dark` (three dimmed buttons plus "Start, Stop and Restart are off while the service is activating.").

## Check 1: prior findings

| Prior | Status | Where |
|---|---|---|
| MAJOR-1 D7 notice | closed | `snapshot.rs:70-71,214-216`; `diff.rs:22-27,59,119-140`; `strings.rs:245-248`; `notifier.rs:12-19` (no Open action, design 6 l.523); `dto.rs:43-44,71`; `types.ts:112` |
| MINOR-1 floors | closed | `gates.sh:18-19` = 158 / 284, matching the counted totals |
| MINOR-2 one Start rule | closed | `state/serviceButtons.ts:18-21`, used by `StatusView.tsx:26`, `ServiceView.tsx:26` (via `serviceButtons`), `NeedsServer.tsx:22` |
| MINOR-3 `dis_other` | closed | `serviceButtons.ts:38`; `en.json:67` and `id.json:67` match design.md:385 exactly; the word comes from the catalog, raw when missing (`ServiceView.tsx:91-93`) |
| MINOR-4 banner | closed | `CommandFailed.tsx:12-19` is a bare `Banner`; `rows.css` is deleted; `.tm-view .banner-slot` is gone; placement is header → alert in all views (tests assert `nextElementSibling`). `.banner-slot` remains only for the shell mismatch banner (`Shell.tsx:23`), which is correct |
| MINOR-5a time | closed | `format/time.ts` is the only month table and clock (grep: no other `MON_` or `getHours`; `useCountdown.mmss` is a countdown, not a clock) |
| MINOR-5b/c CSS | closed for the named classes | `components.css:91-120`. `.reason-hint`, `.svc-hint`, `.tm-hint`, `.status-working-hint` and `tm-row/state/group/sub/end` are gone (grep empty). See NIT-3 |
| MINOR-5d grants from snapshot | closed (see MINOR-2) | `StatusView.tsx:164`, `DevicesView.tsx:37`, `useTerminal.ts:90`; no `terminalStatus` calls outside `useTerminal` |
| MINOR-5e shared homes | closed | `state/facts.ts`, `state/useServiceAction.ts`, `components/Rows.tsx`, `test/views.tsx`; no import of an old path remains (grep) |
| MINOR-6 stray `--pair` | closed | `PairView.tsx:38-41` plus the Pair test (builder M8) |
| NIT-1 | closed | `PairView.tsx:28-32`; `qr_error` shown under Details beside the code (`PairPanels.tsx:63,68-77`) |
| NIT-2 | closed | `qr.ts:7-8` |
| NIT-3 | closed | `LogsView.tsx:96` |
| NIT-4 | closed, but see NIT-1 | `useCommand.ts:27-30,68` |
| NIT-5 | closed | `LogsView.tsx:127-159`: Reconnect = `logs_start` only; the effect no longer re-runs |
| NIT-6 | closed | `intent.tsx:47-57` |
| NIT-7 | closed | `TerminalView.tsx:57-64`, `useTerminal.ts:75-82`; the dialog count is the fresh one (`TerminalDialogs.tsx:7-8,24`) |
| NIT-11 | closed | `PairPanels.tsx`; `PairView.tsx` is 160 lines |
| `fakeTransport.setReply` | closed | `test/fakes.tsx:42-68`; no `transport.invoke =` patching left (grep) |

## Check 2: P20 (code reading and probes)

- **Once per off→on**: `diff.rs:126-128`. **Once per new id**: `diff.rs:129-138` (set difference against `prev`). The removed
  grant is silent.
- **First snapshot is the baseline**: `diff.rs:49-51`. The poller only sets `last_good` on an answered poll (`poller.rs:41-43`).
- **A failed read** is silent (`diff.rs:122-124`) and keeps the old pair (`poller.rs:54-57`). A failed read followed by a revoke
  plus a new grant notifies only the new id (probe 1).
- **P11**: the title and body are static `TermOnTitle/TermOnBody` (`strings.rs:245-248`). The device name never reaches the
  text. EN/ID equal design `n_d7_t/n_d7_b`, and `strings.rs` test `the_d7_notice_uses_the_design_text_and_no_name` pins it.
- **Same-tick interaction**:
  - A server stop makes `next.terminal_enabled` `None`, so there is no D7 notice, only `ServerStopped`.
  - Hermes going down and a grant in the same tick give both notices (independent branches, `diff.rs:57-65`).
  - Enabling and granting within one poll gives two toasts with identical text (`a_removed_grant_is_silent_and_enable_plus_grant…`).
    That matches P20 as written. It is noted, not a finding.

## Check 3: P21

The tray (`menu.rs:36-44,66-75`: `inactive|failed` → Start, `active` → Stop…, anything else, mismatch or not installed →
neither) and the UI `canStart` (`serviceButtons.ts:18-21`) agree for active, inactive, failed, activating, deactivating,
reloading, unknown and maintenance. Both read the mismatch from the same `VersionCheck::Mismatch`, exposed as
`version.kind === "mismatch"`. "Not busy" is enforced by the callers, which disable the button. Tests run all 8 words on the
rule (`serviceButtons.test.ts:7,18`), Status (`StatusView.test.tsx:236`), NeedsServer (`shared.test.tsx:54`) and Service.

## Findings

### MINOR-1: a grant made while the switch is off sends "Remote terminal turned on", and nothing pins that behaviour
- `diff.rs:129-138` notifies every new granted id whatever the switch says, and `strings.rs:245-248` gives it
  `n_d7_t` "Remote terminal turned on" / "Granted phones can open a shell…".
- Granting while off is a real path: the Grant row is "Shown whether the switch is on or off" (design.md:304), and the server
  allows it (`server/internal/terminal/grants.go:131-139`).
- With the switch off, the toast's title is false. P20 says "on each new granted id", and design.md:488 has only the
  "turned on" text, so the code follows the spec and the gap is in the spec and design text.
- R3 (suppressing grant notices while off) SURVIVED, and the scratch probe `grant_while_switch_off_notifies_turned_on_text`
  shows the current output. No test pins either behaviour.
- Fix (PM decision, then a test that pins it): either
  - (a) add a grant text, e.g. `n_d7g_t` "{name} can now open a remote terminal" (names are allowed by P11, and the
    `device_name` is already carried in the notice); or
  - (b) notify grants only while `enabled`. The later off→on notice then covers them.

### MINOR-2: the window drops the last good grants on a failed terminal read (design 4.2), and names can read "(no longer paired)"
- design.md:257 says "a failed … `terminal status` read leaves the last good data and shows `—` where none exists". The window
  backend polls with the bare `Snapshotter` (`app/src/backend.rs:71-80`), and `snapshot.rs:214-216` makes
  `terminal_granted` `[]` when the read fails. So after DS8:
  - **Devices**: one failed poll turns every Granted tag into `—` (`DevicesView.tsx:37,92`). Before, `useGrantedIds` kept the
    last good set (c1047d9 `DevicesView.tsx:44`, "keep the last good data").
  - **Terminal**: `grantedFrom` returns null (`useTerminal.ts:90,100-102`), so the list shows `—`, even though the view's own
    `terminal status` read (`data`) holds the grants. The enable dialog then gets `noGrants=true` (`TerminalView.tsx:135`) and
    says "No phone holds a grant yet", which is untrue.
  - **Terminal names**: they now come only from the devices list (`useTerminal.ts:52,101`). If `devices list` fails while
    `terminal status` works, every grant reads `t_gone` "(no longer paired)" (`TerminalLists.tsx:37,41`), which is false. Before,
    the server's own `granted[].name` was used.
- This is transient (one poll) and display-only. Writes still go through the dialogs, so it is not a safety issue.
- Fix:
  - Terminal: fall back to the view's own read (`data` keeps `granted` with the server names) when the poll could not read
    it. Take names from `granted[].name || devices`.
  - Devices: keep the last good granted set while `terminal_enabled === null` (a ref in the view, or carry the last good pair
    in `Backend` the way `Poller::baseline_for` does).
  - Add one test per view: a good poll, then a poll with `terminal_enabled: null` → the tags and grant rows stay.

### NIT
1. **NIT-4 text is English-only.** `useCommand.ts:29` builds `service is <word>`, which is app text and not CLI text, so the ID
   locale shows English inside the code block. Acceptable, as the builder reported. If it is kept, consider
   `tr.word("svc_", …)` at the view, or leave it as the raw §22 word only (`error: failed`).
2. **Dead fake branches.** `case "terminal_status"` in `views/status/shots/main.tsx:87` and `views/pair/preview/main.tsx:141`.
   No view on those pages reads it any more (Status and Devices take grants from the snapshot). Delete both.
3. **Two hint look-alikes remain.** `.field-hint` (`pair.css:8`) and `.pair-hint` (`pair.css:45`) repeat `.hint`'s 12/16
   `--on-surface-v` with different margins. MINOR-5c did not name them. Fold them into `.hint` plus a margin modifier in a DF
   sweep.
4. **Untested probe scenario.** The tree's test covers "failed read, then a new grant", but not "failed read, then revoke a1
   plus grant b2" (the brief's case). The logic is right (scratch probe 1). Optionally copy that probe into `poller_stamp.rs`.
5. **Stray empty dir.** `desktop/desktop/` exists and is empty (mtime Nov 18 12:56). Git does not show it. Remove it.

### Out of scope (noted, no action for DS8)
- The `service-other-word` shot shows the headline "Server stopped" with "The service is inactive. Phones can't connect…" while
  the service word is `activating`. That is the pre-existing world row 4 `sub_stopped` text (design 4.1), not DS8's change, but
  the sub states a word that is not the current one. It is worth a design.md look in the DF sweep.

## Refactor (check 4)

The only behaviour changes are the intended ones:
- banner placement;
- the Start rule;
- `dis_other`;
- grants read from the snapshot (MINOR-2 is its side effect);
- turn-off re-reads before choosing;
- Reconnect sends start only;
- the listener is registered before the intent is read;
- the `status-working` hint margin goes from 8 to 10 px, matching design 4.4's hint margin.

The `kv-*` rules moved verbatim from `rows.css` into `components.css:94-120`, apart from the deleted `.kv-failed` override.
Lint and typecheck pass, so no unused imports remain. All files deleted in the diff have no remaining importers (grep).

## Tests (check 5)

The tests are meaningful: the per-word tables, placement checks by DOM sibling, and a fresh-read turn-off test.
- Builder mutants spot-checked: M4 and M5 re-run and KILLED.
- PM mutants D1-D3 and U1 are KILLED, according to `pm-mutants.log`.
- The only gap found is R3, under MINOR-1.
