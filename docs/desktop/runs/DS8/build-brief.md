# DS8 fix brief — views-wave review findings (before DS7)

Repo `/home/justcallmesu/project/personal/Aristotle` (read `PROJECT.md`). Input: `docs/desktop/runs/views-wave/review-report.md`
(read in full; file:line + fixes). Spec `docs/desktop/spec.md` **P20, P21** (new), D7, P11; `docs/desktop/design.md` (amended:
§4.2 needs-server, §4.3, §4.4 `dis_other`, §4.7 `dmy`, §4.8 D7 notification, §1 theme note; string `dis_other`). Commit c04b8a9.

## Fix (each behaviour change with a test seen RED first; never git for that)
1. **MAJOR-1 / P20**: core snapshot gains `terminal_granted: Vec<String>` (from the `terminal status` the poller already reads);
   tray diff (`desktop/core/src/diff.rs` or wherever notices are computed) emits a `TermOn*` notice on `terminal_enabled`
   false→true and per new granted id (device name from the devices list, `ADevice` fallback; P11: names only); first snapshot =
   baseline. Uses `Key::TermOnTitle/TermOnBody` (`core/src/strings.rs`); check their EN/ID text equals design.md `n_d7_t`/`n_d7_b`.
   Carry `terminal_granted` to the UI snapshot type.
2. **MINOR-2 / P21**: one shared `canStart`/service-buttons rule in `desktop/ui/src/state/` (move `serviceButtons.ts` there), used
   by Status, Service and `NeedsServer`. Tests for every service word on each surface.
3. **MINOR-3**: other systemctl words return `dis_other` with `{word}`; add the key to `en.json`/`id.json` exactly as design.md.
4. **MINOR-4**: `CommandFailed` without the double margin; delete the local overrides (`status/rows.css:30`, `terminal/terminal.css:22`);
   one placement everywhere: after the view header, above the content (design 4.2). Same for `NeedsServer`.
5. **MINOR-5**: (a) one `src/format/time.ts` (relative, clock, dmy; locale-aware) replacing the three copies; (b, c) grouped-row /
   state-row / hint CSS moved to `styles/components.css`, Terminal and Pair/Devices use them; (d) Status, Devices and Terminal read
   granted ids from the snapshot (remove the extra `terminal_status` readers where the snapshot suffices; Terminal may keep its own
   read for `open`/`warning`/`problem`); (e) shared helpers out of view folders (`facts`, `Rows`, `useServiceAction` → `components/`
   or `state/`).
6. **MINOR-6**: test: `--pair` intent + stopped snapshot, then running snapshot → no pair call.
7. NITs 1, 2, 3, 4 (translated line for `{ok:false}`: add no new key if `act_failed_b` fits; else report), 5 (Reconnect = start only),
   6 (await the listener before reading the intent), 7 (re-read `terminal status` before choosing the turn-off path), 11 (split
   `PairFlow`). Leave NIT 8, 9, 10 (10 done in design.md).
8. `fakeTransport` gains a way to change replies mid-test (`setReply(cmd, value)`); replace the hand-patched `transport.invoke`.

## Files you may create/edit
`desktop/core/**`, `desktop/tray/**`, `desktop/app/**`, `desktop/ui/**`, `docs/desktop/gates.sh` (floors = new totals only).
Not: `server/**`, `docs/desktop/{spec,design}.md` (report any mismatch instead).

## Verify
`timeout 1800 bash docs/desktop/gates.sh docs/desktop/runs/DS8/self-gate` all PASS; paste the summary verbatim. Re-shoot the
changed states (banner placement, Service other-word, Status Start rule) light+dark into `docs/desktop/runs/DS8/shots/` via the
existing dev shot pages (stop the vite server afterwards).

## Hard rules
Never `git add/commit/push/checkout/restore/stash/reset`. No installs, no new dependencies. No app/tray launch against the real
server; never run aristotle-server against the real service. No skill/memory edits. Kill every process you start.

## Report
`docs/desktop/runs/DS8/build-report.md`: per item: change (file:line), tests, RED evidence; gate summary; anything left.
