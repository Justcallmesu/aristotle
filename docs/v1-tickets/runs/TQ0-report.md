# TQ0 report: Aristotle loading-screen quotes

- Delivered `docs/quotes.md` and `docs/quotes.json`: 24 quotes, English ≤140 chars (max 134).
- Works: NE (12), Metaphysics (5), Poetics (3), Rhetoric (2), Politics (2).
- Verified by script: Greek taken from Perseus TEI (Bekker page from its milestones) and found on the linked Perseus Hopper page; English is an exact substring of the MIT text of Ross/Jowett/Butcher/Roberts.
- Edits to English: first-letter capitalisation, clause trimming, leading `…`. No paraphrase.
- Bekker is page+column only (no line numbers). Politics chapter I.2 follows Jowett/MIT; Perseus has no chapters there.
- Translation years are standard first-publication dates; MIT pages name the translator but not the edition.
- Left out: Politics VIII leisure (no checkable Jowett+Greek text), NE IX.9 "another self" (objectors' view). PA, De Anima, Parts of Animals not covered.
- Misattribution table in `quotes.md` (Durant "we are what we repeatedly do", "whole > sum of parts", "entertain a thought", "nature abhors a vacuum", etc.).
- `url_en` points to MIT `.mb.txt` since MIT's per-book HTML truncates some works.
- Nothing touched outside the three allowed files. No commits.
