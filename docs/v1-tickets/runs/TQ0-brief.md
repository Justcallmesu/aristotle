# TQ0 brief — verified Aristotle quotes for the loading screen

route: std. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/spec.md` Q44–Q46.

Produce `docs/quotes.md` and `docs/quotes.json` with 20–30 short quotes (≤ 140 characters of English, readable in ~3 s) that are
GENUINELY from Aristotle's surviving works (Nicomachean Ethics, Politics, Metaphysics, Rhetoric, Poetics, De Anima, Posterior
Analytics, …). For each:
- `en`: the English from a public-domain translation (e.g. W. D. Ross 1908 Nicomachean Ethics, Jowett Politics, Ross Metaphysics,
  Rhys Roberts Rhetoric, Butcher/Bywater Poetics); name the translator + year. Light trimming with an ellipsis is allowed; no
  paraphrase.
- `el`: the corresponding Greek (polytonic) from the Perseus Digital Library or Wikisource Greek text, the exact clause.
- `source`: work, book.chapter, Bekker number (e.g. "Nicomachean Ethics II.1, 1103a").
- `url`: a page where both can be checked (Perseus, Wikisource, MIT Classics).
Use web_search / web_extract to VERIFY each one against the text; mark anything you could not verify and leave it out of the JSON.
Also list in `docs/quotes.md` the popular misattributions you came across (quote, real author/origin, source) so they never get added.
Prefer quotes that suit a thinking/working tool (reason, habit, learning, deliberation, friendship, leisure) — no politics of slavery/gender.
Files you may create: `docs/quotes.md`, `docs/quotes.json`, `docs/v1-tickets/runs/TQ0-report.md` (≤15 lines). Nothing else. No commits.
