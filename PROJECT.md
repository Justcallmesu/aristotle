# PROJECT.md — Aristotle

Project facts for any agent working here (PM, developer, reviewer). Agents keep no project facts in
memory; they read this file first. Keep it short and current; when it is wrong, fix it here.

## What it is
A personal Android app ("Aristotle") that controls the user's Hermes Agent sessions on his laptop,
through a Go server on the laptop. Later: IDX stock analysis. Personal use, sideloaded APK.

## Repos
| Path | Role | Stack |
|---|---|---|
| `Aristotle/` (this dir) | Monorepo. Private GitHub `Justcallmesu/aristotle` (the user creates it) | — |
| `Aristotle/server/` | aristotle-server: relay to Hermes, pairing, (v2) stocks engine | Go 1.26, std lib first, SQLite (v2) |
| `Aristotle/android/` | The app | Kotlin, Jetpack Compose (Material 3), Gradle KTS |
| `Aristotle/spikes/` | Throwaway experiments (v0). Never imported by product code | Go |
| `~/hermes-mobile-remote/` | Co-worker's app (MIT). **Read-only protocol reference.** Never copy code from it (spec Q12) | Python bridge, Kotlin app |
| `~/.hermes/hermes-agent/` | Hermes source. Read-only reference for the protocol | Python, TS |

## Where the truth lives
- Spec and decisions: `docs/spec.md` (decisions table Q1..). A decision only in chat does not exist.
- Tickets, run decisions, worker runs: `docs/v1-tickets/` (`tickets.md`, `decisions.md`, `runs/NN/`).
- Spike findings: `docs/spikes/`.
- Protocol notes once known: `docs/protocol.md` (written by T01).

## Commands (gates)
- Server: `cd server && gofmt -l . && go vet ./... && go test ./...` (must print no files for gofmt, exit 0).
- Android: `cd android && ./gradlew --no-daemon lintDebug testDebugUnitTest assembleDebug`.
- Gate script: `bash docs/v1-tickets/gates.sh <run-dir>` (server + android; JDK 26 is the system default). Workers' "tests pass" is a claim until it runs.
- Install on phone: `~/Android/Sdk/platform-tools/adb install -r android/app/build/outputs/apk/debug/app-debug.apk`.

## Hard rules
- **Commits and pushes (user grant 2026-11-03):** the PM commits each ticket after its gates are GREEN (PM-run),
  the PM mutation check passed and the review has no open BLOCKER. One commit per ticket, staging only that
  ticket's paths (never `git add -A`). The PM pushes `master` to `origin` (`Justcallmesu/aristotle`, private)
  after each commit. Never force-push, amend pushed commits, rebase, stash, reset or checkout. Worker agents
  never commit or push.
- Never edit Hermes config or state (`~/.hermes/config.yaml`, `.env`, `state.db`, `auth.json`,
  profiles). Never restart Hermes or the Desktop. Talking to Hermes over its control socket and
  `/api/ws` is allowed. Spike/test prompts go only into a session created for testing, never into the
  user's existing sessions without his OK.
- Do not touch `~/hermes-mobile-remote/` or its running bridge (port 8650).
- aristotle-server never binds `0.0.0.0`/`::`. Hermes stays on loopback.
- No secrets in the repo. Device tokens are stored hashed. Logs never contain tokens or prompt text.
- Install tools or SDK packages only after the user approves.

## Code shape
Small named functions, no duplication, one responsibility per package/file. Go: std lib first;
interfaces only at seams (Hermes connector, price provider). Kotlin: UI state in ViewModels,
transport in `data/`, pure reducers with unit tests. Strings in `strings.xml` with `values-in/` for Indonesian.

## Roles
- PM: `pm` profile. Developer and reviewer workers: in-session `delegate_task` subagents, one fresh
  worker per ticket; route per task (`light` / `std` / `deep`, rules in
  `thin-orchestrator/references/delegation-routing.md`). Reviews on `deep`, fresh context, never by the author.
- Skills to load: `thin-orchestrator`, `pm-discovery`; for UI work `antislop-ui`, `frontend-ui-engineering` (Compose-adapted).
