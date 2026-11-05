# TA0 — research: how aristotle-server runs a stock analysis in Hermes (read-only)

route: deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Decisions: `docs/v2-stocks/spec.md` Q22, Q87, Q96-Q100, Q17 (analysis only).
Hermes source (read-only): `/home/justcallmesu/.hermes/hermes-agent`. Our Hermes client: `server/internal/hermes/**`, `docs/protocol.md` §1-§7,
§11-§12 (how the relay gets a ticket and talks `/api/ws`). VERIFIED by PM: profile `aristotle-stocks` does NOT exist yet (`~/.hermes/profiles/`).

Answer with file:line evidence; do NOT run Hermes, do not create profiles or sessions, do not touch the running server or port 8760.
1. Can aristotle-server (same user, control-socket ticket) start a NEW session in a given profile, submit one prompt, wait for the final
   assistant message and read its text, with no phone attached? Exact methods/params/events (session.create/profile key, prompt.submit,
   completion event, error/interrupted events), and a timeout/abort path (`session.interrupt`).
2. Can that session run with **no tools** (no web search, no terminal, no file access) — per-session toolsets, a profile config, or a
   prompt-only mode? Q97 forbids web search; tools are also a safety risk for an automated run. If only a profile config can do it, give the
   exact config the user must write (the server never writes Hermes files) and the `hermes` CLI command to create the profile (cite the CLI).
3. Model choice: which model the profile uses by default, how to pin one per session if possible, and the cost against Claude plan limits.
4. Do these sessions show up in the Desktop's session list (for that profile)? Can they be marked/titled/deleted (`session.delete`) by the
   server afterwards? Recommend: keep or delete.
5. Approvals/clarify: what happens if the model asks for approval/clarify in an unattended session (with no tools: can it still?).
6. Concurrency: two analyses at once; Hermes busy with the user's own session in another profile — any interference?
7. Propose: (a) the prompt template (system + user) feeding the Q97 numbers and news and demanding a strict JSON verdict per Q98 (schema),
   with a repair/validation rule when the JSON is invalid; (b) the server contract `POST /v1/stocks/analyze/{code}` (async: start returns a job id;
   `GET .../analysis/{code}` returns running/done/failed + the kept last result per Q99) in the style of protocol §20; (c) the indicator set and
   formulas the PC computes (MA20/50, RSI14 Wilder, volume vs 20-day average, support/resistance method, 52-week position, intraday VWAP if
   bars allow), using only Yahoo bars TS1 already fetches; (d) a sanity check the server applies to the verdict (entry/target/stop within
   today's ARA/ARB and on the tick grid; target > entry > stop for Buy; reject or flag otherwise).
Write `docs/v2-stocks/runs/TA0/report.md` (≤ 100 lines): evidence, recommendations, the user's setup steps, open risks. No code, no commits.
