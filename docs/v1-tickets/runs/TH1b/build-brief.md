# TH1b — TH1 review fix round

route: std. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/v1-tickets/runs/TH1/review-report.md` (MAJOR-1 + NITs with
file:line), `runs/TH1/build-brief.md` + `build-report.md`, `docs/spec.md` Q73. TH1 is uncommitted in the tree; continue it.

## Do
1. **MAJOR-1:** a deleted address is remembered (blocked from coming back via the server list) only when another address of the SAME
   kind (LAN / Tailscale) remains stored; otherwise the deletion is not remembered, so the server list can bring it back. Word the confirm
   dialog per case (EN + ID, append new keys; the old false sentence must no longer show): remembered ("…won't come back from the PC's
   list; Find PC on this Wi-Fi brings a Wi-Fi address back") vs not ("…the PC may add it again the next time it shares its addresses").
   Tests: delete the only Tailscale address with a LAN one left -> not remembered and re-added by a refresh; delete one of two LAN
   addresses -> remembered.
2. NIT: history entries dated in the future (clock moved back) are clamped or pruned (treat > now + 1 day as expired). Test.
3. NIT: no history line may be written after Unpair cleared the log (drop events for a PC id that is no longer paired). Test.
4. NIT: `lastSuccessAt` is written at most once per address per hour (no credential rewrite on every connect). Test.

## Hard rules
Files: `android/**` (not `ui/theme/**`), `docs/v1-tickets/runs/TH1b/build-report.md`. strings append-only. A design worker writes
`docs/design/v2/**` now: don't touch it. No commits; never git checkout/restore/stash/reset. No adb.
Verify: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TH1b` exits 0. Report ≤12 lines.
