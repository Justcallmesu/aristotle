# T15b build report
Gate: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/T15b` exits 0 (PASS android). The `ComposableNaming` lint warning is gone.
Not committed, not installed on the phone.

- **M1**: `SlashCatalog.of` now keeps the `desktop` verdict for every name and alias in `commands` (`withheld`). New `blockOf` checks the menu row, then `withheld`, then the hand-kept list. `/busy` is on the hand-kept list, so it is never sent. `desktop:"hidden"` maps to PhoneControl, anything else to DesktopApp.
  With no catalog yet, `run()` shows a spinner row, joins the read in flight (or starts one) and then decides. A catalog that cannot be read sends nothing and shows the error.
- **M2**: the dialog is dropped when `chat.model/provider` differs from what it was asked at (`ModelLogic.dropStale`, called from `change`). "Switch anyway" needs `ARM_MS` in the ViewModel (`confirmArmed`) and is disabled in the UI for that time.
- **M3**: the submission id is made before `slash.exec` and kept per command text. It is reused on a repeated send, and the dismissed-NoAnswer `keptIds` id is reused too. `submitDirective` takes the id. Retry already reused the item id.
- **NITs**: a confirmation without a token is now `PickAnswer.NoToken`, shown as a snackbar (string added in en and in). A blocked or subcommand-blocked command keeps the draft; the draft is cleared only when the command is sent.
- **TQ1**: tile colour is `surfaceContainerHigh`; radius is `shapes.extraLarge`; reduced motion uses the shared `reducedMotion()`; the fade uses the new `ui/theme/Motion.kt` `EmphasizedDecelerate` (design §4 curve, `ChatItems` now shares it); `QuoteGateHost` renamed `quoteGateVisible`.

Tests (`ChatViewModelControlsTest`, 24 pass; 12 added/changed):
Instead of `git`, I re-broke each fix in the new code (scratch copies in `$TMPDIR/new`, restored afterwards). These 9 tests failed against the old behaviour:
`aTypedCommandTheDesktopRefusesIsNotSentAndKeepsTheDraft`, `busyIsNeverSentEvenWhenTheCatalogDoesNotKnowIt`, `aCommandSentBeforeTheCatalogLoadedWaitsForItAndIsThenChecked`, `aCatalogThatCannotBeReadSendsNothingAndKeepsTheDraft`, `aSkillSubmissionIdExistsBeforeTheFirstCallAndIsReusedForTheRestoredText`, `aSkillRetryAfterAFailedSlashExecKeepsItsId`, `aConfirmationWithoutATokenIsNotAppliedAndSaysSo`, `theSwitchAnywayDialogClosesWhenTheModelChangesElsewhere`, `switchAnywayIgnoresATapBeforeItsArmingTime`.
`theSwitchAnywayDialogStaysWhenTheModelDidNotChange` guards against over-closing and passes both ways. The existing guarded-target test now waits out the arm (`now += 700`).

Open: the TQ1 NITs (colour, radius, easing, shared helper, lint) are Compose-only and have no unit test; they are covered by the compile and lint gate. The design.md §2/§7 and Q44 doc lines from the review are still PM decisions, not edited here.
