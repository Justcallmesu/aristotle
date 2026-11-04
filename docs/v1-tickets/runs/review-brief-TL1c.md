# Re-review brief — TL1 + TL1c + TM1 (phone LAN discovery, app-lock cap) + TL1d (server, committed 584b7f4) — SECURITY

Fresh reviewer, deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/v1-tickets/runs/TL1/review-report.md` (MAJOR-1,
MINOR-1..4, NITs), `runs/TL1c/build-brief.md` + `build-report.md`, `runs/TM1/report.md`, `runs/TL1d/build-report.md`, `docs/protocol.md`
§15, `docs/spec.md` Q65. Diff: `git diff HEAD -- android CHANGELOG.md` + untracked files; and `git show 584b7f4` (TL1d). PM gate
`runs/pm-gate-49` GREEN (808); PM mutation (ScanGate ignores VPN) fails 1. Device fact: auto-discovery moved the phone Tailscale -> LAN in
~2.5 s on a real Wi-Fi (TL1 build, before TL1c). Edit nothing but your report; no gradle; no git state changes; no adb.

Verify each previous finding is closed with file:line, especially MAJOR-1 (every discovery socket — NSD resolve, /24 TCP probe, pinned
/healthz, the trial relay through a found address? — bound to the Wi-Fi Network; the throwaway bind test meaningful; DNS via the network;
same pin/TrustManager; no token on health checks). Then attack the NEW behaviour: the trial second relay while online over Tailscale makes
the server close the working one with 4409 — can a hostile on-link forwarder (passes the pin) cause a reconnect loop / repeated outages
(rate limit? backoff? stored only after gateway.ready — and then a forwarder that drops later?); does the trial relay send the device token
to the found address (it must, to relay — acceptable since TLS is pinned end-to-end? confirm the pin is enforced on that socket);
app-lock check ids and the cap timer (no leak, Unlock always works, cancelled checks send nothing); TL1d naming/caps. Only BLOCKER/MAJOR
trigger a fix round. Report ≤30 lines at `docs/v1-tickets/runs/TL1c/review-report.md`, last line `VERDICT: PASS | PASS-WITH-FINDINGS |
CHANGES-REQUIRED`.
