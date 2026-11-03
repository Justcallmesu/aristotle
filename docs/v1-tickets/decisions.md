# Run-level decisions (append-only)

- 2026-11-03 Grilling finished; user answered Q31 = A (accept PC off, catch up at boot) and Q32 = A (private GitHub repo). PM took the absence of objections as confirmation of the architecture picture and of the Q20/Q21 interpretations.
- 2026-11-03 Board is a draft; no worker is dispatched until the user approves the ticket breakdown.
- 2026-11-03 User said "go": breakdown approved as drafted (no changes requested); dispatch T01, T02, T03 in parallel. Repo has `git init` on `master` with no commits yet; the user has not made the docs commit, so worker diffs are checked as untracked files under each ticket's own directories.
- 2026-11-03 Builds may download the Gradle wrapper distribution and Maven/Go module dependencies (normal build behaviour). No system packages, JDKs or Android SDK platforms are installed without the user's OK.
- 2026-11-03 T01 may create ONE new Hermes session titled `aristotle-spike` in the default profile and send benign prompts to it; it never posts into existing sessions. The live Desktop demo with the user happens after the worker report.
- 2026-11-03 User approved installing Android platform 36 (PM ran sdkmanager). User said "that's all" for now: no new dispatch beyond the running wave-1 reviews until he says go; the T01 Desktop demo waits for him.
- 2026-11-03 User granted the PM commit and push rights ("hereby i grant you to commit and push"). Rules in PROJECT.md Hard rules; PM SOUL.md updated to allow it only where PROJECT.md grants it.
