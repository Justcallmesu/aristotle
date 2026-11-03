# T09b build brief — T09 review fixes

route: deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`, `docs/design.md`, `docs/protocol.md` §11/§12,
`docs/v1-tickets/runs/T09/review-report.md` (ALL findings), then the code it cites.

## Fix (each with a test where testable)
1. MAJOR: `/v1/addresses` refresh MERGES into the stored list (union by url; fresh entries update kind/iface; never drops an address
   because the PC does not serve it right now). Cap the stored list (e.g. 8, oldest-unused dropped first; the address the user typed is
   never dropped). Test the office/home scenario from the report.
2. Link state must update immediately when the connection ends: the `/v1/addresses` call must not block it (make it cancellable —
   OkHttp `Call.cancel()` on coroutine cancellation — or run it detached).
3. `RelaySocket`: use `cancel()` for sockets still connecting or whose network is gone; `close()` only for an open socket.
4. Link coroutine scope gets a `CoroutineExceptionHandler` / `SupervisorJob` so an unexpected error moves to Offline(reason) and the
   next retry runs — never a crash or a dead link. Test with a throwing store.
5. Detect a Wi-Fi -> Wi-Fi (or any default-network) change: on `onAvailable` of a new default network / `onLinkPropertiesChanged`
   with a different network, clear stale pools and retry at once.
6. `server/.../sock_unix_test.go:107`: replace the fixed 200 ms sleep with a deterministic condition (wait until the send buffer is
   full / write returns EAGAIN, with a timeout).
7. NITs from the report: "Tailscale may be off" only when no VPN is up; Indonesian "Was running at" wording; tabular figures for
   the offline time; no `runCatching` swallowing CancellationException; reset failure count on 4503/4505 recovery; hide stale
   "Running" spinners while not Online; banner wording per design.md.

## Hard rules
Files: `android/**`, `server/internal/relay/sock_unix_test.go`, `docs/v1-tickets/runs/T09b/build-report.md`. No commits. Do not stop the
demo server on port 8760. Poco attached: install/screenshot allowed, no settings changes.

## Verify
`cd server && go test -race -count=20 -run <the D27 test> ./internal/relay/`; full server gate;
`cd android && ./gradlew --no-daemon lintDebug testDebugUnitTest assembleDebug`.

## Report
`docs/v1-tickets/runs/T09b/build-report.md`, ≤30 lines.
