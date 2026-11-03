# T01b build brief — protocol doc fixes from the T01 review

route: std (doc edits that must be checked against frames and Hermes source)

Repo: `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`, then `docs/v1-tickets/runs/T01/review-report.md`
(all findings), `docs/protocol.md`, `docs/spikes/v0-attach.md`. Hermes source (read-only):
`/home/justcallmesu/.hermes/hermes-agent/`. Captured frames: `docs/spikes/frames/`.

## Goal
Make `docs/protocol.md` and `docs/spikes/v0-attach.md` correct and complete enough that T04/T06/T11 can build from them.

## Do
1. **MAJOR — `session.info` two shapes.** Document both: the partial state update and the full inventory. List which
   keys each carries (from source + frames, cite file:line). State the client rule: merge only keys present; never
   replace state with defaults. Note which shapes carry `system_prompt` and `credential_hint` (both are stripped by the relay, spec Q34).
2. **MINOR — frequency.** Correct the "~twice per turn" wording in both docs to what frames and source show (once per
   turn + once per resume + per explicit `session.info` call), with evidence.
3. **NIT — ticket params.** Document the required/optional params of `identify` and `session-ticket` and their errors (cite source).
4. **NIT — 403 cite.** Fix the source line cited for the bad/expired ticket 403 to the real code path.
5. **NIT — snapshot redaction.** If frames still contain skill/tool name lists that `commands.catalog` redaction meant to hide, say in v0-attach.md that they are left in on purpose (private repo, names only) — do not rewrite frames.
6. Add one "Relay redaction" section to `docs/protocol.md`: exactly which fields in which frames the relay strips
   (Q34: `system_prompt`, `credential_hint`), replacement value (`system_prompt` -> its length as `system_prompt_chars`; `credential_hint` removed).

## Hard rules
- Files you may edit: `docs/protocol.md`, `docs/spikes/v0-attach.md`, and create `docs/v1-tickets/runs/T01b/build-report.md`. Nothing else.
- Do not connect to Hermes. No git commands that change state. No commits.
- Every new claim labelled VERIFIED (file:line or frame file + line) or ASSUMED.

## Stop and report if
A finding turns out to be wrong when you check it — report the evidence instead of editing.

## Report
`docs/v1-tickets/runs/T01b/build-report.md`, ≤30 lines: each finding -> FIXED/WRONG with the cite; sections changed.
