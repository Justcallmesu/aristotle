# DF10 build report — flaky `ServiceView.test.tsx:231`

Result: **test race, fixed in the test. No product bug.**

## Reproduction (before the fix)
- File alone, 3 loops at once (CPU load), `vitest run src/views/service/ServiceView.test.tsx`: 149 runs, **5 failures** (A 0/50, B 2/50, C 3/49; stopped at 3 failures).
  A single run alone passed 1/1. The brief's "full suite pre-fix loop" was **not run**: the file loop already hit 3+ failures, and the fix was verified on the full suite after.
- Failure texts: `docs/desktop/runs/DF10/failures/{fileB-run28,fileB-run45,fileC-run28,fileC-run43,fileC-run49}.txt`.
- All 5 identical: test `Service: --confirm stop start intent (P14) > opens the stop dialog once the first poll says Stop is available`,
  failing line `ServiceView.test.tsx:234` (`expect(Cancel).toHaveFocus()`):
  `Expected element with focus: <button class="btn btn-outline">Cancel</button> / Received element with focus: <body>`.
  The dumped DOM contains the `alertdialog` (scrim, `aria-modal`), so the dialog was mounted but focus had not moved yet.
  (The brief said line 231 = the `it(` line; the assertion is line 234.)

## Proven cause
- `ConfirmDialog` focuses Cancel in a `useEffect` on mount (`components/ConfirmDialog.tsx:38-40`). Product code is correct.
- In this test the dialog opens from `useConfirmStopIntent`'s effect after the first poll, outside `act()`. `findByRole` resolves on the
  DOM mutation (RTL runs it with `IS_REACT_ACT_ENVIRONMENT` off), which can be before React flushes the dialog's passive effect.
  The immediate `getByRole(...).toHaveFocus()` then races the effect. Every other focus assertion opens the dialog through `userEvent.click` (act-wrapped, effects flushed), so they do not race.
- Probe (throwaway copy of the test file, deleted): record `document.activeElement === Cancel` right after `findByRole`, then `waitFor` focus.
  3 loops under load, 75 runs: 74 focused immediately, **1 "dialog present, not focused yet", then focused within the `waitFor`**. 0 probe-run failures. Confirms the mechanism.

## Fix
`desktop/ui/src/views/service/ServiceView.test.tsx` (only file changed), line 234:
`expect(...Cancel).toHaveFocus()` → `await waitFor(() => expect(...Cancel).toHaveFocus())`, plus a one-line comment. Assertion not loosened (same expectation, waits for the effect).

## After the fix
- File alone, 4 loops at once (load): 100 runs, **0 failures** (25/25 ×4).
- Full suite (`vitest run`, 16 files / 289 tests), 2 loops at once: 30 runs, **0 failures** (15/15 ×2).

## Same pattern in other tests (sites checked)
Only an assertion on focus/effect-driven state right after an async (non-act) open races. Checked every `toHaveFocus` in `desktop/ui/src`:
- `ServiceView.test.tsx:234` — **fixed**.
- `ServiceView.test.tsx:118, 163, 122` — dialog opened by `userEvent.click`, act-flushed; safe.
- `components/components.test.tsx:181, 193, 202-206` — click-opened / `userEvent.tab`; safe.
- `TerminalView.test.tsx:201, 245, 275` — dialog opened by an awaited click; safe.
- `DevicesView.test.tsx:182, 196` — same; safe.
- `ServiceView.test.tsx:242, 250` (other intent tests) — `findByRole` then click, no focus assertion; safe.
No other site changed. (Not hardened: those would be churn without a failing path.)

## Notes
- No git writes, installs, app launch, or server commands. All loops and the background job finished; the probe file was removed.
- Loop script lived in scratch (`loop.sh`), not the repo.
