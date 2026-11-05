# TD5 report — Screener design board
- Done: `docs/design/v2/screener.html` (304 KB, self-contained, 0 URLs; source + `build.py` in `screener-src/`) and `screener-notes.md` (59 lines, Compose mapping, slop 0/10).
- 13 screens × light + dark: Stocks home (top, scrolled to Screener; watchlist rows carry a setup label, bearish in loss colours), Screener page (Today, ⋮ Scan now cooling down, Last close), settings (setups + thresholds, invalid value, liquidity / scan / universe, Edit list sheet), states (before first scan, Yahoo blocked, no candidates, PC offline, offline settings). Plus the home section in each state and a density × label-style specimen.
- Tweaks: candidate rows (two-line 64 rec. / one-line 52), setup labels (tonal rec. / outline), home section (under watchlist rec. / first), universe (Edit list rec. / read-only), theme, clickable path with a scenario switch.
- Samples are labelled; ranking and scores are computed from TS4's weights and the §20.3 fields (the sample sort order is checked in code).
- Verified in Chromium: renders, no JS errors, no overflow at 393 dp in either density, clickable path (segment, filters, steppers, Save, sheet, all 6 scenarios) works, screenshots checked.
- Needs the user: Edit list on the phone goes beyond TS4's read-only proposal (brief asked for it); the scan-interval range (5-60 min) is a placeholder until the job's limits are known.
- Notes: specified but not drawn: session break, partial scan, expired list. No Analyze on rows (TA2). No commits.
