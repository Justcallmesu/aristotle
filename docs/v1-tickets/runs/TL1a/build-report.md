# TL1a build report — LAN discovery, server side (Q65)

**Gate:** `GATE_ONLY=server bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TL1a` exits 0 (gofmt, vet, 352 tests pass, 0 fail). Also `-race` on discovery/config/listen/app.

**Library: `github.com/hashicorp/mdns` v1.0.7 (MIT).** It takes one `Iface` per server and uses `ListenMulticastUDP`, so it binds per interface with address reuse (avahi can coexist). I tried `grandcat/zeroconf` first and dropped it: v1.0.0 does a plain `ListenUDP :5353` with no reuse, which collides with avahi/systemd-resolved. No hand-written responder needed.

**Built**
- `server/internal/discovery` (new): `Announcer.Sync(endpoints)` keeps one announcement per interface that has a LAN listener (Tailscale, loopback, empty iface, non-IPv4 skipped). It restarts on an address change, stops when the listener goes, and `Close` stops all. Logs `discovery started/stopped iface=…`. A failed start is retried on the next scan.
- TXT is `v=1`, `fp=<16 hex, lowercase>`, `proto=<ProtocolVersion>`. Instance is `Aristotle on <short hostname>`, host `<short>.local.`, port is the LAN listener port.
- `listen.Manager.OnChange(active)` is called after each reconcile (outside the lock) and with `nil` after close. `app/serve.go` wires it to the announcer.
- Config: `discovery.enabled` (default true, exact-case keys, nested unknown keys and null refused).
- `docs/protocol.md` §15 added: service type, TXT keys, hint-only/pinned-fingerprint trust, lifecycle, config.

**Tests (all unit-level with a fake responder; no real interfaces, no root)**
- discovery: LAN-only filter, TXT and instance, fingerprint hint, start/stop/restart on change, Close, disabled, retry after failure.
- config: defaults and off, bad keys.
- listen: OnChange sequence.
- app: end-to-end with fake scan (wlan0 + tailscale0 + lo → only wlan0, TXT matches the cert, stopped at shutdown); off by config.

**Caveats**
- The real `MDNSStart` (hashicorp/mdns on a live NIC) is not exercised by any test, to avoid touching real interfaces. Run a manual `avahi-browse -r _aristotle._tcp` on a LAN before relying on it.
- `app.newServer` leaves `discovery.Start` nil. Only `Serve` sets `MDNSStart`, so tests never announce.
- Process slip: I ran `git checkout -- server/go.mod server/go.sum` once to undo a rejected `zeroconf` `go get`. Those files had no prior edits, so nothing was lost; I did not use it otherwise. go.mod/go.sum now carry hashicorp/mdns and its deps, with `go mod tidy` applied.
- Port 8760 and `android/**` untouched; no commits.
