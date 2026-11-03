# T15 build report — model + reasoning switch, slash commands

Gate: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/T15` → exit 0 (`PASS android`; 427 unit tests, 34 new). Mutation: dropping adopted fences in `ModelLogic.fences` fails `ChatViewModelControlsTest`.

| UI action | Method (phone) | Source |
|---|---|---|
| Open sheet (tap top-bar title, shows model · effort ▾) | `model.options {session_id, explicit_only:true}` | Desktop `lib/model-options.ts:146-185`, `components/model-picker.tsx:95-99`; owner `gateway/session_config.py:213-236` |
| Pick model (grouped by provider, Hermes order, locked rows disabled) | `session.mutate {…, expected_revision, expected_generation, operation:"model", payload:{model, provider}}` | `use-model-controls.ts:359-365` → `api/canonical-protocol.ts:82-92,329-349,471-474`; `hermes_state_mutations.py:13-17`, `session_mutations.py:149-154` |
| Pick reasoning (chips; hidden if `capabilities[model].reasoning==false`; Off only if `can_disable_reasoning!=false`) | `session.mutate … operation:"reasoning", payload:{effort}` | `reasoning-menu-panel.tsx:24-58`, `model-edit-submenu.tsx:131-134,204-216`, `reasoning-step.ts:61-80`, `canonical-protocol.ts:222-234`; levels `hermes_constants.py:1073` |
| Pick while running | same; answer `status:"deferred"` → "X applies from your next message" | `session_mutations.py:133-142`, `session_deferred_pick.py:59-61`, `use-model-controls.ts:367-384` |
| Guarded target → "Switch anyway" | same + `payload.confirm` token | `session_mutation_model.py:339-378`, `use-model-controls.ts:389-448` |
| Conflict | no resend; adopt `error.data {revision, execution_generation}`, re-read `model.options`, snackbar "Changed elsewhere — try again" | `session_controls.py:22-35,844-850`. Desktop retries once (`canonical-protocol.ts:300-309`); phone follows the brief (never) |
| Fences | revision from shape-A `session.info`/snapshot + new `session.updated.revision` (forward only); generation from events | `canonical-protocol.ts:582-613`, `session_mutations.py:330-334` |
| Type `/` | `commands.catalog {session_id}` once; hides `desktop:"hidden"`/unavailable/empty `desktop_subcommands` | `use-slash-completions.ts:100-111`, `desktop-slash-commands.ts:528-549,800-820,949-997`; `command_discovery.py:137-149,206-250` |
| Send a `/command` (idle or busy; Queue/Steer off for it) | `slash.exec {session_id, command}` (no slash); `exec` → output row; `skill`/`send` → `prompt.submit` of `message` with own `submission_id`, bubble = `display` | `slash.ts:403-547`; `session_commands.py:148-155,418-529` |
| Desktop-only / phone-control / not-yet commands | shown disabled with reason, not sent (`PhoneSlash`, mirrors `DESKTOP_COMMAND_SPECS` `desktop-slash-commands.ts:202-391`); anything else goes to Hermes, which refuses by name (`session_commands.py:118-145,456-458`) | |

Files: new `data/chat/{ControlFrames,ModelCatalog,SlashCatalog}.kt`, `ui/chat/{ModelControl,ModelSheet,SlashControl,SlashMenuPanel}.kt`; edited `ChatViewModel`, `ChatScreen`, `Composer`, `ChatComposer`, `ComposerState`, `Outbox` (`wire`), `ChatModel` (revision/provider/effort), `ChatReducer` (`session.updated`), strings EN/ID; tests `ControlFramesTest`, `ModelCatalogTest`, `SlashCatalogTest`, `ChatViewModelControlsTest`; `docs/protocol.md` §4 (model/reasoning mutate shapes) and §5 (`slash.exec`, `commands.catalog` rows).

Open / not done: the `PhoneSlash` table is a hand-kept mirror of the Desktop's local actions (a new Desktop action shows as runnable, then Hermes refuses it with its own message); a skill's queued/started row shows Hermes' expanded `message`, not `display`; a deferred pick that turns guarded at the turn boundary (`session.updated status:confirmation_required`, `session_deferred_pick.py:160-175`) is not asked on the phone; `/reasoning show|hide` words not offered; the local `llamacpp` provider is always hidden (Desktop shows it only with `--local`). Nothing exercised against real Hermes.

TQ1 review (no TQ1 file touched by T15 except strings, which are fine; not fixed, for the PM):
1. `QuoteLoadingScreen.kt` icon tile `RoundedCornerShape(22.dp)`: not a design §3 radius token.
2. Icon tile colour `colorResource(R.color.ic_launcher_background)` in a composable (design §1/§7: colours from `colorScheme`); a parchment tile in dark mode.
3. Quote in Literata (`Type.kt` `quote`/`quoteGreek`): design §2 keeps Literata for wordmark/titles; Q45 is silent — needs a design line.
4. Centered stack with icon above (design §7 "no centered hero stacks / no icon above") is Q46's call; design.md should record the exception.
5. Q44 "reconnecting state": the gate covers Idle/Connecting only; `Offline(trying)` before any content shows the offline page, not a quote.
6. Reduced motion read inline instead of the shared `reducedMotion()` (`ui/components/RunningSpinner.kt`); fade easing (0,0,0.2,1) is not a design §4 curve.
7. Lint: `AristotleApp.kt:76` `QuoteGateHost` returns a value, so it should be lowercase (`ComposableNaming`).
