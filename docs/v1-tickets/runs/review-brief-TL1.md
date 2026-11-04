# Review brief — TL1 (phone LAN discovery) + TM1 — SECURITY

Fresh reviewer, deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/spec.md` Q49, Q65, Q71, `docs/protocol.md` §15,
`docs/v1-tickets/runs/TL1/build-brief.md` + `build-report.md`, `runs/TM1/build-brief.md` + `report.md`, `runs/review-batch-46.md`.
Diff: `git diff HEAD -- android CHANGELOG.md docs` + untracked files. PM gate `runs/pm-gate-47` GREEN (781); PM mutations: pinned
`/healthz` check skipped -> 3 fail; fp prefix filter off -> 2 fail. Edit nothing but your report; no gradle; no git state changes; no adb.

Attack, with file:line: can a hostile host on the same Wi-Fi get its address stored or used (spoofed announcement with a matching fp
prefix — the fp is public; is the pinned TLS check really the same pinned client/trust manager as the relay, incl. hostname/IP handling;
redirects; a captive portal; a host that passes `/healthz` once then swaps); is the stored-address list poisonable (8-cap eviction of the
typed/last-good address, flooding with many verified-looking candidates, IPv6/link-local/loopback/public IPs from NSD); the /24 probe
(only on tap; on cellular? prefix rules; not bound to the Wi-Fi network — could probes leave over VPN/Tailscale or cellular?; rate);
multicast lock release on every path; NSD callbacks after cancel; resolve serialisation on API < 34 deadlocks; battery (repeated Wi-Fi
joins); logs leak nothing (fingerprints, addresses at info level?); permissions claim for API 29-36 and the SDK 37 note. TM1: auth cap
correctness, per-PC ServerVersions races closed. Only BLOCKER/MAJOR trigger a fix round. Report ≤30 lines at
`docs/v1-tickets/runs/TL1/review-report.md`, last line `VERDICT: PASS | PASS-WITH-FINDINGS | CHANGES-REQUIRED`.
