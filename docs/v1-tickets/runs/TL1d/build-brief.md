# TL1d — mDNS instance name carries the fingerprint hint (D49) + question cap (D46)

route: std. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/protocol.md` §15, `docs/v1-tickets/tickets.md` rows D46,
D49, `docs/v1-tickets/runs/TL1b/{build-report,review-report}.md`, then `server/internal/discovery/` (zone.go, filter.go, discovery.go and
tests).

## Do
1. **D49:** instance name `Aristotle on <short hostname> (<first 8 hex of the fingerprint, lowercase>)`, so two PCs with the same
   hostname never share an instance name. Keep the whole instance label ≤ 63 bytes (RFC 6763): truncate the hostname part (on a UTF-8
   boundary), never the hint. The target host name `aristotle-<host>.local.` gets the same hint suffix (`aristotle-<host>-<8hex>.local.`,
   label ≤ 63, DNS-safe characters only). Tests: two zones with the same hostname and different fingerprints → distinct names; a 70-char
   hostname; a non-ASCII hostname.
2. **D46:** drop queries with more than 32 questions (before any work), and any packet > 9000 bytes. Test.
3. `docs/protocol.md` §15: the new naming rule; the question cap. The phone does not depend on the instance name (it filters on TXT
   `fp` and pins) — say so.
4. Mark D46 and D49 DONE in `docs/v1-tickets/tickets.md` only in your report (the PM edits the board).

## Hard rules
Files: `server/internal/discovery/**`, `docs/protocol.md` §15, `docs/v1-tickets/runs/TL1d/build-report.md`. Another worker edits
`android/**` now: don't touch it. Never run a responder on a real interface (the netns test is fine); never touch port 8760. No commits;
never git checkout/restore/stash/reset.
Verify: `GATE_ONLY=server bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TL1d` exits 0. Report ≤12 lines.
