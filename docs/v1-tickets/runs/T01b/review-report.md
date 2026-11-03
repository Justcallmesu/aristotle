# T01b review: docs/protocol.md, docs/spikes/v0-attach.md

Fresh reviewer. Read-only; Hermes not contacted. `H/` = `~/.hermes/hermes-agent/`, `F/` = `docs/spikes/frames/`.
Frames parsed with a script (each line is `{client,dir,frame}`).

## Spot-checks (11 checked; 10 CONFIRMED, 1 PARTIAL)
| # | Claim | Verdict | Evidence |
|---|---|---|---|
| 1 | Shape A = exactly 7 keys, from `_publish_pending` | CONFIRMED | `H/gateway/session_authority.py:442-457`. Script: 47 of 47 shape-A frames have exactly that set. |
| 2 | Shape B = 23 keys, carries `system_prompt` + `credential_hint` | CONFIRMED | `F/c-client1.jsonl:26` and `d-client1:43` have 23 keys. `H/gateway/session_local_inventory.py:225-270` publishes `{**agent_inventory, **local_session_info}`. All 16 B frames have `credential_hint`. |
| 3 | RPC/snapshot form = 34 keys; lazy resume has neither value | CONFIRMED | `f-busy-client2:85` = 34 keys, `c-send:8` = 34, `c-client1:7` = 32 keys with no `system_prompt` and `credential_hint: null`. `session_controls.py:554-559` rejects extra params. |
| 4 | B′ policy repaint publishes `local_session_info` alone | CONFIRMED (source only) | `H/gateway/session_mutations.py:353-359` |
| 5 | Frequency: 3 A + 1 B per turn per viewer | CONFIRMED | `d-client1` session.info seq: 25A, 27A, 50B, 52A, 53A, 55A, 96B, 98A. Full shape is once per turn. |
| 6 | `session-ticket` required/optional params and errors | CONFIRMED | `H/gateway/control_socket.py:369-397`. All 7 error messages match source. Error text is `PermissionError: <msg>` (`:352-353`). |
| 7 | `identify` ignores params | CONFIRMED | `control_socket.py:224`; handler called without params when its signature has none (`:343-349`). |
| 8 | Unredeemable ticket falls through to the web app (403 cite fixed) | CONFIRMED | `H/gateway/run_api.py:450-460`. Origin present → `:429-441` also goes to the app. |
| 9 | Subprotocol "missing/ill-formed" → `4403` (protocol.md §1.2) | PARTIAL | `web_server_boundary.py:166-171` returns `"none"` for a missing ticket subprotocol, and `run_api.py:431` sends that to the app. Only `"invalid"` (no `hermes-gateway-v1`, 2+ tickets, empty ticket) reaches `4403` (`:442-447`). |
| 10 | Redaction paths, `credential_hint` | CONFIRMED | `H/gateway/session_local.py:511-514`: `mask_secret` gives `head4...tail4`. All 16 event frames and 8 snapshot frames hold the literal `[REDACTED]`. No real key in frames. |
| 11 | `events.since` capture is empty | CONFIRMED | `f-busy-client2:83` has `events: []`, `truncated`, `snapshot_required`. The replayed-events redaction claim is correctly marked ASSUMED. |

## Previous review findings
- M1 (session.info shapes): FIXED. Both shapes are documented with keys, cites and a merge-present-keys rule. The relay-stripped fields are noted per shape.
- m1 (`credential_hint` committed): FIXED. Frames hold `[REDACTED]` and the field is in the relay list (§10).
- m2 (frequency): FIXED. Wording is corrected in protocol.md §2.1 and v0-attach.md surprise 3 and risks.
- m3 (spec Q7d vs `config.set`/`shell.exec`/`delete`): NOT FIXED. This was not in the T01b brief and was disclosed in the build report. It is still open for the PM.
- n1 (skill/tool names): FIXED. v0-attach.md states they are left in on purpose.
- n2 (403 cite): FIXED, except the PARTIAL in #9.
- n3 (ticket params): FIXED. v0-attach.md now cites `session_pending_controls.py:38,51`.

## Findings
- **MINOR m1** protocol.md §1.2 (the "Peer must be 127.0.0.1/::1; a bad Host, a missing/ill-formed subprotocol…" bullet): a missing subprotocol does NOT close `4403`. It goes to the app via `run_api.py:431`, same as the Origin case. Fix: say "ill-formed (no `hermes-gateway-v1`, ≠1 ticket entry, empty ticket)" for `4403`. Say "missing" falls through to the app. The doc also labels the "seen by an HTTP client as 403" part VERIFIED, but it is inference. Mark it ASSUMED.
- **MINOR m2** protocol.md §2.1 and §10 cite `c-client1.jsonl:26 (16 events)`. Line 26 is one event. The 16 events are spread over several files. Fix: "16 events across the frames, e.g. `:26`".
- **NIT n1** §10 lists `session.activate` as carrying `result.info`. I did not find a capture of it, so it is source-only. Label it ASSUMED or drop it.
- **NIT n2** §2.1 "the three A events per turn correspond to admission, run start and settle" is correctly marked ASSUMED. The Hermes comment at `session_authority.py:448-450` is the only support.
- **NIT n3** spec Q34 still says "twice per turn". The build report flagged it. The PM should edit the spec, because T04/T06 will read it.

No BLOCKER or proven MAJOR. The docs are usable for T04/T06/T11.

VERDICT: PASS-WITH-FINDINGS
