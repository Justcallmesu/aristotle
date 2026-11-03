# T06b review — relay review fixes + desktop_local refusal (Q43)

Fresh reviewer. `go test -race -count=3 ./internal/relay/` → ok (33 s). Probes ran as throwaway tests in a scratch copy (fake Hermes
given a `Trickle` option), since deleted. Hermes source read at `~/.hermes/hermes-agent` (read-only); no real Hermes, no git changes.
## T06 findings
- **#1 slow phone cut — FIXED.** A 32 KiB `pc.Writer` loop (`liveness.go:26-38`) gives one message: a text opcode, then continuation frames,
  then the fin frame from `w.Close`. Control frames may interleave (RFC 6455 allows it). Only pumpHermes writes data, so no two writers.
  `TestRelaySlowPhoneGetsEveryLargeFrame` passes. But see new finding A.
- **#2 revoke on a stalled phone — FIXED.** Probe over TLS with 128 KiB buffers and two 2.5 MiB echoes (8 runs): unregistered at 505-508 ms and the
  phone sees EOF. Under -race: no race, no double-close fault. `raw.Close` and the library's own close of `tls.Conn` both close the same
  TCP fd, and the second close only returns an ignored error. `s.raw` is written in Hijack, before `register` (h.mu), so kill and Shutdown see it.
- **#3 secrets in method log — FIXED.** `knownMethods` (`phoneframe.go:10-21`) is the allowlist. Checked against §1-§9: every method the app can
  send is listed. `session.title` appears only as an event. A token or `sk-…` sent as a method is logged as `other` (tested).
- **#4 desktop_local — DONE (REFUSE).** Hermes sets it only in `client.capabilities` (`methods_voice.py:535`, key presence; `ws.py:683-688`
  calls `raw.strip()` then `json.loads`; there are no batches, `_normalize_request` requires a dict). Corpus probe of 31 frames, Go
  `inspectPhone` vs Python's view: **0 bypasses**. Covered: duplicate `method`/`params`/`desktop_local` keys, which are last-wins in both
  parsers, so they agree; `\u` escapes in all three names; a `[...]` batch; U+3000 or a BOM in front; NaN/Infinity/1e999; lone surrogates;
  nesting 1200 and 20000 deep; trailing data; non-object or null `params`; nested `desktop_local`; case variants. The only over-refusals are
  frames Hermes rejects anyway (a batch, a BOM, trailing data). Legitimate `client.capabilities {server_requests}`, `prompt.submit` with `\u00e9`,
  and srq results that quote the words all pass. Checking every frame (no prefilter) is correct: a prefilter would miss `client.c\u0061pabilities`.
- **#5, #6 docs — FIXED** (§12: 64 MiB → 4502, Origin → 403). **#7** — n/a.
## New findings
A. **MAJOR (proven at reduced scale)**: a dead phone is not detected while Hermes sends small frames. `liveness.go:20` moves `lastWrite`
   at the start of every write, and `quiet()` (`:76-78`) pings only after PingInterval with no movement in either direction. A write the
   kernel takes into a free send buffer therefore counts as progress, though the phone took nothing. Probe (ping 100 ms, pong 200 ms):
   a phone that never reads after the ack is closed 1011 within 2.3 s when Hermes is silent. When Hermes sends one ~90 B event every
   40 ms, it is **still open after 4 s**. With 128 KiB buffers and a 1 ms trickle it was closed only at 9.9 s, after 5492 frames had filled
   the buffers. At real scale, a phone lost while the agent streams tool events (more often than every 25 s) is held until the send buffer
   fills or TCP gives up (~15 min with `tcp_retries2`), not 40 s. Effects: each ghost takes 1 of the 4 per-device slots, so a phone that changes
   network a few times mid-run gets `4430`, and Hermes keeps the ghost's subscriptions. §12 `:469` "no progress for 15 s" is false here.
   Fix: count a write as progress only when it had to wait for the phone (a blocked write that then completed), not when it starts or
   lands in a free buffer. Keep pinging on the PingInterval cadence when no blocked write is in flight. Test: a non-reading phone plus a trickling Hermes.
B. **NIT**: while `forwarding` (`liveness.go:77,83`) no ping is sent at all, so a stuck Hermes that stops reading also suspends phone liveness
   (bounded by Hermes itself; local). Also NIT: `knownMethods` has `event`, which only Hermes sends; harmless.
## Verified OK
Goroutines: no new ones; `ping()` is bounded by PongTimeout or `c.closed`, and the leak test passes. Refused and post-close frames are never
forwarded (`session.go:123,140`). Reads go through `progressConn` because coder resets `brw.Reader` onto the hijacked conn (`accept.go:167-168`).
Spec Q43 line is present; §12 text matches the code. Every T06 finding is fixed and Q43 has no bypass; A is a proven regression.
VERDICT: CHANGES-REQUIRED
