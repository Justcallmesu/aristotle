# Review brief — T08 (QR pairing) + re-check of T13b/T13c

Fresh reviewer; you did not write this. Pairing is the trust root. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`,
`docs/spec.md` Q7b, Q49; `docs/protocol.md` §11 (T08 edited §11.1, §11.3, added §11.4); `docs/design.md` pairing paragraph (PM updated it
for Q49); `docs/v1-tickets/runs/T08/build-brief.md` + `build-report.md`; `runs/T13b/build-report.md` (+ its T13c follow-up).
Diff: `git diff HEAD -- server android docs` + untracked files. T13b/T13c are commit `8085f8e`. PM gate GREEN both sides
`runs/pm-gate-23` (271 Go). PM mutations: QR parser fingerprint regex relaxed -> survives (Fingerprint.parse also rejects length: equivalent,
confirm); address-validator skip (mapNotNull) -> 2 tests fail. Edit nothing but your report; no git state changes; no gradle; never run
`aristotle-server pair` against the real state dir (temp XDG dirs only if you run Go tests).

Check with evidence (file:line):
1. Trust: a scanned payload can never pin anything other than its own `fp`; the "different certificate -> try next address" rule cannot be
   abused to pair with an attacker on one address (each attempt pinned to the QR fp?); a 401 stops; the code is single use server-side even
   if the phone tries several addresses (does a 403/conn error on address 1 burn the code?).
2. Payload: escaping, strict keys, version, max 8 + the "fits 80 columns, drops addresses" rule (phone still gets the full list from the
   pair response — verify); 4096-char cap; no device token or other secret in the QR; nothing of the QR/code reaches the server log or
   journald (T21 runs it as a service: does `pair` print via the CLI process only?).
3. Scanner: no CAMERA permission in the merged manifest; failure / missing Play services falls back cleanly; Play-services dependency
   does not pull in analytics or network permissions beyond what the app already has (list merged permissions).
4. One pairing path after parsing (no weaker branch).
5. D39 countdown; T13b backstop cannot run while the link is Stopped by the user; T13c draft pruning keeps timestamp-less drafts.
Only BLOCKER/MAJOR trigger a fix round. Report ≤30 lines at `docs/v1-tickets/runs/T08/review-report.md`, last line
`VERDICT: PASS | PASS-WITH-FINDINGS | CHANGES-REQUIRED`.
