# DS8b fix brief — round 2 of 2 (last): DS8 re-review findings

Repo `/home/justcallmesu/project/personal/Aristotle` (read `PROJECT.md`). Input: `docs/desktop/runs/DS8/review-report.md` (read in
full). Spec `docs/desktop/spec.md` P11, P20, **P22** (new, user decision Q-D7b = A); `docs/desktop/design.md` (new strings
`n_grant_t`/`n_grant_b`, §4.8 notification line, §4.2 "a failed read leaves the last good data").

## Fix (each with a test seen RED first; never git for that)
1. **MINOR-1 / P22**: a new granted id produces its own notice with `n_grant_t`/`n_grant_b` (`{name}` = device name, `ADevice`
   fallback), whether the switch is on or off; off→on keeps `TermOn*`/`n_d7_*`; both in one tick = two notices. New keys in
   `desktop/core/src/strings.rs` (EN + ID exactly as design.md; the strings test must cover them). Tests: grant while off, grant while
   on, enable + grant in one tick, existing grants after a tray restart (baseline, no notice).
2. **MINOR-2** (design §4.2): a failed terminal read must not blank the grants. Snapshot: distinguish "unknown" from "none"
   (`terminal_granted: Option<Vec<String>>`, `None` on a failed read; the tray diff treats `None` as "no information", like
   `terminal_enabled`). Devices keeps the last good set; Terminal falls back to its own `terminal_status` read; the enable dialog's
   "no phone holds a grant" and the "(no longer paired)" label only on known data (a failed devices read is not "unpaired"). One test
   per view + core/diff tests for `None`.
3. NITs: `{ok:false}` text via i18n (EN + ID; reuse an existing key if one fits, else report the key you need — do not invent
   design.md keys: put the text in the report and stop that item); remove the dead `terminal_status` replies in
   `status/shots/main.tsx:87`, `pair/preview/main.tsx:141`; `.field-hint`/`.pair-hint` -> shared `.hint`; a core test for "failed
   read, then revoke + new grant" (one notice for the new grant only, none for the revoke).

## Files you may edit
`desktop/core/**`, `desktop/tray/**`, `desktop/app/**`, `desktop/ui/**`, `docs/desktop/gates.sh` (floors = new totals only).

## Verify
`timeout 1800 bash docs/desktop/gates.sh docs/desktop/runs/DS8b/self-gate` all PASS; paste the summary verbatim.

## Hard rules
Never `git add/commit/push/checkout/restore/stash/reset`. No installs/new deps. No app/tray launch against the real server; never run
aristotle-server against the real service. No skill/memory edits. Kill every process you start.

## Report
`docs/desktop/runs/DS8b/build-report.md`: per item change (file:line), tests, RED evidence, gate summary, anything left.
