# T14s build brief — D34: a device's new relay socket retires its older ones (server only)

route: std. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`, `docs/protocol.md` §11–§12 (relay limits: 4 sockets
per device, close codes), board row D34 in `docs/v1-tickets/tickets.md`, then `server/internal/relay/{relay,session}.go` and tests.

## Problem (seen on the Poco, server log 2026-11-03 15:46 and 22:04)
When the phone changes network (Wi-Fi drops -> Tailscale), its old socket cannot send a close: that network is gone. The server keeps it
until the keepalive fails (~42 s, close 1011). Rapid switches can hit the 4-per-device cap (4430) and refuse the phone.

## Requirement
- When a device opens a new relay socket on a **different listener** (lan vs tailscale) than an existing open socket of the same device,
  the server closes the older socket(s) of that device on the other listener with a new close code `4409 superseded` (reason
  "superseded by a newer connection") and logs `relay superseded` (device_id, old listener, new listener). Same-listener sockets are
  untouched (the app may legitimately hold several on one network in the future).
- The new socket is admitted before the cap check counts the superseded ones (so a switch never sees 4430).
- Document 4409 in `docs/protocol.md` §12 close-code table; the phone treats 4409 as "do not reconnect this socket" (no app change needed
  now: the app already closed/abandoned it — note in the report whether the current app would reconnect on 4409; if it would, add a D-row).

## Tests
Two listeners in the existing relay test harness: device opens on lan, then tailscale -> lan socket gets 4409; same listener twice -> both
stay; cap: 4 lan sockets then 1 tailscale -> admitted and the 4 lan ones superseded; another device unaffected. `-race`.

## Hard rules
Files: `server/internal/relay/**`, `docs/protocol.md` §12 close codes, `docs/v1-tickets/runs/T14s/build-report.md`. android/ has
uncommitted T12 work: do not touch. No commits; never git checkout/restore/stash/reset. Do not touch the demo server on 8760.

## Verify
`GATE_ONLY=server bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/T14s` exits 0.

## Report
`docs/v1-tickets/runs/T14s/build-report.md`, ≤20 lines.
