package io.github.justcallmesu.aristotle.data.chat

import io.github.justcallmesu.aristotle.data.InboundFrame
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One session event as the reducer takes it: the frame and when the phone received it (reasoning time). */
data class ChatFrame(val event: InboundFrame.Event, val atMillis: Long)

/**
 * The chat transcript as a pure function of Hermes' session events (protocol §2, captured in
 * `docs/spikes/frames/c-*.jsonl`, `f-busy-*.jsonl`). Deltas change only the last item; every other item keeps its
 * instance, so the list recomposes one row per chunk. Unknown events, and events of another session, leave
 * the state as it is.
 */
object ChatReducer {
    /** Never kept on the phone (spec Q34): the relay strips them; this is the second guard. */
    private val DROPPED_INFO_KEYS = setOf("system_prompt", "credential_hint")

    /** Top-level snapshot keys that are shape-A `session.info` state (protocol §2.1, §4). */
    private val SNAPSHOT_STATE_KEYS = listOf("stored_session_id", "pending", "authority_epoch", "running", "execution_generation", "revision")

    private val EMPTY = JsonObject(emptyMap())

    fun reduce(state: ChatState, frame: ChatFrame): ChatState {
        val event = frame.event
        if (event.sessionId != state.sessionId || alreadyApplied(state, event)) return state
        return apply(state, event, event.payload ?: EMPTY, frame.atMillis).at(event)
    }

    /**
     * A Hermes -> client request for this session (`sudo`, `secret`, batch `clarify`, vault): it opens a prompt. It
     * carries no `seq`; a repeat (the same request again in a snapshot's `open_requests`) replaces the entry.
     */
    fun request(state: ChatState, request: InboundFrame.ServerRequest): ChatState {
        if (request.sessionId != state.sessionId) return state
        return Prompts.fromRequest(request)?.let { openPrompt(state, it) } ?: state
    }

    /** `session.events.since` also lists the requests still open (`gateway/session_controls.py:745-751`). */
    fun withOpenRequests(state: ChatState, openRequests: JsonArray?): ChatState {
        if (openRequests == null) return state
        val requests = requestPrompts(openRequests)
        val kept = state.prompts.filter { !it.fromRequest() }
        return replacePrompts(state, kept + requests)
    }

    /**
     * The state a `session.resume` result (or `session.create`) gives. [previous] keeps its items when the
     * snapshot omits messages (a reconnect that caught up with `session.events.since`), and its info is the base
     * the snapshot's info merges into.
     *
     * An omitted snapshot continues [previous] at its own position: Hermes writes every frame after the caught-up
     * `seq` to this socket before it answers the resume (`session_authority.py:369-370`), so the snapshot's
     * `last_sequence` is ahead of what was applied, and those frames are still in the inbox. Taking that position
     * would drop them as already applied; keeping the caught-up one applies them once (T11 review MAJOR-1).
     */
    fun fromSnapshot(sessionId: String, snapshot: JsonObject, previous: ChatState? = null): ChatState {
        val caughtUp = previous?.takeIf { snapshot.flag("messages_omitted") == true }
        val running = snapshot.flag("running") == true
        val last = snapshot.number("last_sequence")?.toLong()
        // Frames between the caught-up seq and the snapshot are on their way: they move the turn on themselves.
        val behind = caughtUp != null && last != null && (caughtUp.lastSeq == null || last > caughtUp.lastSeq)
        val keepTurn = caughtUp != null && (running || behind)
        val base = when {
            previous == null -> ChatState(sessionId)
            keepTurn -> previous
            else -> settled(previous)
        }
        val started = pendingEntries(snapshot["pending"]).filter { it.text("status") == "started" }.mapNotNull { it.text("admission_id") }
        val state = base.copy(
            sessionId = snapshot.text("session_id")?.ifEmpty { null } ?: sessionId,
            items = if (snapshot.flag("messages_omitted") == true) base.items else HistoryMapper.items(snapshot["messages"] as? JsonArray),
            running = running,
            step = if (keepTurn) base.step else null,
            thinking = keepTurn && base.thinking,
            replayEpoch = caughtUp?.replayEpoch ?: snapshot.text("replay_epoch"),
            lastSeq = caughtUp?.lastSeq ?: last,
            turn = if (keepTurn) base.turn ?: TurnCursor().takeIf { running } else TurnCursor().takeIf { running },
            // The stored history already holds the prompt of the turn now running (ASSUMED: the user row is
            // committed when the turn starts), so its admission is not shown again from `pending`. Behind, the
            // turn's own frames show the prompt where it belongs.
            shownAdmissions = when {
                caughtUp == null -> started.toSet()
                behind -> base.shownAdmissions
                else -> base.shownAdmissions + started
            },
        )
        val stateKeys = JsonObject(SNAPSHOT_STATE_KEYS.mapNotNull { key -> snapshot[key]?.let { key to it } }.toMap())
        val merged = mergeInfo(mergeInfo(state, snapshot["info"].asObject() ?: EMPTY, showStarted = false), stateKeys, showStarted = false)
        val prompts = (snapshot["prompts"] as? JsonArray).orEmpty().mapNotNull { it.asObject()?.let { p -> Prompts.fromEvent(p, at = null) } } +
            requestPrompts(snapshot["open_requests"] as? JsonArray)
        val withPrompts = replacePrompts(merged, prompts)
        return if (behind) withPrompts.copy(running = base.running) else withPrompts
    }

    // ── prompts (protocol §6) ─────────────────────────────────────────────────────────────────────

    private fun requestPrompts(entries: JsonArray?): List<Prompt> = entries.orEmpty().mapNotNull { it.asObject()?.let(Prompts::fromOpenRequest) }

    private fun Prompt.fromRequest(): Boolean = key.startsWith("srq-")

    private fun openPrompt(state: ChatState, prompt: Prompt): ChatState {
        val index = state.prompts.indexOfFirst { it.key == prompt.key }
        val prompts = if (index < 0) state.prompts + prompt else state.prompts.toMutableList().also { it[index] = prompt }
        return state.copy(prompts = prompts)
    }

    /** A `*.settled` or `request.cancel`: the prompt is no longer open (answered here or elsewhere, timed out, ended). */
    private fun settlePrompt(state: ChatState, key: String, reason: String?): ChatState {
        val prompt = state.prompts.firstOrNull { it.key == key } ?: return state
        return state.copy(
            prompts = state.prompts - prompt,
            lastSettled = SettledPrompt(prompt, reason, state.nextKey),
            nextKey = state.nextKey + 1,
        )
    }

    /** A snapshot's list is the truth; a prompt that was open and is gone settled meanwhile. */
    private fun replacePrompts(state: ChatState, prompts: List<Prompt>): ChatState {
        val fresh = prompts.distinctBy { it.key }
        val gone = state.prompts.firstOrNull { old -> fresh.none { it.key == old.key } }
        val next = state.copy(prompts = fresh)
        return if (gone == null) next else next.copy(lastSettled = SettledPrompt(gone, null, next.nextKey), nextKey = next.nextKey + 1)
    }

    private fun apply(state: ChatState, event: InboundFrame.Event, payload: JsonObject, at: Long): ChatState = when (event.type) {
        "message.start" -> startTurn(state, payload, event.admissionId, at)
        "message.delta" -> messageDelta(state, payload, at)
        "message.interim" -> interim(state, payload, at)
        "message.complete" -> complete(state, payload, at)
        "reasoning.delta" -> reasoningDelta(state, payload, at)
        "reasoning.available" -> reasoningAvailable(state, payload, at)
        // Hermes' spinner line ("(¬_¬) reasoning..."), cleared with "": a status, not reasoning text (frames c-*, f-busy-*).
        "thinking.delta" -> state.copy(thinking = !payload.text("text").isNullOrEmpty())
        "agent.step" -> state.copy(running = true, step = payload.number("iteration")?.toInt() ?: state.step)
        "tool.start" -> toolStart(state, payload, at)
        "tool.progress" -> toolProgress(state, payload)
        "tool.complete" -> toolComplete(state, payload, at)
        "session.info" -> mergeInfo(state, payload)
        "session.title" -> payload["title"]?.let { mergeInfo(state, JsonObject(mapOf("title" to it))) } ?: state
        "approval.request", "clarify.request" -> Prompts.fromEvent(payload, at)?.let { openPrompt(state, it) } ?: state
        "approval.settled", "clarify.settled" -> payload.text("prompt_id")?.let { settlePrompt(state, it, null) } ?: state
        // A withdrawn Hermes -> client request (`tui_gateway/contracts/server_requests.py:364-379`).
        "request.cancel" -> payload.text("id")?.let { settlePrompt(state, it, payload.text("reason")) } ?: state
        "error" -> notice(state, NoticeKind.Error, payload.text("message").orEmpty())
        "notice" -> payload.text("message")?.takeIf { it.isNotBlank() }?.let { notice(state, NoticeKind.Info, it) } ?: state
        else -> state
    }

    // ── position in the event stream ──────────────────────────────────────────────────────────────

    /** A frame at or before the last applied `seq` of the same replay epoch (a resume race, a replay). */
    private fun alreadyApplied(state: ChatState, event: InboundFrame.Event): Boolean {
        val seq = event.seq ?: return false
        val last = state.lastSeq ?: return false
        val sameEpoch = event.replayEpoch == null || state.replayEpoch == null || event.replayEpoch == state.replayEpoch
        return sameEpoch && seq <= last
    }

    private fun ChatState.at(event: InboundFrame.Event): ChatState {
        val seq = event.seq ?: return this
        val newEpoch = event.replayEpoch != null && replayEpoch != null && event.replayEpoch != replayEpoch
        val last = lastSeq
        return copy(lastSeq = if (newEpoch || last == null) seq else maxOf(last, seq), replayEpoch = event.replayEpoch ?: replayEpoch)
    }

    // ── session.info ──────────────────────────────────────────────────────────────────────────────

    /** Protocol §2.1: merge only the keys present; never replace the info, never default an absent key. */
    private fun mergeInfo(state: ChatState, payload: JsonObject, showStarted: Boolean = true): ChatState {
        val kept = payload.filterKeys { it !in DROPPED_INFO_KEYS }
        if (kept.isEmpty()) return state
        var next = state.copy(info = JsonObject(state.info + kept))
        payload.flag("running")?.let { next = next.copy(running = it) }
        if (showStarted) {
            pendingEntries(payload["pending"]).filter { it.text("status") == "started" }.forEach { next = showUser(next, it) }
        }
        return next
    }

    private fun pendingEntries(pending: Any?): List<JsonObject> = (pending as? JsonArray).orEmpty().mapNotNull { it.asObject() }

    /** The prompt of a turn, from its `pending` admission (the stream itself never repeats what the user typed). */
    private fun showUser(state: ChatState, admission: JsonObject): ChatState {
        val id = admission.text("admission_id") ?: return state
        if (id in state.shownAdmissions) return state
        val text = admission.text("text").orEmpty()
        val items = if (text.isBlank()) state.items else state.items + ChatItem.User("user-$id", text)
        return state.copy(items = items, shownAdmissions = state.shownAdmissions + id)
    }

    // ── turn ──────────────────────────────────────────────────────────────────────────────────────

    private fun startTurn(state: ChatState, payload: JsonObject, admissionId: String?, at: Long): ChatState {
        var next = settled(state, at).copy(running = true, step = null, thinking = false, turn = TurnCursor(turnId = payload.text("turn_id")))
        val admission = admissionId ?: payload.text("admission_id")
        pendingEntries(next.info["pending"]).firstOrNull { it.text("admission_id") == admission }?.let { next = showUser(next, it) }
        return next
    }

    private fun messageDelta(state: ChatState, payload: JsonObject, at: Long): ChatState {
        val text = (payload["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        // `text: null` is a segment boundary (before tool lines): the open text block ends here.
        if (text == null) return closeText(state)
        val (moved, fresh) = advance(state.turn ?: TurnCursor(), payload, text) ?: return state
        // Past what was applied: the chat joined the turn late (the snapshot has no partial text) or chunks were lost.
        val turn = when {
            !moved.missedStart -> moved
            moved.textKey != null -> moved.copy(missedStart = false, lateTextKey = moved.textKey)
            else -> moved
        }
        val next = endReasoning(state.copy(turn = turn), at)
        return appendText(next, fresh)
    }

    /**
     * Moves the cursor past [text] and returns what is new in it, or null when all of it was applied
     * before. `offset` counts UTF-16 units, which is what a Kotlin string's length counts.
     */
    private fun advance(cursor: TurnCursor, payload: JsonObject, text: String): Pair<TurnCursor, String>? {
        val turnId = payload.text("turn_id")
        val segment = payload.number("segment")?.toInt()
        val offset = payload.number("offset")?.toInt()
        var turn = when {
            turnId == null || turnId == cursor.turnId -> cursor
            cursor.turnId == null -> cursor.copy(turnId = turnId)
            else -> TurnCursor(turnId = turnId) // a new turn whose message.start was missed
        }
        if (segment != null && segment < turn.segment) return null // a re-sent chunk of a sealed segment
        if (segment != null && segment > turn.segment) turn = turn.copy(segment = segment, applied = 0)
        val applied = turn.applied
        if (offset != null && offset > applied) turn = turn.copy(missedStart = true)
        val fresh = when {
            offset == null || offset >= applied -> text
            offset + text.length <= applied -> ""
            else -> text.substring(applied - offset)
        }
        val reached = if (offset == null) applied + text.length else maxOf(applied, offset + text.length)
        return turn.copy(applied = reached) to fresh
    }

    private fun appendText(state: ChatState, fresh: String): ChatState {
        val turn = state.turn ?: TurnCursor()
        val last = state.items.lastOrNull()
        if (last is ChatItem.Assistant && last.key == turn.textKey) {
            return state.copy(items = state.items.replaceLast(last.copy(text = last.text + fresh)))
        }
        // A block starts at its first visible character (the text after a tool card begins with "\n\n").
        val start = fresh.trimStart('\n', '\r')
        if (start.isEmpty()) return state
        val key = "a-${state.nextKey}"
        return state.copy(
            items = state.items + ChatItem.Assistant(key, start, streaming = true),
            nextKey = state.nextKey + 1,
            turn = turn.copy(
                textKey = key,
                lastTextKey = key,
                hadText = true,
                missedStart = false,
                lateTextKey = if (turn.missedStart) key else turn.lateTextKey,
            ),
        )
    }

    /** `message.interim`: the segment is sealed; text that did not stream arrives whole here. */
    private fun interim(state: ChatState, payload: JsonObject, at: Long): ChatState {
        var next = endReasoning(closeText(state), at)
        val text = payload.text("text")
        if (payload.flag("already_streamed") != true && !text.isNullOrBlank()) {
            next = addText(next, text)
        }
        val turn = next.turn ?: TurnCursor()
        val segment = payload.number("segment")?.toInt() ?: turn.segment
        return next.copy(turn = turn.copy(segment = segment + 1, applied = 0))
    }

    private fun complete(state: ChatState, payload: JsonObject, at: Long): ChatState {
        val turn = state.turn
        var next = endReasoning(closeText(state), at)
        val text = payload.text("text").orEmpty()
        val status = payload.text("status")
        val stopped = payload.text("outcome") == "cancelled" || status == "interrupted"
        val delivered = payload.flag("response_previewed") == true || payload.flag("response_reused") == true
        val late = turn?.lateTextKey?.takeIf { it == turn.lastTextKey }
        when {
            status == "error" -> next = notice(next, NoticeKind.TurnFailed, text)
            text.isBlank() -> Unit
            // The block missed its start (opened mid-turn): the final text is the whole of it.
            late != null -> next = replaceText(next, late, text)
            delivered -> Unit
            payload.flag("response_transformed") == true && turn?.lastTextKey != null -> next = replaceText(next, turn.lastTextKey, text)
            turn?.hadText != true -> next = addText(next, text)
        }
        if (stopped) next = notice(next, NoticeKind.Stopped, "")
        payload.text("warning")?.takeIf { it.isNotBlank() }?.let { next = notice(next, NoticeKind.Info, it) }
        return settled(next, at).copy(running = false, step = null, thinking = false, turn = null)
    }

    private fun addText(state: ChatState, text: String): ChatState {
        val key = "a-${state.nextKey}"
        val turn = (state.turn ?: TurnCursor()).copy(lastTextKey = key, hadText = true)
        return state.copy(items = state.items + ChatItem.Assistant(key, text), nextKey = state.nextKey + 1, turn = turn)
    }

    private fun replaceText(state: ChatState, key: String, text: String): ChatState =
        state.copy(items = state.items.map { if (it is ChatItem.Assistant && it.key == key) it.copy(text = text) else it })

    /** Nothing streams any more and no tool runs (turn end, a new turn, a snapshot rebuild). */
    private fun settled(state: ChatState, at: Long? = null): ChatState {
        val closed = endReasoning(closeText(state), at)
        if (closed.items.none { it.isLive() }) return closed
        return closed.copy(items = closed.items.map { item -> if (item.isLive()) item.settle() else item })
    }

    private fun ChatItem.isLive(): Boolean =
        (this is ChatItem.Assistant && streaming) || (this is ChatItem.Reasoning && streaming) || (this is ChatItem.Tool && status == ToolStatus.Running)

    private fun ChatItem.settle(): ChatItem = when (this) {
        is ChatItem.Assistant -> copy(streaming = false)
        is ChatItem.Reasoning -> copy(streaming = false)
        is ChatItem.Tool -> copy(status = ToolStatus.Done, progress = null)
        else -> this
    }

    private fun closeText(state: ChatState): ChatState {
        val turn = state.turn ?: return state
        val key = turn.textKey ?: return state
        return state.copy(items = state.items.updateKey(key) { (it as? ChatItem.Assistant)?.copy(streaming = false) ?: it }, turn = turn.copy(textKey = null))
    }

    // ── reasoning ─────────────────────────────────────────────────────────────────────────────────

    private fun reasoningDelta(state: ChatState, payload: JsonObject, at: Long): ChatState {
        val text = payload.text("text")?.takeIf { it.isNotEmpty() } ?: return state
        val turn = state.turn ?: TurnCursor()
        val last = state.items.lastOrNull()
        if (last is ChatItem.Reasoning && last.key == turn.reasoningKey) {
            return state.copy(items = state.items.replaceLast(last.copy(text = last.text + text)))
        }
        val next = closeText(state)
        val key = "r-${next.nextKey}"
        return next.copy(
            items = next.items + ChatItem.Reasoning(key, text, streaming = true, startedAtMillis = at),
            nextKey = next.nextKey + 1,
            turn = (next.turn ?: TurnCursor()).copy(reasoningKey = key),
        )
    }

    /** A whole reasoning block from a provider that does not stream it. */
    private fun reasoningAvailable(state: ChatState, payload: JsonObject, at: Long): ChatState {
        val text = payload.text("text")?.takeIf { it.isNotBlank() } ?: return state
        if (state.turn?.reasoningKey != null) return endReasoning(state, at)
        val next = closeText(state)
        return next.copy(items = next.items + ChatItem.Reasoning("r-${next.nextKey}", text), nextKey = next.nextKey + 1)
    }

    /** The open reasoning block ends when anything else of the turn arrives; its duration is known then. */
    private fun endReasoning(state: ChatState, at: Long?): ChatState {
        val turn = state.turn ?: return state
        val key = turn.reasoningKey ?: return state
        val items = state.items.updateKey(key) { item ->
            if (item !is ChatItem.Reasoning) return@updateKey item
            val started = item.startedAtMillis
            item.copy(streaming = false, durationMillis = if (at != null && started != null) (at - started).coerceAtLeast(0) else null)
        }
        return state.copy(items = items, turn = turn.copy(reasoningKey = null))
    }

    // ── tools ─────────────────────────────────────────────────────────────────────────────────────

    private fun toolStart(state: ChatState, payload: JsonObject, at: Long): ChatState {
        val toolId = toolIdOf(payload) ?: return state
        val next = endReasoning(closeText(state), at)
        val card = ChatItem.Tool(
            key = toolKey(toolId),
            toolId = toolId,
            name = toolNameOf(payload),
            summary = ToolText.summary(payload.text("context") ?: payload.text("preview"), payload["args"].asObject()),
            status = ToolStatus.Running,
        )
        if (next.items.any { it.key == card.key }) {
            return next.copy(items = next.items.updateKey(card.key) { (it as? ChatItem.Tool)?.copy(name = card.name, summary = card.summary) ?: it })
        }
        return next.copy(items = next.items + card)
    }

    private fun toolProgress(state: ChatState, payload: JsonObject): ChatState {
        val toolId = toolIdOf(payload) ?: return state
        val progress = payload.text("message")?.takeIf { it.isNotBlank() } ?: payload.number("percent")?.let { "${it.toInt()} %" }
        return state.copy(items = state.items.updateKey(toolKey(toolId)) { (it as? ChatItem.Tool)?.copy(progress = progress) ?: it })
    }

    private fun toolComplete(state: ChatState, payload: JsonObject, at: Long): ChatState {
        val toolId = toolIdOf(payload) ?: return state
        val next = endReasoning(closeText(state), at)
        val key = toolKey(toolId)
        val status = if (payload.flag("is_error") == true) ToolStatus.Failed else ToolStatus.Done
        val output = ToolText.output(payload["result"], payload.text("result_text"), payload.text("inline_diff"))
        val duration = payload.number("duration_s")
        if (next.items.none { it.key == key }) {
            val summary = ToolText.summary(payload.text("context"), payload["args"].asObject())
            return next.copy(items = next.items + ChatItem.Tool(key, toolId, toolNameOf(payload), summary, status, output, duration))
        }
        return next.copy(
            items = next.items.updateKey(key) {
                (it as? ChatItem.Tool)?.copy(status = status, output = output, durationSeconds = duration, progress = null) ?: it
            },
        )
    }

    private fun toolIdOf(payload: JsonObject): String? = (payload.text("tool_id") ?: payload.text("tool_call_id"))?.takeIf { it.isNotEmpty() }

    private fun toolNameOf(payload: JsonObject): String = payload.text("name") ?: payload.text("tool_name") ?: "tool"

    fun toolKey(toolId: String): String = "tool-$toolId"

    // ── notices ───────────────────────────────────────────────────────────────────────────────────

    private fun notice(state: ChatState, kind: NoticeKind, text: String): ChatState =
        state.copy(items = state.items + ChatItem.Notice("n-${state.nextKey}", kind, text), nextKey = state.nextKey + 1)

    // ── list helpers: change one item, keep every other instance ──────────────────────────────────

    private fun List<ChatItem>.replaceLast(item: ChatItem): List<ChatItem> = toMutableList().also { it[it.lastIndex] = item }

    private fun List<ChatItem>.updateKey(key: String, change: (ChatItem) -> ChatItem): List<ChatItem> {
        val index = indexOfLast { it.key == key }
        if (index < 0) return this
        val changed = change(this[index])
        if (changed == this[index]) return this
        return toMutableList().also { it[index] = changed }
    }
}
