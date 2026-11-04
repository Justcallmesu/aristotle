# TV1a build brief — versioning, server side (Q63)

route: std. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`, `docs/spec.md` Q63, `docs/protocol.md` §11.2/§12/§14,
then `server/cmd/aristotle-server`, `server/internal/{app,httpapi}`, `docs/v1-tickets/gates.sh`.

## Do
1. **App version**: `server/internal/version` package with `Version` (semver, default `0.0.0-dev`) and `Commit` set via
   `-ldflags "-X …"`; a `Makefile` or `scripts/build-server.sh` that builds with `Version` from the git tag (`git describe --tags`,
   fallback `0.0.0-dev+<short sha>`). `aristotle-server version` prints `aristotle-server <version> (<commit>) protocol <N>`.
2. **Protocol version**: an integer `ProtocolVersion` (start at 1) for the phone-facing API (§11–§14) plus `MinPhoneProtocol`. Bump rules
   documented in protocol §0 (new): bump on any breaking change to a phone-facing shape; additive changes don't bump.
3. Expose: `/healthz` gains `"version"` (already has one? keep it the server semver) and `"protocol": N`; `/v1/me` gains
   `"server": {"version": "...", "protocol": N, "min_phone_protocol": M}`. Document in §11.2.
4. Tests: version output, ldflags default, `/healthz` and `/v1/me` shapes.
5. `CHANGELOG.md` is NOT in this ticket (TV2).

## Hard rules
Files: `server/**`, `scripts/build-server.sh` (or a server Makefile), `docs/protocol.md` §0/§11.2, `docs/v1-tickets/runs/TV1a/build-report.md`.
android/ has another worker (TS1): do not touch. No commits; never git checkout/restore/stash/reset. Never touch port 8760.

## Verify
`GATE_ONLY=server bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TV1a` exits 0.

## Report
`docs/v1-tickets/runs/TV1a/build-report.md`, ≤15 lines.
