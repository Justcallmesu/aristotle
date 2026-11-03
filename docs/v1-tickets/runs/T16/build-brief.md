# T16 build brief — send pictures and files from the phone

route: deep (writes files onto the PC). Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`; `docs/spec.md` §1 v1.4,
Q7, Q7d, Q24 (no share-to in v1); `docs/design.md`; `docs/protocol.md` §5 (prompt.submit), §11, §12 (relay limits: 1 MiB frames,
600 frames/min); then `ui/chat/{Composer*,ChatViewModel,Outbox}.kt`, `data/chat/ComposerFrames.kt`, and on the server `internal/httpapi`,
`internal/relay`, `internal/hermes`. Find out exactly how the Hermes Desktop attaches images and files to a prompt (Hermes source and
`apps/desktop/` under `/home/justcallmesu/.hermes/hermes-agent/`, read-only): inline base64 in `prompt.submit`, a separate upload
method on `/api/ws`, or a REST upload to the dashboard API. Cite file:line. Then build the smallest design that works through the relay.

## Goal
From a chat the user attaches photos (camera roll / take photo) and files (document picker) to a message; Hermes receives them exactly as
if they had been attached on the Desktop, and the Desktop shows the same attachments.

## Requirements
1. Attach button in the composer: Photo (Android photo picker — no storage permission), Camera (ACTION_IMAGE_CAPTURE into the app's cache
   via FileProvider — no CAMERA permission needed for the intent; verify), File (ACTION_OPEN_DOCUMENT). Thumbnails/chips above the field,
   removable before sending; per-file size shown.
2. Transport: whatever the Desktop uses. If it is a `/api/ws` method or inline payload, respect the relay's 1 MiB frame cap and rate limit
   (chunk if Hermes supports chunks; otherwise downscale images per the Desktop's own rules, and refuse files over the limit with a clear
   message — never raise the relay limits silently). If it needs REST that the relay does not carry, add the MINIMUM server endpoint
   (`POST /v1/upload` behind the device token, streamed to the place the Desktop's upload would put it, size cap, content-type allowlist
   matching Hermes, no path from the phone ever used as a filesystem path, files 0600 under a server-owned dir) and document it in
   protocol §11/§12. aristotle-server still never writes Hermes' own files (PROJECT.md rule): if the Desktop's path requires writing into
   Hermes' tree, STOP and report instead of building.
3. Images: strip EXIF location before sending (privacy) unless the Desktop sends originals — say which and why; HEIC -> JPEG if Hermes
   does not accept HEIC (cite).
4. Progress per attachment; failure keeps the draft and attachments for Retry with the same submission id (T13 rule: never double submit).
5. Bubbles: the user's message shows its attachments (thumbnail / file chip); attachments sent from the Desktop render too (read from
   history/events shapes — cite).
6. EN/ID strings.

## Tests
Frame/request builders vs source; size/limit rules; EXIF stripping; retry reuses the submission id; server endpoint (if any): auth,
caps, allowlist, no path traversal, perms, temp XDG dirs.

## Hard rules
Files: `android/**`, `server/**` only if req 2 needs it, `docs/protocol.md` §5/§11/§12, `docs/v1-tickets/runs/T16/build-report.md`.
No commits; never git checkout/restore/stash/reset. Do not install on the phone; do not touch port 8760; never connect to the real Hermes.

## Verify
`bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/T16` exits 0.

## Report
`docs/v1-tickets/runs/T16/build-report.md`, ≤30 lines: how the Desktop attaches (cites), the chosen transport, limits, files, tests,
NOT-VERIFIED.
