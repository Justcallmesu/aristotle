# T01b build report

Edited only `docs/protocol.md`, `docs/spikes/v0-attach.md` (+ this file). No git changes, Hermes not contacted
(source + committed frames only). Paths: `H/` = `~/.hermes/hermes-agent/`, `F/` = `docs/spikes/frames/`.

| Finding | Status | Evidence |
|---|---|---|
| M1 `session.info` two shapes | FIXED | New protocol.md §2.1. A = 7-key state update (`H/gateway/session_authority.py:442-457`; all 47 captured A frames have exactly that set, e.g. `F/c-client1.jsonl:15,17,29`). B = full inventory, 23 keys, with `system_prompt` + `credential_hint` (`H/gateway/session_local_inventory.py:225-270`; `F/c-client1.jsonl:26`). Also B′ (policy repaint, source only) and the RPC/snapshot form (34 keys, `F/f-busy-client2.jsonl:85`). Merge-present-keys rule stated. Only B, B′ and the snapshot carry `credential_hint`; only B and the snapshot carry `system_prompt`. |
| m2 frequency | FIXED | Per turn per viewer: 3 A + 1 B (`F/d-client1.jsonl:13,15,43,45` / `:59,61,110,112`). Full shape once per turn, plus once per resume snapshot and per explicit call. Fixed in protocol.md §2/§2.1 and v0-attach.md surprise 3 + risks. |
| m1 `credential_hint` committed | ALREADY FIXED in frames | All frames hold the literal `"[REDACTED]"` (16 events, 1 result, 8 snapshots); no `sk-` text. Real Hermes value is `mask_secret(api_key)` (`H/gateway/session_local.py:511-514`), so it is added to the relay redaction list. |
| n3 ticket params | FIXED | protocol.md §1.1: required `profile_id, instance_id, purpose`; optional `scope, subject, restart_wait` (must be `"refuse"`); `protocol` must be 1; full error table (`H/gateway/control_socket.py:369-397`, `:352-353`; `H/gateway/runtime_bootstrap.py:42-44,48-49,61-74`). `identify` ignores params (`control_socket.py:224,347-351`). v0-attach.md:56-57 ASSUMED now cites `session_pending_controls.py:38,51`. |
| n2 403 cite | FIXED | Origin header → `H/gateway/run_api.py:429-441`; unredeemable ticket → `:450-460` (falls through to the web app's gate, which returns the 403). `:442-447` is the `4403` close for other checks. The gate's own body text is marked ASSUMED. |
| n1 skill/tool names in frames | FIXED (doc only) | v0-attach.md says `info.skills`/`info.tools` names are left in on purpose (private repo, names only). Confirmed still present (`F/c-client1.jsonl:7`). Frames not rewritten. |
| Relay redaction section | ADDED | protocol.md §10: table of every path (event payload, `session.info` result, create/resume `result.info`) for `system_prompt` → `system_prompt_chars`, `credential_hint` → removed. Replayed `events[]` is marked ASSUMED, since the only `events.since` capture is empty (`F/f-busy-client2.jsonl:83`). |

Sections changed: protocol.md §1.1, §1.2, §2 (intro line), new §2.1, §4 `session.info` row, new §10.
v0-attach.md: frames paragraph, Q1 pointer, Q3 ASSUMED cite, Surprise 3, Risks (relay bullet).

Findings WRONG: none.
Open items for the PM:
- `docs/spec.md` Q34 rationale says "~21 KB twice per turn"; frames show once per turn. I did not edit spec.
- m3 (spec Q7d no-allowlist vs `config.set`/`shell.exec`/`session.mutate delete`) was not in this brief and is untouched.
