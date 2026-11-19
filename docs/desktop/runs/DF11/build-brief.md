# DF11 brief — tray tells the user when the window cannot open; tray `--version`

Repo `/home/justcallmesu/project/personal/Aristotle` (read `PROJECT.md`). Spec `docs/desktop/spec.md` D18, P11, P14, P24 (4);
`docs/desktop/design.md` §5/§6 (notifications). Found live: with no display variables the window app panicked
("Failed to initialize GTK") and the user saw nothing after clicking the tray (`desktop/tray/src/launcher.rs`).

1. When a window-app child launched by the tray exits non-zero (or by a signal) within 5 s of launch, the tray sends one
   notification: title "The window could not open" / "Jendela tidak bisa dibuka", body "aristotle-desktop stopped at start. Run it
   from a terminal to see why." / "aristotle-desktop berhenti saat mulai. Jalankan dari terminal untuk melihat penyebabnya." (new
   Rust keys `WindowFailedTitle/Body`; add them to `docs/desktop/design.md` §5 as Rust rows — allowed in this ticket only for these
   two rows). No child output in the text (P11). A child that runs longer than 5 s, or exits 0 (single-instance hand-off to a running
   window), is silent. The tray never blocks on the child (reap from a thread or on the next poll tick; no zombies).
2. `aristotle-tray --version` prints `aristotle-tray <v>` (`v` = core `desktop_version()`, else `0.0.0-dev`) and exits 0, before any
   D-Bus/tray setup.
Tests: fake desktop binary that exits 1 at once -> one notice; exits 0 -> none; sleeps 6 s -> none; `--version` output. RED first.

Files you may edit: `desktop/tray/**`, `desktop/core/src/strings.rs` (+ its tests), `docs/desktop/design.md` (the two rows only),
`docs/desktop/gates.sh` (floors only). Another worker edits `scripts/**`, `server/**`, `desktop/app/**`, `desktop/packaging/**` at
the same time: not yours.
Verify: `timeout 1800 bash docs/desktop/gates.sh docs/desktop/runs/DF11/self-gate` (a failure caused by the other worker's files:
report it as not yours). Hard rules: never git write commands; no installs/new deps; never launch the real tray/app; never run
aristotle-server against the real service; no skill/memory edits; kill every process you start.
Report: `docs/desktop/runs/DF11/build-report.md`.
