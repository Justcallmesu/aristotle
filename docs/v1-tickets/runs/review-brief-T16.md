# Review brief — T16 (attachments)

Fresh reviewer; you did not write this. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`; `docs/spec.md` Q7d,
Q24; `docs/design.md`; `docs/protocol.md` §5/§11/§12 (T16 edited them); `docs/v1-tickets/runs/T16/build-brief.md` + `build-report.md`.
Diff: `git diff HEAD -- android docs/protocol.md` + untracked android files. PM gate GREEN both sides `runs/pm-gate-29` (274 Go, 478
Android); PM mutation (JPEG GPS not blanked) fails 1 test. Hermes source + Desktop client read-only under
`/home/justcallmesu/.hermes/hermes-agent/`. Edit nothing but your report; no git state changes; no gradle.

Decide with evidence (file:line):
1. `image.attach_bytes`, `file.attach {data_url}`, `prompt.submit attachments` + `@file:` lines: exact vs source; Hermes staging-path rule
   holds for phone uploads; nothing lets the phone choose a filesystem path on the PC.
2. Size math: base64 + JSON envelope really fits the relay's 1 MiB frame at 783,360 bytes (and the redaction/forwarding path does not grow it);
   what happens at 1 MiB+1 (close 1009 kills the whole relay socket? then every session drops — severity).
3. Retry/idempotency: files not re-uploaded (prompt text stable), images re-upload safe (Hermes dedups by bytes — cite); no double submit;
   upload failure never sends a prompt without its attachments.
4. Privacy: GPS stripping correct for JPEG (all GPS IFDs, thumbnails' IFD1?), PNG eXIf, WebP EXIF/XMP; re-encode path writes no Exif;
   file attachments (PDFs, docs) are sent as-is — acceptable?
5. Android: FileProvider paths minimal (no broad roots), camera intent without CAMERA permission really works when the manifest declares
   none (Android rule: if CAMERA is declared but not granted the intent fails — confirm none is merged), temp files cleaned up, no storage
   permission, memory (10 × 765 KiB base64 in memory at once?).
6. 15 s relay call timeout vs a ~1 MB upload over Tailscale: real risk? what does the user see?
Only BLOCKER/MAJOR trigger a fix round. Report ≤35 lines at `docs/v1-tickets/runs/T16/review-report.md`, last line
`VERDICT: PASS | PASS-WITH-FINDINGS | CHANGES-REQUIRED`.
