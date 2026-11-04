# Re-review brief — TL1 + TL1c + TL1e + TM1 (+ TS3 nav) — SECURITY

Fresh reviewer, deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/v1-tickets/runs/TL1c/review-report.md`
(MAJOR-2, MINOR-A, NITs), `runs/TL1e/build-brief.md` + `build-report.md`, `runs/TS3/build-report.md`, `docs/protocol.md` §15, `docs/spec.md`
Q65, Q72. Diff: `git diff HEAD -- android CHANGELOG.md docs` + untracked files. PM gate `runs/pm-gate-50` GREEN (831); PM mutation (VPN
route coverage ignored) fails 2. **Device facts (Poco, Android 14):** TL1 build auto-moved Tailscale -> LAN in ~2.5 s on a real Wi-Fi;
TL1c build with Tailscale ON showed "Can't scan this Wi-Fi" (MAJOR-2 confirmed); TL1e build with Tailscale ON: Find PC -> "Found at
192.168.1.61" (user-confirmed). Edit nothing but your report; no gradle; no git state changes; no adb.

Verify MAJOR-2 and MINOR-A closed (file:line). Attack the unbound fallback: can it send probes/checks anywhere but the Wi-Fi /24 (route
reading per API 29-36, most-specific-route logic, excluded routes, IPv4-only, VPN over cellular, a VPN whose routes change mid-scan, an
always-on lockdown VPN), host names never resolved, the per-target `reaches` check before every socket; flap demotion state (per Wi-Fi
key — what identifies a Wi-Fi? SSID needs location permission — so what is used?; bounded memory); NSD on API 33+ bound to the network.
TS3: navigation/back mapping and restore can't open a settings sub-page while the app lock is showing. Only BLOCKER/MAJOR trigger a fix
round. Report ≤25 lines at `docs/v1-tickets/runs/TL1e/review-report.md`, last line `VERDICT: PASS | PASS-WITH-FINDINGS | CHANGES-REQUIRED`.
