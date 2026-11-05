# TA0 report — stock analysis turn in Hermes (read-only research, 2026-11-05)

Cites: `hermes/` = `/home/justcallmesu/.hermes/hermes-agent` (v0.22.5 @717447e, same as protocol.md), `repo/` = this repo. VERIFIED = read in source (or EXERCISED in protocol.md); ASSUMED = not proven.
Hermes was not run; no profile or session was created. One offline check: `_get_platform_tools` and `get_tool_definitions` were evaluated in Python with an empty scratch `HERMES_HOME` (no gateway).

## Answer in short
Yes. Over the existing control-socket ticket path the server can create a **hidden, tool-less, rules-free** session in profile `aristotle-stocks`, submit one prompt, read `message.complete.text`, interrupt on timeout and delete the session afterwards.
Tool-less is set per session (`toolsets:["no_mcp"]`), so the server writes no Hermes file. The user still creates the profile and gives it a small config as a second layer. **Never send `toolsets: []`**: it lets in every enabled MCP server.

## 1. Unattended create → submit → final text (VERIFIED)
- Socket: `Dial` already mints a host-scoped interactive ticket and upgrades `/api/ws` (`repo/server/internal/hermes/conn.go:29-58`); a `"profile":"aristotle-stocks"` key routes each call (`hermes/gateway/session_controls.py:305-318`).
  Recommended instead: a **profile-scoped** ticket (`repo/docs/protocol.md:295-296`, EXERCISED: other profiles refused `profile_mismatch`), built from `resolveProfile` + `mintTicket` (`repo/server/internal/hermes/control.go:138`, `repo/server/internal/hermes/control.go:155`). Least privilege: a bug cannot touch the user's chats.
- `session.create` params (`hermes/tui_gateway/contracts/canonical_sessions.py:21-55`): `request_id, source, model, provider, toolsets, reasoning, max_turns, ignore_rules, hidden, run_budget, title…`.
  The handler returns `self.resume(ref, {})` (`hermes/gateway/session_controls.py:532`), so the creating socket is already subscribed, which `prompt.submit` requires (`hermes/gateway/session_controls.py:815-816`).
- `prompt.submit {session_id, text, submission_id, finite:true}` → `{admission_id, status}` (`repo/docs/protocol.md:374-383`, EXERCISED); `submission_id` is the idempotency key.
  Never send `unattended:true`: it auto-approves, and a `tool`-source session on our native ticket would qualify (`hermes/gateway/session_finite.py:27-30`, `hermes/gateway/session_finite.py:59-68`).
- Done: event `message.complete` with our `admission_id`; `payload.text` is the final reply, `status` ∈ `complete|error|interrupted` (`hermes/tui_gateway/contracts/events.py:198-203`, `hermes/tui_gateway/contracts/events.py:281-305`), plus `error` and `failure_reason`.
  `text` is the authoritative final copy (`hermes/tui_gateway/prompt_turn.py:929`). Failures outside a turn arrive as event `error {message}` (`hermes/tui_gateway/contracts/events.py:120`).
  If the event is lost: `prompt.receipt {session_id, admission_id, include_result:true}` (`hermes/gateway/session_controls.py:858-869`).
- Abort: `session.interrupt {session_id, execution_generation}` → `status:"interrupted"` (`repo/docs/protocol.md:389`, EXERCISED); the generation comes from the latest event envelope. Also set Hermes' own `run_budget` (`hermes/gateway/session_policy.py:623-629`).
- Keepalive: `gateway.ping` every 15 s (`repo/docs/protocol.md:140-141`). One long-lived ctx per socket, no per-call timeouts (`repo/server/internal/hermes/conn.go:19-21`). The turn survives our socket closing (`repo/docs/protocol.md:369-370`).

## 2. No tools (VERIFIED)
- An explicit `toolsets` replaces the profile's list for that session (`hermes/gateway/session_policy.py:648-676`) and stays frozen for later turns (`hermes/gateway/session_policy.py:252-257`). `None` = all tools, `[]` = none (`hermes/model_tools.py:313-328`).
- **Trap 1:** an explicit empty list still merges every enabled MCP server unless `no_mcp` is in it (`hermes/hermes_cli/tools_config.py:746-752`). Offline check: `{cli:[]}` + one MCP server → `['x']`; `{cli:['no_mcp']}` → `[]`; `get_tool_definitions([])` → `[]`.
  `no_mcp` is an accepted name (`hermes/hermes_cli/toolset_validation.py:94`).
- **Trap 2:** with xAI credentials, `x_search` (web search) is added even to an explicit list (`hermes/hermes_cli/tools_config.py:514-516`). There are none today (no xAI in auth.json); the profile's `agent.disabled_toolsets`, applied last, blocks it (`hermes/hermes_cli/tools_config.py:636-641`).
- Without tools there are no memory-provider tools (`hermes/agent/memory_manager_tools.py:34`), no memory review (`hermes/agent/turn_context.py:765-774`) and no skill review (`hermes/agent/turn_finalizer.py:863-867`).
  `ignore_rules:true` drops SOUL, memory and context files (`hermes/gateway/session_execution.py:42-48`, `hermes/gateway/session_execution.py:183-184`), so the prompt is entirely ours.
- Server guard: the create snapshot's `info.tool_count` is what the first turn will load (`hermes/gateway/session_local_inventory.py:200-202`). If it is not `0`: delete the session, fail `tools_present`, submit nothing.

## 3. Model and cost
- A fresh profile copies the active profile's model block (`hermes/hermes_cli/profiles.py:727-740`): today `claude-sonnet-5-5` / `anthropic` (root config.yaml lines 78 and 111). It reuses the root Anthropic grant (`hermes/hermes_cli/profiles.py:1228-1231`), so it draws on the same Claude plan.
- Per-session pin: `session.create {model, provider}` (`hermes/gateway/session_policy.py:436-438`). Recommended: a server setting `analyzer_model` (empty = profile default), plus `reasoning:"low"` to stay within 30-60 s (latency ASSUMED).
- Cost (ASSUMED, not measured): about 5-8 k input tokens (Hermes base prompt without tools + about 2.5 k of data) and 0.5-1 k output per run, plus at most one repair turn. It counts against the 5-hour and weekly windows TY7 shows (`repo/docs/protocol.md:1137-1145`).
  Auto-titling makes an extra model call per session; turn it off in the profile (`hermes/agent/title_generator.py:147-167`).

## 4. Visibility and cleanup (VERIFIED)
- `hidden:true` (`hermes/gateway/session_controls.py:530`) keeps the session off the Desktop's REST list (`hermes/hermes_state_sessions.py:1329-1330`, `hermes/apps/desktop/src/api/sessions.ts:104-117`) and off WS `session.list` (`hermes/gateway/session_controls.py:599`).
  Source `tool` is an internal source (`hermes/hermes_state_sessions.py:241`), which also keeps it off `session.active_list`, the phone's running-work view (`hermes/gateway/session_active_list.py:34-36`). `hidden` alone would still show there.
- Send no `title`: a titled create returns the existing session with that title (`repo/docs/protocol.md:304`).
- Delete: `session.mutate {session_id, request_id, expected_revision, operation:"delete", payload:{}}` (`hermes/hermes_state_mutations.py:40`). The session must be idle (`hermes/gateway/session_mutations.py:61-67`), so send it after `message.complete`; on `revision_conflict`, retry once with `error.data.revision`.
- **Recommend delete** once the verdict is stored on the PC (Q99 keeps it there). On failure keep the hidden session and log its id (`hermes -p aristotle-stocks sessions`); the server deletes those older than 7 days at startup.

## 5. Approvals and clarify
With no tools nothing can ask: approvals come from tool gates, and clarify is itself a tool (`hermes/toolsets.py:151`). `finite:true` makes a stray clarify answer itself (`hermes/gateway/session_finite.py:17-24`).
If an `approval.request`, `clarify.request` or `srq-` frame arrives anyway, the server answers nothing (bridges must never be answered: `repo/docs/protocol.md:484-488`), interrupts the turn and fails the job `unexpected_prompt`.

## 6. Concurrency
Sessions are independent; the owner runs one FIFO per session (`repo/docs/protocol.md:381-383`). `max_concurrent_sessions` is unset in root config.yaml (line 1011), so there is no cap. The user's chat in another profile does not block an analysis.
Both share the Anthropic plan and its rate limits (ASSUMED effect: slower replies, or a 429 that ends as `status:error`). Server rule: one session per job, at most 1 job per code and 2 in total.

## 7a. Prompt (one `prompt.submit`; Hermes owns the system prompt, so our "system" block leads the user text)
```
SYSTEM: You are an IDX day-trading analyst. Judge only the DATA below. Never invent prices, figures or news. You have no tools.
TASK: {code} for the session on {session_date} ({market_state}). Held: {held}. Verdicts allowed: {held ? "add|hold|sell" : "buy_today|wait|avoid"}.
RULES: every price is an integer IDR in [{arb}, {ara}] and a multiple of its band's tick (<200:1, <500:2, <2000:5, <5000:10, else 25).
buy_today/add: stop < entry.low <= entry.high < target. hold: stop set, entry null. sell/avoid: entry, target, stop null. wait: entry = trigger zone or null.
Base levels on DATA.levels and DATA.indicators. Give 3-5 reasons, each citing at least one id from DATA (ind:*, lvl:*, news:*, hold:*). Reply with ONLY this JSON object, no prose, no code fence:
{"schema":"stock_verdict/1","code":"","data_as_of":"<copy DATA.as_of>","verdict":"","confidence":"low|med|high","entry":{"low":0,"high":0}|null,"target":0|null,"stop":0|null,"summary":"<=280 chars","reasons":[{"text":"<=200 chars","sources":["ind:rsi14"]}]}
DATA: {"as_of":"…","quote":{…},"limits":{"ara","arb"},"indicators":{"ind:ma20":…},"levels":{"lvl:s1":…},"holding":{…}|null,"news":[{"id":"news:1","source","published_at","title","summary"}]}
```
**Validation and repair:** strip one surrounding code fence, then the reply must parse as exactly one object with no unknown fields. Check the schema, the enums, the `code`/`data_as_of` echo, and that every `sources` id exists in DATA.
On any failure send **one** repair turn in the same session: `Your reply failed validation: <≤5 errors>. Reply again with ONLY the corrected JSON object.` A second failure fails the job `invalid_verdict`.

## 7b. Server contract (protocol §20 style: device token, `Cache-Control: no-store`, errors `{"error":"<code>"}`)
- `POST /v1/stocks/analyze/{code}` (no body) → `202 {"job":{"id":"an_<12 hex>","code","status":"running","started_at"}}`; a job already running for this code → `200` with that job.
  Errors: `400 bad_code`, `422 unknown_symbol`, `409 busy` (2 jobs running), `503 analyzer_unavailable` + `"reason":"hermes_not_running"|"profile_missing"` (checked at start via `identify.served_profiles`).
- `GET /v1/stocks/analysis/{code}` → `200 {"code","job":{id,status:"running"|"done"|"failed",started_at,finished_at,error,detail}|null,"last":{<verdict fields>,"analyzed_at","duration_ms","model","flags":[],"input":{indicators+levels sent}}|null}`; `404 no_analysis` when both are null.
  The phone polls every 2 s while the job runs. `error` ∈ `timeout, model_error, invalid_verdict, verdict_rejected, tools_present, unexpected_prompt, data_unavailable, hermes_lost, interrupted`.
- `last` lives in its own `stocks-analyses.json` (0600, atomic replace, one entry per code), so `stocks.json` v1 is not touched. A failed job never replaces `last`. Jobs do not survive a restart. Timeout 120 s → `session.interrupt`.

## 7c. Indicators (only bars TS1 already fetches: `repo/server/internal/stocks/provider.go:61-83`; integer IDR; levels snapped to tick)
- Daily `6mo` (1d bars, about 120) over **completed** sessions (today's partial bar left out). MA20 and MA50 = SMA of closes. Trend `up` if close > MA20 > MA50 and MA20 is above its value 5 bars ago, `down` for the mirror case, else `side`. Also the price's % distance from each MA.
- RSI14 (Wilder): seed the average gain and average loss with the SMA of the first 14 changes, then `avg = (avg×13 + x)/14`; RSI = 100 − 100/(1+AG/AL), and 100 when AL = 0.
- Volume: today's cumulative volume ÷ the 20-session average, both raw and paced by elapsed trading minutes (330 per day Mon-Thu, 270 on Fri, from the §20 hours), plus yesterday's ratio. Extra: ATR14 (Wilder) for the stop distance.
- Levels: swing highs and lows of the last 60 sessions (a high above the 2 bars on each side, lows likewise), merged within 1.5 %; the 2 nearest below (`lvl:s1`, `lvl:s2`) and above (`lvl:r1`, `lvl:r2`); the 20-day high and low; the floor pivot of the last session P = (H+L+C)/3, R1 = 2P−L, S1 = 2P−H.
- 52-week position = (price − low52)/(high52 − low52) × 100, from the quote's `week52_*`, else from `1y` bars. Intraday (`1d` 5 m bars, when today has ≥ 3): VWAP = Σ(((H+L+C)/3)×V)/ΣV, today's open, high and low, gap % vs prev close. Yahoo is about 10 min behind.
- News: up to 8 items for the code from the news ring (strong matches first; fetch first if the ring is older than 10 min), each with title and a summary of at most 300 characters.

## 7d. Verdict sanity check (server, after the schema check)
- Limit base: prev_close while the market is open or on break; while closed, the last completed session's close, and `session_date` = the next trading day. Limits = `AutoReject(base, session_date)` (`repo/server/internal/stocks/idxcal.go:172`).
  ARB is only defined until 2026-12-31 (`repo/server/internal/stocks/idxcal.go:154`); after that, skip the range check and add the flag `limits_unknown`.
- **Reject (one repair turn, then `verdict_rejected`):** a price outside [ARB, ARA]; `p % TickSize(p) != 0` (`repo/server/internal/stocks/idxcal.go:116`); `entry.low > entry.high`; buy_today/add without `stop < entry.low ≤ entry.high < target`; hold without a stop or with stop ≥ price; any non-null price on sell/avoid; a verdict outside the set for the held state.
- **Flag (kept, listed in `flags`):** reward/risk = (target − entry.high)/(entry.low − stop) below 1.5; stop more than 2×ATR14 below entry; entry more than 3 % from the price; target more than 1 tick above `lvl:r1`; market closed (the verdict is for the next session).

## User setup (the server writes no Hermes file)
1. `hermes profile create aristotle-stocks --no-alias --no-skills --description "Aristotle stock analyzer (tool-less)"` (`hermes/hermes_cli/subcommands/profile.py:18-48`). The running gateway serves it without a restart (`hermes/gateway/run_profile_reconcile.py:1-12`).
2. In `~/.hermes/profiles/aristotle-stocks/config.yaml` add `platform_toolsets: {cli: [no_mcp]}`, `agent: {disabled_toolsets: [x_search, web, browser, terminal, file, code_execution, delegation, memory, skills, clarify]}` and `auxiliary: {title_generation: {enabled: false}}`.
   Optionally `model: {default: claude-sonnet-5-5, provider: anthropic}`. No `mcp_servers`.
3. The server sends with each session: `{source:"tool", hidden:true, toolsets:["no_mcp"], ignore_rules:true, reasoning:"low", max_turns:3, run_budget:90}`.

## Open risks and decisions
- Assumed: latency and token cost (measure on the first real run); whether `auxiliary.title_generation` is read from the session's profile home in a multiplexed gateway.
- Yahoo `previousClose` after the close: TS0 found today's bar close null once the session ends, so the limit base must come from the server's own bars, not the quote.
- Shared Claude plan: every analysis spends the user's 5-hour window, and on-demand runs have no cap (Q87). Optional: a 60 s cooldown per code. The profile appears in the Desktop's and the phone's profile lists (its sessions do not).
- User decisions: delete or keep failed sessions (recommended: keep 7 days); language of the reasons (EN/ID); profile-scoped vs host-scoped socket (recommended: profile-scoped, a small `hermes` package addition); off-tick prices rejected (recommended) or snapped.
