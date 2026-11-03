# T06 review — `/v1/ws` relay + D16/D17

Fresh reviewer. Read all inputs in the brief plus the Hermes source (§10 sinks, `desktop_local`). `go test -race -count=1 ./...` ok, `gofmt -l .`
empty, `go vet` 0. Probes ran as throwaway tests in a scratch copy, since deleted. No git changes, no real Hermes, no port-18765 server.
## Findings
1. **MAJOR (unproven at real scale; mechanism proven at reduced scale)**: `relay/session.go:156-172` with `relay.go:24-25`. The keepalive closes a healthy
   phone that is slow to drain Hermes→phone data. coder/websocket writes a whole message as one frame while holding the write lock, and the kernel
   send buffer sits in front of the ping too, so `Ping` fails whenever more than PongTimeout of queued data is ahead of it. Probe: the phone reads
   continuously at 256 KiB/s while 3 MiB is queued. With PongTimeout 1 s it got 2/3 frames, then a cut mid-frame (no close frame, server logs 1011);
   with 60 s all 3 arrived. Real case: a session.resume of a few MB on a ~2 Mbit/s link takes >15 s, is cut, the phone resumes again, so it loops.
   Fix: fragment large frames (`pc.Writer`, 32–64 KiB writes) so ping/close interleave, and judge liveness by progress, not wall clock (two missed
   pongs, or no ping during a write that has a minimum-throughput deadline). Add a throttled-conn test.
2. **MINOR (proven)**: revoking a device whose phone is not draining gives no 4401. Probe: the relay unregistered at 2.008 s (`closeGrace`) and the
   phone saw `unexpected EOF` mid-frame; §12 promises "4401 within 1 s". The security property holds: after `stop` no new frame goes either way
   (`session.go:108,135`); only the frame already being written keeps going until the 2 s cut. Fix: #1's fragmented writes let the 4401 interleave;
   otherwise document "or the socket is cut within 2 s".
3. **MINOR (proven)**: the audit line can log secrets. `relay/limits.go:161-184`: `plainName` accepts `[A-Za-z0-9._-]{1,64}`, so a phone frame
   whose `method` is its own 43-char token (or an `sk-…` key) is logged verbatim (probe: `token in log: true`). This breaks the PROJECT.md rule
   "logs never contain tokens". Fix: count only the known Hermes method names (or `^[a-z]+(\.[a-z_]+)*$`); anything else counts as `other`.
   Add a token-shaped method to `TestRelayAuditLinesHaveNoFrameContent`.
4. **MINOR + recommendation (`desktop_local`)**: the relay forwards `client.capabilities {"desktop_local":true}` unchanged (probe). The relay's
   socket holds a native ticket, so Hermes accepts the claim (`process_identity.py:425-431`). It then runs `begin_local_desktop_boot` (free-tier
   bootstrap, onboarding), reports client surface `desktop` (`anon_challenge.py:124`) and marks the phone's chats `desktop_local`
   (`session_local.py:308`; free-tier gating `anon_auth.py:206`): no privilege beyond Q7d, but misattribution and first-boot side effects.
   **Recommend refusing, not stripping**: close `1008 desktop_local_refused` when the decoded frame (`methodOf` already decodes) has
   `method=="client.capabilities"` and `params.desktop_local==true`. Not a byte check (Python decodes `desktop\u005flocal`). Also refuse frames Go
   cannot decode (Python accepts `NaN`). Narrows Q7d, so it needs a spec line (PM decision).
5. **NIT (doc)**: §12 "no relay size limit" Hermes→phone, but the read limit is 64 MiB (`hermes.go:56`); larger ends `4502 … connection lost`.
6. **NIT (doc)**: §12 says "no Origin header needed". An `Origin` whose host differs from `Host` gets `403` (library default; probe confirmed).
   Say "if sent, must match Host"; this check is also the cross-site defence.
7. **NIT**: the fake move also added `RestBodies/RestLengths/ReadLimit`: additive, defaults unchanged. Acceptable.
## Verified OK
- **Auth:** only the `Authorization` header is read. Probes: `?token=`, `?access_token=`, `?bearer=` and `Basic <token>` all get 401. An
  unauthenticated upgrade never mints a Hermes ticket. **Cross-site WebSocket hijacking:** not possible. A browser cannot set `Authorization` on a
  WebSocket, there are no cookies, and a cross-origin upgrade gets 403 even with a valid Bearer.
- **Redaction:** checked against Hermes source. Every `_session_info` sink is the event `params.payload`, `result.info` (create/resume/branch/
  compress/personality/tools) or the `session.info` result; the §10 paths cover them all, plus `events[]`. Frames without the field names pass byte
  for byte; a name in text or at an unlisted path is untouched; a frame that names a field but is invalid JSON or a non-object is dropped;
  re-encoding keeps `<>&`.
- **Concurrency:** cap check and `wg.Add` run under `h.mu`, and `closed` is set before `Wait`. The revoke hook runs outside the store lock and
  does not block; the `Has` re-check after register closes the auth→register window. Pumps exit only when their read fails, bounded by `kill`;
  the leak test is a real assertion. Shutdown sends 1001; each session is bounded by `closeGrace` (2 s), the hub by 5 s, and a dial in progress
  is cancelled through `stopCtx`. A stalled phone back-pressures Hermes over TCP, with no unbounded buffering in the relay (one frame ≤64 MiB plus
  redaction copies). D4: the request line is logged with status 101 at upgrade time.
- **D16/D17:** all 8 items are present. `TestListenConfigPinsSocket` and `TestBindTCPPinsToEndpointInterface` PASS here (not skipped). Also
  present: empty-interface refusal, shared reject log, POST body through `Do`, `Content-Length` dropped, import grouping, the `rest.go` doc line,
  the §11 Linux note.

No blocker. #1 should be fixed before the app relies on `session.resume` over mobile data; #3 is a one-line hardening; #4 needs a PM decision.
VERDICT: PASS-WITH-FINDINGS
