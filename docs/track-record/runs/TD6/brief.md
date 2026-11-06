# TD6 — design board: AI track record (phone)

route: std. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/track-record/spec.md` (Q116-Q120 and readings), `docs/design.md`,
`docs/design/v2/stocks-notes.md`, `docs/design/v2/screener-notes.md`, and copy the frame / tokens / fonts / Tweaks pattern from
`docs/design/v2/screener.html` (source + `build.py` in `docs/design/v2/screener-src/`). Load the skills `claude-design` and `antislop-ui`.
Look at the existing verdict card (`android/app/src/main/java/io/github/justcallmesu/aristotle/ui/stocks/VerdictCard.kt`) and the stock page
(`StockDetailScreen.kt`) so the new parts sit in the real layout.

## Make
`docs/design/v3/track-record.html` (self-contained, 0 external URLs) + `docs/design/v3/track-record-notes.md` (≤ 60 lines, Compose mapping),
source in `docs/design/v3/track-record-src/`:
1. **Track record page** (from Stocks ⋮ and from the verdict card line): summary per verdict type (Buy today, Add, Hold, Avoid, Sell; Wait
   as "would have made"), columns win / loss / not triggered / closed flat, average result %, and the 3-day info column visibly secondary
   (Q117). Filters: model (Opus / Sonnet), trigger (manual / auto), confidence (low / med / high). A "how scoring works" sheet in plain words
   (Q117-Q120, stop-first rule).
2. **Verdict card line:** one short line under the verdict, e.g. "AI record on Buy today: 11 of 24 hit target" + tap to the page; how it
   looks with too few calls (decide a minimum, e.g. < 10 -> "not enough calls yet").
3. **Stock page:** "Past calls" list for that stock (date, verdict, model, result chip), newest first, collapsed to 3.
4. **States:** no history yet (feature just installed), small sample, today's calls not scored yet ("scored after 16:20"), PC offline.
Light + dark, EN + ID strings, realistic IDX samples clearly labelled as samples. Two variants in Tweaks where taste matters (summary as
table vs cards; result chips style); mark one recommended. 393 dp width without overflow, 1.3x text checked.

## Rules
Edit only `docs/design/v3/**` and `docs/track-record/runs/TD6/report.md` (≤ 10 lines). No commits; never `git checkout/restore/stash/reset`.
No new numbers or rules beyond the spec: anything the spec leaves open goes in the report under "Needs the user". Avoid `bash -c`,
heredocs and chained `$(...)`: script files run with plain `bash <file>`. Verify in Chromium (renders, no JS errors, no overflow) and
look at the screenshots before reporting. A research worker (TR0) runs in parallel and only writes `docs/track-record/runs/TR0/`.
