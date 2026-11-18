# Views wave review — DS4, DS5, DS6, DS1d + DS3x/DS3y re-check (56ad86a)

Reviewer: fresh-context deep review. Scope: the uncommitted tree (`git status --short`, `git diff`) and commit `56ad86a`.
Read-only on the repo: no code edited and no git write commands run. The app and tray were not launched, and aristotle-server was
not run against the real service. Mutants ran on a scratch copy of `desktop/ui`
(`~/.hermes/profiles/pm/cache/scratch/views-review/`, node_modules symlinked), never on the tree.

## Verdict: **APPROVED-WITH-FINDINGS**

No BLOCKER. One MAJOR, which is a planning gap rather than a defect in this wave's code: the D7 "notification after the change" is
not implemented by any component, and no ticket covers it. It needs a ticket before DS7. All D7/D15 dialog paths, P11 handling,
truthfulness, the tray-rule agreement on the Service view, and the DS1d contract hold. The MINORs are a stale gate floor, two
design-rule inconsistencies, shared-CSS/helper duplication across the three views, and one untested intent path.

## V1 (PM mutant that survived): equivalent mutant, no test missing

- `useCommand.run` drops any call made while one is in flight. The check is `if (busy.current) return undefined;`
  (`desktop/ui/src/state/useCommand.ts:53`), and `busy` is set synchronously before the first `await` (`:54`). `confirmRevoke`
  (`views/devices/DevicesView.tsx:90-94`) runs in one handler, so V1's extra `revoke.run(...)` takes the slot and the original call
  is dropped. The device is sent once either way. The mutant used `asking?.id`, which is the same id.
- The guard has its own test: `useCommand.test.tsx:53-63` "does not start a second run while one is working".
- "Revoke sent exactly once" is asserted at `DevicesView.test.tsx:188`. Evidence from the scratch copy:
  - V1 alone: SURVIVED. The PM result reproduces.
  - V1 with the busy guard removed: **KILLED** ("confirm runs devices_revoke once with the id…").
  - Guard removed alone: **KILLED** (useCommand test).
- The same "exactly once" check exists for the other confirms. Stop: `ServiceView.test.tsx:127`, an exact
  `[{action:"stop"}]`. Restart: `:154`. Terminal enable: `TerminalView.test.tsx:187`, `:239`. Grant: `:269`. Turn off: `:296`.
- Action: none. V1 can be logged as an equivalent mutant.

## What I verified (evidence)

- **Tests (own runs).**
  - Vitest on the scratch copy: 224/225 pass. The one failure is `ipc.test.ts` reading `../../app/src/command_list.rs`
    relative to its path, which does not exist in scratch. The PM gate shows 225/225.
  - `go test -count=1 ./internal/termqr ./internal/app`: ok. `gofmt -l` on both packages: empty.
- **Reviewer mutants** (`scratch/views-review-mut.sh`, `.out`):
  - KILLED: stop intent opens even when Stop is unavailable; NeedsServer Start ignores mismatch; Status Start ignores mismatch;
    grant confirm sends another id; D7 enable confirm not red; problem state keeps the switch on; service "wait for next poll"
    dropped.
  - SURVIVED, and each is a finding below: stray `--pair` intent not dropped (MINOR-6); revoke buttons not disabled while
    working (NIT); dialog double-confirm guard removed (NIT, equivalent because of the useCommand guard, already DF8-5);
    turn-off `open` source swapped (NIT-7); `shownFor` limited to an empty payload (NIT-1, the suggested fix).
- **D7/D15 (check 1).** Every destructive or access-widening path goes through `ConfirmDialog`:
  - Stop and Restart: `ServiceView.tsx:54,57,69-84`.
  - Tray `--confirm stop`: opens the dialog only, never stops directly (`ServiceView.tsx:93-103`).
  - Revoke: `DevicesView.tsx:136,150-159`.
  - Terminal on, by switch and by button: `TerminalView.tsx:104,109`. Grant: `TerminalLists.tsx:72` and `TerminalView.tsx:124`.
  - Turn off when `open` is non-empty: `TerminalView.tsx:55-58`.
  - The only direct calls are Start (Service, Status, NeedsServer), Remove grant and turn-off with nothing open, which is
    D15/D19-8 as written.
  - Cancel, Esc and scrim send nothing, and focus starts on Cancel (`ConfirmDialog.tsx:40-53,63,78`). There is no "don't ask
    again". The D7 confirms are filled danger (default `confirmVariant`, `TerminalDialogs.tsx:23,30,41`) and the restart confirm is
    primary, per design.md 3.6.
  - Double clicks: action buttons are disabled while working, `ConfirmDialog` has the `done` guard, and `useCommand` has the
    busy guard. The app has no keyboard shortcuts.
  - Rust re-checks mismatch and not-installed on every write (`app/src/backend.rs:98-116`, used by `service.rs:50`,
    `devices.rs:10`, `terminal.rs:30`, `pairing.rs:14`).
- **P11 (check 2).**
  - Where the code lives: the code, payload, modules and fingerprint live only in `PairFlow`'s `shown` state
    (`PairView.tsx:74`). The `useCommand` wrapper returns `null`, so the hook never keeps the reply (`:79-82`). Expiry swaps the
    state to `{kind:"expired", addresses}` (`:94-102`, PM V2 killed). Unmount drops it (test `PairView.test.tsx` "unmounting
    drops the code").
  - Logging and storage: nothing in `src/` outside tests uses `console.*`, `localStorage`, `sessionStorage` or `innerHTML` (grep).
    Rust `Pair`'s `Debug` hides `qr_modules` too (`core/src/types.rs` diff). `CliError::BadOutput` carries no text
    (`core/src/runner.rs:39`).
  - Bundle: the dev pages (`views/status/shots/`, `views/pair/preview/`, `views/terminal/shots.*`) are not in the bundle.
    `vite.config.ts` sets no `rollupOptions.input`, so only `/index.html` is built. `dist/` holds `index.html` plus one JS and
    one CSS file, and grep finds no `shots`, `preview`, `fixtures` or `K7Q2`. `tauri.conf.json` points `frontendDist` at
    `../ui/dist`. The harness code is the existing Go fixture's fake `K7Q2M9XD`, not a real code.
- **Truthfulness (check 3).**
  - `{ok:false}` counts as a failure (`useCommand.ts:59-63`).
  - The revoke row goes away only through the snapshot. The backend publishes a fresh poll before replying
    (`app/src/ipc.rs:85-93`).
  - Start, Stop and Restart stay "working" until a later poll (`views/status/useServiceAction.ts:25,32`).
  - Terminal state comes from the server's reply (`TerminalView.tsx:50-53`), and stale reads are ticketed
    (`useTerminal.ts:54-70`).
  - Stale replies after unmount are ignored: `useCommand` `mounted`, the `alive` flags in `DevicesView.tsx:41-48` and
    `StatusView.tsx:161-168`, and React's no-op on unmounted `setShown`.
- **Tray rule (check 4).** `serviceButtons.ts:19-29` matches `tray/src/menu.rs:66-75` for every word: active → Stop and Restart;
  inactive or failed → Start; anything else, mismatch or not installed → none; no-answer with active → Stop and Restart. Both
  intents fire once per id: `ServiceView.tsx:96-102` and `PairView.tsx:87-92` acknowledge first, and `useCommand` drops a
  repeat. There is no StrictMode double effect (`main.tsx:1`).
- **Logs (check 5).**
  - Start and stop: `logs_start` on mount and `logs_stop` on unmount, which is also every view change because `CurrentView`
    swaps the component (`LogsView.tsx:127-147`). Window close stops the stream (`app/src/main.rs:52-53,65-66`).
  - Cap: 2,000 lines (`logBuffer.ts:2,22`).
  - Text only: text nodes, no HTML (`logLine.tsx:37-52`).
  - Reconnect: `LogsView.tsx:149-153`.
- **DS1d (check 6).**
  - Encoding: `qr_modules` comes from the exact `qr_payload` text (`server/internal/app/cli.go:221-237`). The payload holds all
    addresses up to `MaxPayloadAddresses`, and `TestJSONPairQRModulesMatchThePayload` compares 9 addresses against a re-encode
    (PM G1 killed).
  - Absence: `omitempty` leaves the field out when the payload is empty (test covers it). Plain `pair` uses `qrFor`, which is
    untouched, and the goldens pass.
  - Docs and types: the §22 text matches the code. Rust `Option<Vec<String>>` with `default`/`skip_serializing_if`, TS
    `qr_modules?: string[]`. `termqr.Modules` reuses the one encoder (`termqr.go:42-57`).
- **DS3x/DS3y re-check.**
  - `logs.rs` stop and reaper: `stop()` takes the stream out under the lock and then stops it outside the lock (`:84-89`).
    `stop_running` (`:99-125`) does killpg, then a bounded `try_wait`, then `kill`, then a detached reaper. It never blocks
    without bound, and `stopped` mutes late events.
  - `service.rs:35-41,57-59`: the stamp is kept on `Timeout` and deleted on every definite failure, including `ok:false`.
  - `useCommand`: see V1 and DS3y.
  - `NeedsServer`/`CommandFailed`: fine, apart from MINOR-2 and MINOR-4.
  - Snapshot `seq`: taken under the snapshotter lock (`backend.rs:71-81`), and the UI keeps the higher one (`snapshot.tsx:6-8`).
  - `PendingIntent`: queued before the first read, emitted after it (`args.rs:43-74`). See NIT-6 for the frontend side.
- **Screenshots (check 7), spot-checked.**
  - Spot-checked: `DS5/shots/pair-code-{light,dark}`, `dev-failed-light`, `pair-failed-dark`, `pair-expired-light`,
    `dev-list-light`, `dev-needs-server-dark`; `DS6/shots/on-light`, `dialog-enable-dark`, `failed-light`, `problem-dark`;
    `DS4/shots/status-running-light`, `service-other-word-dark`, `logs-live-dark`, `service-working-light`,
    `status-start-failed-light`.
  - Conformant: tokens, both themes, QR tile with quiet zone and the same colours in both themes, D7 warn box with the salmon
    confirm in dark, ERROR/WARN log rows, dead tile.
  - Issues: the failure banner is double-indented in Pair and Devices (MINOR-4). Service with another systemctl word shows three
    dimmed buttons with no reason (MINOR-3).

## Findings

### MAJOR

**MAJOR-1: the D7 notification after enable or grant exists nowhere.**
- Requirement: spec D7 ("notification after the change") and `design.md:308` ("After a successful enable or grant the window sends
  the D7 notification").
- Current state:
  - DS6 left it out (`docs/desktop/runs/DS6/build-report.md`, "no IPC command exists"). The DS6 brief said "the tray's job".
  - The tray has no such diff. The only trace is the unused `Key::TermOnTitle/TermOnBody` in `desktop/core/src/strings.rs:79-81`,
    whose comment says "Sent by the window app".
  - `app/src/ipc.rs:101-114` (`terminal_change`) never notifies.
  - No ticket or DF row tracks it.
- Fix: ticket it before DS7. Preferred: the core snapshot already fetches the full `terminal status` each poll
  (`core/src/snapshot.rs:210-211,267`). Add `terminal_granted` ids to the snapshot and have the tray diff notify `TermOn*` on
  `terminal_enabled` false→true and on a new grant id. This also covers CLI changes and removes the need for a granted count
  (MINOR-5c). Alternative: notify from `terminal_change` after a successful enable or grant, which adds notify-rust to the app
  crate.

### MINOR

**MINOR-1: gate floors are stale.**
- `docs/desktop/gates.sh:19` has `MIN_UI_TESTS=73` while the suite has 225.
- `:18` has `MIN_TESTS=145` while the suite has 148.
- With the floor at a third of the UI suite, deleting a whole views test file would still pass the gate.
- Fix: raise both to the counted totals in this commit.

**MINOR-2: there are two "can Start" rules.**
- Status (`views/status/StatusView.tsx:24`) and `components/NeedsServer.tsx:21` offer Start for any `service != "active"`, which
  includes `activating`, `deactivating`, `reloading` and `unknown`.
- Service (`views/service/serviceButtons.ts:27-28`) and the tray (`tray/src/menu.rs:70-74`) allow Start only on
  `inactive`/`failed`.
- design.md supports both readings (`:261` and `:256` say `service != active`; `:270` gives the tray rule). The result is that the
  same state shows Start on Status and no Start on Service.
- Fix: PM decision. Recommended: one shared `canStart(status, world)` (the serviceButtons rule) used by all three, and amend
  design.md 4.2/4.3.

**MINOR-3: Service with another systemctl word disables all three buttons without a written reason.**
- `serviceButtons.ts:28` returns `off(null)`, and the `service-other-word` shot shows three dimmed buttons and no hint.
- This breaks design.md 3.3 (`:181`: "the reason is written beside it, never only a dimmed button"). The builder notes design.md
  has no key for it.
- Fix: add a design.md key, for example `dis_other` "Start, Stop and Restart are off while the service is {word}." (EN + ID), and
  return it here. The busy state is fine as it is, because the working bar names it.

**MINOR-4: the failure banner is double-indented and placed inconsistently.**
- Cause: `components/CommandFailed.tsx:13` wraps the banner in `.banner-slot`, which has margin `24px 24px 0`
  (`styles/shell.css:36`), but views render it inside the already padded `.content`.
- DS4 and DS6 each override this locally (`views/status/rows.css:30`; `views/terminal/terminal.css:22`).
- DS5 does not override it, so the banner sits indented above the h1: `PairView.tsx:106,113,155`, `DevicesView.tsx:98`, shots
  `dev-failed-light` and `pair-failed-dark`. `NeedsServer.tsx:25` has the same problem in all three Phones views.
- Placement also differs: above the h1 in Pair and Devices, below the header in Service and Terminal.
- Fix (before release; the DF sweep is fine): drop the wrapper, or the margin, in `CommandFailed`. Delete both local overrides.
  Pick one placement (design 4.2 says "above the view content", meaning after the header) and use it everywhere.

**MINOR-5: duplication across the three views (PROJECT.md "no duplication").**
- (a) Time text is written three times:
  - `views/status/facts.ts:23-45` (`seenText`, its own month tables, `hhmm`, `sameDay`) duplicates
    `views/devices/time.ts:3-46` (`relativeTime`, `clock`, `dayMonth`).
  - `views/terminal/format.ts:2-7` is a third `clock`.
- (b) Grouped-row and state-row CSS exists twice: `status/rows.css` (`kv-*`) and `terminal/terminal.css:1-14` (`tm-state`,
  `tm-row`, `tm-sub`).
- (c) Hint classes are repeated four times: `.reason-hint` in `devices/devices.css:19` and again in `pair/pair.css:11`, plus
  `.svc-hint` and `.tm-hint`.
- (d) Three separate `terminal_status` readers exist because the snapshot has no granted list: `StatusView.tsx:156-171`,
  `DevicesView.tsx:35-51`, `terminal/useTerminal.ts`.
- (e) Shared pieces live in view folders: Pair imports `../devices/time` (`PairView.tsx:14`), and Service imports
  `../status/{facts,Rows,useServiceAction}` (`ServiceView.tsx:10-12`).
- Fix in the DF sweep, before DS7:
  - (a) one `src/format/time.ts`;
  - (b, c) move `kv-*` and one hint class into `styles/components.css`, and make Terminal use them;
  - (d) granted ids in the snapshot (see MAJOR-1);
  - (e) move the shared helpers out of the view folders.

**MINOR-6: untested intent path.**
- `PairView.tsx:45-48` drops a `--pair` hand-off that arrives while the server is down. Without this, a code would be minted later
  when the server starts, with no user action at that moment. The reviewer mutant that removes the drop SURVIVED.
- Fix: add a test. Intent "pair" plus a stopped snapshot, then a running snapshot: `pairCalls()` stays at 0.

### NIT

1. **Encode failure shows the "no listener" state.** `PairView.tsx:36-38` treats any `qr_error` as "No address to pair on", but
   DS1d sets `qr_error` with a non-empty payload when encoding fails (`cli.go:231-234`). That would hide a valid code behind a
   wrong explanation. It is unreachable in practice: 8 URLs are far below the QR v40 capacity. Fix: use `!pair.qr_payload` only.
   That is the reviewer mutant R2, which the tests already accept. Show the code without a tile and put `qr_error` under Details.
2. **Stale cast in `qr.ts`.** `views/pair/qr.ts:6-11` still casts for an optional `qr_modules`, but DS1d added it to `ipc/types.ts`
   (`:85`). Drop the cast and the comment.
3. **Cap note format in Indonesian.** `LogsView.tsx:96` formats the cap with `en-US`, so ID shows "2,000" where "2.000" is
   expected. Use the locale.
4. **Unhelpful text for `{ok:false}`.** It renders `error: not-ok` (`useCommand.ts:62` with `CommandFailed.tsx:16`). Consider the
   §22 `service` word or a translated line.
5. **Reconnect sends `logs_stop` then `logs_start`.** See `LogsView.tsx:142-152`. Each runs on its own blocking thread, so a
   late stop could kill the new stream silently, because `stopped` mutes the Ended event (`logs.rs:84-89,168`). This is
   theoretical. `logs_start` already replaces a running stream (`logs.rs:54,76-79`), so Reconnect needs only start.
6. **`IntentProvider` race.** It calls `startIntent()` without waiting for the `start-intent` listener (`state/intent.tsx:47-48`).
   A hand-off between `window_ready` and the listener registration would be lost. Theoretical. Fix: await `on()`, then read.
7. **Turn-off decides from the polled `terminals`.** That list can be up to 5 s old (`useTerminal.ts:79`, `TerminalView.tsx:55-58`),
   so a terminal opened inside the last poll interval is closed without the D19-8 dialog. Fix: re-read `terminal status` before
   choosing, or use the newer of the read and the poll.
8. **"Working" lingers.** `useServiceAction.ts:25` records the seq at reply time. The backend publishes the post-action poll
   before replying (`ipc.rs:71-73`), so "Stopping…" can stay up to 5 s under a headline that already says "Server stopped". This
   is conservative, not a lie. The tests emit after the reply, so this ordering is not modelled.
9. **Untested guards.** Revoke buttons disabled while working (`DevicesView.tsx:135`, mutant survived) and the dialog `done` guard
   (DF8-5) have no tests. Both are covered in effect by the useCommand guard.
10. **design.md and code have drifted.** design.md §1 uses `:root[data-theme]` while `styles/tokens.css:11,24` uses
    `prefers-color-scheme`. The behaviour is the same (P7). Update design.md rather than the code. Also record the Paired column
    `dmy` format (`devices/time.ts:26-29`: "Oct 3, 2026" / "3 Okt 2026") in design.md 4.7.
11. **`PairFlow` is too long.** `PairView.tsx:69-194` is about 125 lines with three inline state panels. Extract `ExpiredPanel`
    and `NoListenerPanel`.

## Builder deviations: rulings

- **Logs when stopped:** DS4 followed design.md 4.5 over the brief's "needs-server". Correct: design.md is binding, and the journal
  is readable while the server is down.
- **Other service words with no reason:** not acceptable as is (MINOR-3).
- **Invalid `qr_modules` → code without a QR tile:** acceptable. The code and fingerprint still allow manual pairing, and the
  server guarantees the shape. An explanatory line is optional.
- **Date format:** acceptable. Record it in design.md (NIT-10).

## Shared-code gaps: fix before release or in the DF sweep

| Gap | When |
|---|---|
| Banner double spacing (MINOR-4) | DF sweep, before DS7 (visible to the user) |
| No shared row/table/field CSS, `kv-*`/`tm-*` (MINOR-5b/c) | DF sweep |
| `data-theme` vs `prefers-color-scheme` | doc-only fix, DF |
| No granted list in the snapshot (MINOR-5d) | together with MAJOR-1's ticket |
| `fakeTransport` cannot change replies | DF (test infra; every view patches `transport.invoke` by hand) |
| Gate floors (MINOR-1) | this commit |

## User live checks still needed

- Note: the installed server is v2.1.1 without `--json`, so these need the DS7 install, or a dev binary via
  `ARISTOTLE_SERVER_BIN`.
- **Pair:** scan the QR drawn from `qr_modules` with the real phone, at 248 px and 208 px (narrow window), in light and dark.
- **Tray hand-off:** "Pair a phone…" and "Stop server…" into a running window and on a cold start. Each should issue one code or
  open one dialog.
- **Service:** Stop and Restart on the real unit from the window. Expect no "stopped unexpectedly" notification (P15 stamp).
  Check Start from Status and from the Phones views' needs-server state.
- **Logs:** a real journal stream. After leaving the view and after closing the window, no `aristotle-server logs -f` or
  `journalctl` process may remain (`ps`). Test Reconnect after `systemctl --user restart`.
- **Devices and Terminal:** revoke a test device. Enable and grant through the D7 dialog with a real phone.
- **Look and locale:** a live KDE light/dark switch, the Indonesian locale, and the 720 × 520 minimum window.
- **D7 notification:** once MAJOR-1 is built.
