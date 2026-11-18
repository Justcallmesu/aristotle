# DS3y fix brief — `useCommand`: a `{ ok: false }` result is a failure

Repo `/home/justcallmesu/project/personal/Aristotle`. File: `desktop/ui/src/state/useCommand.ts` (+ its test
`useCommand.test.tsx`). Today a command that resolves `{ ok: false }` (the §22 action shape, `docs/protocol.md` §22) is reported as
**succeeded** (`useCommand.ts:29,56-58`), so every view built on it (DS4-DS6, running next in parallel) would show success for a
failed start/stop/revoke. Change: when the resolved value is an object with `ok === false`, the phase is `failed` with
`error = { kind: "not-ok", message: null, exit_code: null }` (add `"not-ok"` to the `IpcError` kind type in `desktop/ui/src/ipc/`
if the type is a closed union) and `result` keeps the value; anything else stays as today. Update the doc comment. Tests: `{ok:false}`
-> failed + result kept; `{ok:true}` and non-object results -> succeeded. See the new test fail first.

Files you may edit: `desktop/ui/src/state/useCommand.ts`, `desktop/ui/src/state/useCommand.test.tsx`, `desktop/ui/src/ipc/types.ts`
(kind union only). If the Vitest count floor in `docs/desktop/gates.sh` must change, edit only that number.
Verify: `bash docs/desktop/gates.sh docs/desktop/runs/DS3y/self-gate` all PASS; paste the summary.
Hard rules: never git write commands; no installs; no app launch; no skill/memory edits.
Report: `docs/desktop/runs/DS3y/build-report.md`.
