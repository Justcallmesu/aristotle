# T14s build report (D34, server only)

- `relay.go` `register`: before the cap check, every open socket of the same device on a *different* listener (and not already closing) is closed `4409` ("superseded by a newer connection"), removed from the device's set (so the cap never counts it), and logged `relay superseded` (device_id, old_listener, new_listener). Same-listener sockets are untouched.
- `limits.go`: `CodeSuperseded = 4409`.
- `docs/protocol.md` §12: 4409 row added to the close-code table.
- Tests (`relay_test.go`, second "lan" router sharing the hub): lan x2 then tailscale -> both lan get 4409 + log line, same-listener pair stays, other device unaffected; 4 lan + 1 tailscale -> admitted (no 4430), 4 superseded. `go test -race ./internal/relay` ok.
- Gate: `GATE_ONLY=server bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/T14s` exit 0 (257 Go tests pass).

## App note
Current app (`LinkEnds.ofClose`) maps any unknown code, including 4409, to `RoundEnd.Lost` -> reconnect with backoff. In the Wi-Fi->Tailscale case the old socket is already abandoned, so 4409 never reaches it. It matters only if both listeners are reachable and the app's still-live socket gets superseded: it would reconnect and could supersede the other one (flip-flop). Suggest a D-row: app treats 4409 as "do not reconnect this socket". I did not edit tickets.md (outside my file scope).
