# T16b build brief — attachment minors (T16 review)

route: std. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/v1-tickets/runs/T16/review-report.md` (MINOR-1..4 + NITs),
then the cited files (committed in HEAD).

## Fix (each with a test that fails before the fix; keep a scratch copy of the old file to prove it, never use git to revert)
1. MINOR-1: prepare attachments with bounded concurrency (at most 2 at once); decode images with inSampleSize/target size so a full-res
   photo never decodes at full size when it will be downscaled; catch OutOfMemoryError per item -> that chip shows "Too large to prepare".
2. MINOR-3: route by content, not the picker's MIME: sniff the first bytes (JPEG/PNG/WebP/HEIC/GIF signatures) for every pick, so an image
   picked through the file picker goes through the image path (GPS stripping) even when labelled `application/octet-stream`/`image/jpg`.
3. MINOR-2: also blank GPS and drop XMP in additional JPEG images (MPF / Ultra-HDR secondary images) and extended XMP (APP1
   `http://ns.adobe.com/xmp/extension/`); if a structure can't be parsed safely, fall back to re-encoding as plain JPEG (no metadata).
4. MINOR-4: the upload call timeout grows with size (e.g. 15 s + 1 s per 32 KiB, max 120 s); other calls on the socket are not starved
   beyond that (say how the relay call queue behaves).
5. NITs: delete prepared copies and camera temp files when the message is sent or the chip removed; strip XMP from GIFs (or re-encode).

## Hard rules
Files: `android/**`, `docs/v1-tickets/runs/T16b/build-report.md`. No commits; never git checkout/restore/stash/reset. Do not install on
the phone.

## Verify
`GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/T16b` exits 0.

## Report
`docs/v1-tickets/runs/T16b/build-report.md`, ≤20 lines, listing which tests failed against the old code.
