# Re-review brief — TM3a2 (fix round for TM3a's 2 MAJORs; uncommitted)

Fresh reviewer, deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/v2-stocks/runs/review-TM3.md` (MAJOR-1 back loop, MAJOR-2 labels vanish,
MINOR pre-09:00 slot), `runs/TM3a2/build-brief.md` + `build-report.md`. Diff: `git diff HEAD -- android` + untracked android files (TM3a itself is
committed 8fff8da). PM: android gate `runs/pm-gate-tm3a2` GREEN; mutation back() never pops -> 3 PairedPageTest failures.
Read-only: only your report; no gradle; no git state changes; no adb.
Check: the back stack really unwinds every path (Home→News→stock→All news→stock→…; Holdings↔stock; Running; Stocks home mode switch; process
death restore; deep entry from a notification later) with system back and the arrow; no regression to Chats navigation (PairedPage routes, the
Running "See all" return-to-chat from TY7); chartHeight measures with the SAME style/density the Canvas draws with (check both code paths), and
the fake-measurer test inputs are faithful (non-linear font scale, app text scale 1.15, Atkinson 1.3 em) — say whether a regression in the
real measuring path could pass the test; the pre-09:00 slot rule with the Friday table. Only BLOCKER/MAJOR trigger a round.
Report ≤ 20 lines at `docs/v2-stocks/runs/TM3a2/review-report.md`, last line `VERDICT: ...`.
