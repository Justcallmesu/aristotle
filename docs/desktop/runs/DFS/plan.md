# DFS plan (lines verified against the tree at b611b6f)

| Id | Where now | Plan |
|---|---|---|
| DF7-a | `core/src/runner.rs:203` `try_wait` Err returns with the child unreaped | kill group + `wait` there; no test (cannot make `try_wait` fail) |
| DF7-b | `core/src/poller.rs:32-38` read then `delete()` | `ExpectedStop::delete_if(at)` deletes only when the file still holds the value read; unit test |
| DF7-c | `core/src/stamp.rs:40-44` garbage reads as `None`, never deleted | `read()` removes a file that holds no number; test |
| DF7-d | `core/src/strings.rs:3-4,60,64-86` stale "added in DS3" comments, no design key per `Key` | comment-only: fix header + per-key design names |
| DF7-e | design.md §5 Surface labels / 3 Rust rows / "189 keys" | not doing: design.md is read-only for this sweep -> PM |
| DF7-f | design.md §6 "Start server (stopped)…" | not doing: design.md -> PM |
| DF7-g | `tray/src/tray.rs:62-71,start_server` | Start disabled and re-entry refused while its own `start` runs (guard type + test) |
| DF7-h | `core/src/diff.rs:143-154` `hermes_went_down` fires for "absent" too | not doing: the fix is a notice-policy choice (silent vs a new text) -> PM |
| DF7-n3 | `core/src/strings.rs:~235,248` names raw in bodies | escape `& < >` in names in `notice_text`; test |
| DF7-n5 | `runner.rs:162` + inline copy `stamp.rs:29-31` (launcher already uses `absolute_env_path`) | one `pub fn non_empty_env` in `runner`, stamp uses it; refactor |
| DF7-n6 | `core/src/types.rs:198-202` `ErrorBody`, only used by `tests/fixtures.rs:143` | drop the type and that test (`parse_output` tests cover `{"error"}`) |
| DF7-n7 | `assess`/`TrayState`/`Reason`/`Assessment` in `snapshot.rs:93-170` | move to `core/src/state.rs`; update imports (move-only) |
| DF7-n9 | `snapshot.rs` read-only queries share the 10 s timeout | not doing: `status` includes a 2 s Hermes probe in the server; a blanket 3 s cap risks false Red -> PM (number + which verbs) |
| DF8-1 | `ui/src/state/world.ts:40-55` re-derives §4.1 | stop and report if > ~200 lines (decided after reading the fixtures: ~124 test uses of `snapshotOf`/`withStatus` patch `status` without an assessment, plus the mismatch-hides-the-row case) |
| DF8-4 | `app/src/logs.rs:52-` child has no parent-death signal | spawner thread + `PR_SET_PDEATHSIG`; subprocess test |
| DF8-5 | `ui/src/components/components.test.tsx:186-197` | add a harness that keeps the dialog mounted, double-click; mutant check |
| DF8-6 | `tray/src/icons.rs:76-78` decodes 4 PNGs per call | `OnceLock` per state; test that two calls share data |
| DF8-8 | `core/src/types.rs:28` `Other` serialises as `"Other"` | `#[serde(rename = "other")]`; test; fix `ui/src/ipc/types.ts:39` comment and world test name |
| DF8-9 | `runner.rs:208` `kill_group` after reap | not doing: needs waitid(WNOWAIT) for a pid-reuse case review itself calls practically impossible |
| DF9-m1 | `core/src/diff.rs:108,128` names from `next.devices` only | fall back to `prev.devices`; test (diff + poller) |
| DF9-ok | `ui/src/components/CommandFailed.tsx:14` | not-ok block shows `service: <raw word>`; no new key; tests |
| DF9-n1 | `ui/src/views/terminal/TerminalLists.tsx:43` | empty server name = gone when the id is in the view's own read; test where server name wins |
| DF9-n2 | stale comments `snapshot.rs:70`, `strings.rs:79`, `TerminalLists.tsx:9`, `diff.rs:22,24,33` | comment-only |
| DF9-n4 | `TerminalView.tsx:136` `granted !== null` unreachable | drop the guard |
| DF9-n5 | last-good rule in `Poller::baseline_for`, `DevicesView.tsx:41-46` ref, Terminal fallback | `Snapshot::keeping_last_good` in core, used by Poller and by `Backend::refresh`; drop the Devices ref (Terminal's own read stays: it is its own read) |
| DF9-n6 | `tests/strings.rs:225-239` bare block, `poller_stamp.rs:388` `replace("true","false")` | tidy; `terminal_json` takes `enabled` |
| EXTRA | Service `activating` shows "Server stopped / The service is inactive" | design.md §4.1 row 4 gives `st_stopped`/`sub_stopped` for every `server != running` and has no string for other service words -> report to PM |
