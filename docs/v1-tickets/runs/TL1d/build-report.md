# TL1d build report

- D49: `InstanceName(host, fp)` → `Aristotle on <short host> (<8 hex>)`; `TargetHost(host, fp)` → `aristotle-<host>-<8hex>.local.` (a-z0-9- only). Both labels ≤ 63 bytes; the host part is cut (rune boundary for the instance), never the hint. No fingerprint → no suffix. `begin` passes `Announcer.Fingerprint`.
- D46: `decide` drops packets > 9000 bytes and queries with > 32 questions (counted question by question, stops at 33, before answering). The read buffer is now 9001 bytes so an oversized datagram is detectable instead of being silently truncated.
- Tests: same hostname/different fp → distinct names; 70-char, accented and CJK hostnames (length, UTF-8 validity, DNS-safe target, `newZone` accepts); 33 questions dropped / 32 answered; 9000+ byte packet dropped. Existing tests adapted to the new signatures.
- Docs: `docs/protocol.md` §15 — naming rule, 63-byte rule, phone does not depend on the instance name (filters TXT `fp` and pins), the caps.
- Gate: `GATE_ONLY=server bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TL1d` exit 0 (gofmt, vet, 370 go tests pass, netns test included).
- Board: mark D46 and D49 DONE (PM edits `tickets.md`). No commits; android untouched.
