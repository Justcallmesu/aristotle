# T16b build report — attachment minors (T16 review)

Gate: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/T16b` → PASS, exit 0 (lint, unit tests, assemble). Nothing run on a phone or against Hermes; no commit.

- **MINOR-1** `ChatComposer.attach`: a shared `Semaphore(2)` limits preparing; `OutOfMemoryError` per item → chip "Too large to prepare" (`AttachmentProblem.OutOfMemory`, EN+ID strings); `AttachmentFiles` also catches it. Source is read straight into an array sized from `meta.size` (no 2–3× stream copies); bitmaps recycled. Decode already used `ImageDecoder.setTargetSize` (decoder samples while reading, so no full-size bitmap); that part was not changed.
- **MINOR-3** `AttachmentRules.routesAsImage`: every File pick is sniffed (first 16 bytes: JPEG/PNG/GIF/WebP/HEIF) and goes the image path whatever the MIME; `IMAGE_TYPES` removed.
- **MINOR-2** `ImageMetadata.sanitizeJpeg` parses all scans and every JPEG after EOI (MPF/Ultra-HDR); blanks GPS in each Exif, removes XMP + extended XMP (`xmp/extension/`). With further images, XMP is zeroed in place so MPF offsets hold. Returns null (→ re-encode as plain JPEG) for: no EOI, non-JPEG trailer, unparseable later image, Exif whose GPS IFD/data lies outside the block.
- **NIT GIF** `ImageMetadata.stripGif` removes `XMP Data` extensions, keeps animation; unparseable → re-encode (one frame).
- **MINOR-4** `AttachmentRules.uploadTimeoutMillis` = 15 s + 1 s/32 KiB, max 120 s (a full 1 MB frame → 47 s). `RelayLink.callWithin` (default = `call`, so fakes unchanged) → `RelayRpc.call(timeout=)`; `ProfileLink` forwards. Only staging calls use it. Queue behaviour: `RelayRpc` has no queue; calls are written at once to the one OkHttp socket (FIFO writes), each timer starts at its own write. A slow upload still delays writes behind it, and those calls keep their 15 s; not changed.
- **NIT cleanup** prepared copies are discarded when Hermes admits the message (kept while a retry may need them); cancelled/failed camera → `ComposerActions.cameraCancelled` → `AttachmentSource.discardCamera`. Chip removal already discarded.
- Tests added: ImageMetadataTest (4 JPEG + 1 GIF), AttachmentRulesTest (routing, timeout), RpcFramesTest (per-call timeout), ChatViewModelAttachTest (≤2 concurrent, OOM, timeouts passed, discard after send, camera cancel).
- **Failed against old behaviour** (mutated copies of the new code; old files kept in `$TMPDIR/T16b-old`, no git): ≤2 concurrent, OOM chip, discard after send, camera cancel, file-pick routing, timeout growth, per-call RPC timeout, extended XMP removal, secondary-JPEG GPS, single-image XMP removal, unparseable→null. Several also fail to compile against the old API (no `sanitizeJpeg`/`callWithin`/`routesAsImage`). The GIF test and the `LinkManager.callWithin` wiring were not mutation-checked (the VM tests use a fake link).
- NOT-VERIFIED: HEIC/real-photo decode memory, Poco camera output (MPF/trailers), real uplink timing.

## Follow-up T16c (D43)

- Fixed `sniff` to reject ISO-BMFF files with non-HEIF ftyp brands (MP4 isom/mp42, M4A) by validating major brand and compatible brands against whitelist (heic, heix, hevc, hevx, heim, heis, mif1, msf1, avif, avis).
- Added BMP header validation: require valid DIB size (12/40/52/56/108/124) at offset 14-17 to reject files like "BMW..." text that only have the magic.
- Unit tests added: MP4 isom/mp42 → Other, M4A → Other, HEIC major brand → Heif, mif1 with heic compatible → Heif, fake "BMW" → Other, real BMP → Bmp.
