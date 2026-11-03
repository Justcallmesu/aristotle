package io.github.justcallmesu.aristotle.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.RelayLink
import io.github.justcallmesu.aristotle.data.ResumedSessions
import io.github.justcallmesu.aristotle.data.RpcException
import io.github.justcallmesu.aristotle.data.chat.ApprovalChoice
import io.github.justcallmesu.aristotle.data.chat.ChatDrafts
import io.github.justcallmesu.aristotle.data.chat.ChatFeed
import io.github.justcallmesu.aristotle.data.chat.ChatFeedState
import io.github.justcallmesu.aristotle.data.chat.ChatState
import io.github.justcallmesu.aristotle.data.chat.ClarifyAnswer
import io.github.justcallmesu.aristotle.data.chat.Prompt
import io.github.justcallmesu.aristotle.data.chat.PromptAnswer
import io.github.justcallmesu.aristotle.data.chat.PromptReplies
import io.github.justcallmesu.aristotle.data.chat.PromptReply
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class ChatUiState(
    val feed: ChatFeedState,
    val link: LinkState,
    val sheet: PromptSheetState = PromptSheetState(),
    val composer: ComposerState = ComposerState(),
) {
    /**
     * A sudo/secret answer is a bare response that Hermes may have resolved elsewhere meanwhile, and re-sending it
     * sends the password out again: after a reconnect it waits until this socket's snapshot confirms the prompt is
     * still open (D38). Other prompts are not secret and Hermes answers a duplicate itself.
     */
    fun answerable(prompt: Prompt?): Boolean =
        prompt !is Prompt.Secret || (link as? LinkState.Online)?.generation?.let { it == feed.confirmedGeneration } == true
}

/**
 * The chat screen's state: one [ChatFeed] for the session, run on [background] (frames are parsed and reduced
 * off the main thread, protocol §12), the link state for the top bar, the prompt sheet (T12) and the composer
 * (T13, [drafts] keeps the unsent text per session). [scope] is the ViewModel's own unless a test passes one.
 */
class ChatViewModel(
    private val sessionId: String,
    private val link: RelayLink,
    resumed: ResumedSessions,
    drafts: ChatDrafts? = null,
    private val background: CoroutineDispatcher = Dispatchers.Default,
    scope: CoroutineScope? = null,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    newId: () -> String = { UUID.randomUUID().toString().replace("-", "") },
) : ViewModel() {
    private val work: CoroutineScope = scope ?: viewModelScope
    private val feed = ChatFeed(link, resumed, sessionId)
    private val state = MutableStateFlow(
        ChatUiState(ChatFeedState(ChatState(sessionId)), link.state.value, composer = ComposerState(draft = drafts?.get(sessionId).orEmpty())),
    )
    val uiState: StateFlow<ChatUiState> = state.asStateFlow()
    private val composer = ChatComposer(sessionId, link, drafts, clock, newId, work, state::value, ::change)

    private var job: Job? = null
    private var arming: Job? = null
    private var armingFor: Shown? = null
    private var composerArming: Job? = null
    private var composerArmingFor: ComposerShown? = null

    init {
        work.launch {
            link.state.collect { current ->
                change { ui ->
                    val sheet = (current as? LinkState.Online)?.let { PromptSheetLogic.onReconnect(ui.sheet, it.generation) } ?: ui.sheet
                    ui.copy(link = current, sheet = sheet)
                }
            }
        }
        start()
    }

    /**
     * Retry after a failed load or a refused reattach: the link tries now, and the session is subscribed again.
     * A loaded transcript stays on screen and catches up from where it is.
     */
    fun retry() {
        link.retryNow()
        start()
    }

    private fun start() {
        job?.cancel()
        val from = state.value.feed
        job = work.launch {
            feed.states(from).flowOn(background).collect { next ->
                change { it.copy(feed = next, sheet = PromptSheetLogic.onChat(it.sheet, next.chat, next.loaded)) }
            }
        }
    }

    // ── prompt sheet (T12) ────────────────────────────────────────────────────────────────────────

    /** The waiting chip or a notification tap. */
    fun openSheet() = updateSheet { it.copy(open = true) }

    /** Back on the sheet: it goes down to the chip; the prompt stays open in Hermes. */
    fun hideSheet() = updateSheet { it.copy(open = false, confirmAlways = null) }

    fun closeWithdrawn() = updateSheet { it.copy(withdrawn = null) }

    fun messageShown() = updateSheet { it.copy(message = null) }

    /**
     * An approval button. "Always allow" only asks for the second confirmation; [confirmAlways] sends it. Every
     * answer below is dropped unless its prompt is the one on screen and has been for [PromptSheetLogic.ARM_MS].
     */
    fun choose(key: String, choice: ApprovalChoice) {
        if (!armed(key)) return
        if (choice == ApprovalChoice.Always) {
            updateSheet { it.copy(confirmAlways = key) }
            return
        }
        send(key, PromptAnswer.Choose(choice))
    }

    /** The second step of "Always allow"; nothing unless that step was asked for this prompt and has been seen. */
    fun confirmAlways(key: String) {
        if (state.value.sheet.confirmAlways != key || !armed(key, confirming = true)) return
        send(key, PromptAnswer.Choose(ApprovalChoice.Always))
    }

    fun cancelAlways() = updateSheet { it.copy(confirmAlways = null) }

    fun answerClarify(key: String, answers: Map<String, ClarifyAnswer>) {
        if (armed(key)) send(key, PromptAnswer.Answers(answers))
    }

    /**
     * Sends a sudo password or secret value once (Q51). The value is taken out of [field] (empty afterwards) and lives
     * only in the frame being sent; nothing here keeps, logs or shows it. A tap before the prompt was seen leaves it.
     */
    fun answerSecret(key: String, field: SecretField) {
        if (!armed(key)) return
        val value = field.take()
        if (value.isEmpty()) return
        send(key, PromptAnswer.Value(value))
    }

    /** Cancel on a sudo/secret sheet: Hermes is told the user skipped it (`value: ""`). */
    fun declineSecret(key: String, field: SecretField) {
        if (!armed(key)) return
        field.take()
        send(key, PromptAnswer.Decline)
    }

    /** [key] is on screen (in [confirming]'s step) and has been for [PromptSheetLogic.ARM_MS]. */
    private fun armed(key: String, confirming: Boolean = false): Boolean {
        val ui = state.value
        val prompt = ui.feed.chat.prompts.firstOrNull { it.key == key }
        return ui.answerable(prompt) && PromptSheetLogic.armed(ui.sheet, key, confirming, clock())
    }

    /**
     * The activity came back to the front (D38): what is on screen starts its arming time again, so a prompt or a
     * composer swap that happened while the app was stopped is not tappable the instant the screen returns.
     */
    fun onResume() {
        val now = clock()
        state.update { ui ->
            ui.copy(
                sheet = ui.sheet.copy(shown = ui.sheet.shown?.copy(at = now, armed = false)),
                composer = ui.composer.copy(shown = ui.composer.shown?.copy(at = now, armed = false)),
            )
        }
        val next = state.value
        armSheet(next.sheet.shown)
        armComposer(next.composer.shown)
    }

    private fun send(key: String, answer: PromptAnswer) {
        val current = state.value
        if (current.sheet.sending != null) return
        val prompt = current.feed.chat.prompts.firstOrNull { it.key == key } ?: return
        val replies = PromptReplies.of(sessionId, prompt, answer)
        if (replies.isEmpty()) return
        // A bare response gets no reply: if this socket drops, the next one may show the prompt again (MINOR-1).
        val generation = (current.link as? LinkState.Online)?.generation
        val bare = replies.all { it is PromptReply.Response }
        updateSheet { it.copy(sending = key, answered = it.answered + key, confirmAlways = null, message = null) }
        work.launch {
            val outcome = deliver(replies)
            updateSheet { sheet ->
                val base = sheet.copy(sending = null)
                when (outcome) {
                    Outcome.Resolved -> if (bare && generation != null && key in base.answered) {
                        base.copy(unconfirmed = base.unconfirmed + (key to generation))
                    } else {
                        base
                    }
                    Outcome.AnsweredFirst -> base.copy(message = PromptMessage.AnsweredFirst)
                    Outcome.NotSent -> base.copy(answered = base.answered - key, message = PromptMessage.NotSent)
                    // Hermes kept the prompt: it is answerable again while it stays open (MINOR-1, no dead chip).
                    is Outcome.Refused -> base.copy(answered = base.answered - key, message = PromptMessage.Refused(outcome.reason))
                }
            }
        }
    }

    private sealed interface Outcome {
        data object Resolved : Outcome
        data object AnsweredFirst : Outcome
        data object NotSent : Outcome
        data class Refused(val reason: String) : Outcome
    }

    /** Sends the frames in order and stops at the first that did not count. */
    private suspend fun deliver(replies: List<PromptReply>): Outcome {
        for (reply in replies) {
            when (reply) {
                // A response gets no reply; Hermes then withdraws the request everywhere (`request.cancel` resolved).
                is PromptReply.Response -> if (!link.respond(reply.requestId, reply.result)) return Outcome.NotSent
                is PromptReply.Call -> {
                    val result = try {
                        link.call(reply.method, reply.params) as? JsonObject
                    } catch (e: RpcException) {
                        val error = e.error
                        return when {
                            e.linkClosed -> Outcome.NotSent
                            error != null -> Outcome.Refused(error.message.ifBlank { "error ${error.code}" })
                            else -> Outcome.Refused(e.message.orEmpty())
                        }
                    }
                    // Another screen won (`gateway/session_pending_controls.py:70-71,94,101`), or a batch lock came after
                    // the wait ended (`gateway/session_clarify_lock.py:35-37`).
                    val status = (result?.get("status") as? JsonPrimitive)?.contentOrNull
                    if (status == "already_resolved" || status == "expired") return Outcome.AnsweredFirst
                }
            }
        }
        return Outcome.Resolved
    }

    // ── composer (T13) ────────────────────────────────────────────────────────────────────────────

    fun editDraft(text: String) = composer.edit(text)

    fun send() = composer.send()

    fun queue() = composer.queue()

    fun steer() = composer.steer()

    fun stop() = composer.stop()

    fun cancelQueued(admissionId: String) = composer.cancelQueued(admissionId)

    fun retrySend(id: String) = composer.retry(id)

    fun dismissFailed(id: String) = composer.dismiss(id)

    fun composerMessageShown() = composer.messageShown()

    private fun updateSheet(transform: (PromptSheetState) -> PromptSheetState) = change { it.copy(sheet = transform(it.sheet)) }

    /**
     * Every state change goes through here: it re-reads what the sheet has on screen ([PromptSheetLogic.track]),
     * drops the echoes Hermes now shows itself ([Outbox.reconcile]) and re-reads what the composer's controls mean
     * ([ComposerLogic.track]); a newly shown prompt or control turns its buttons on after the arming time.
     */
    private fun change(transform: (ChatUiState) -> ChatUiState) {
        val now = clock()
        val next = state.updateAndGet { current ->
            val changed = transform(current)
            val sheet = PromptSheetLogic.track(changed.sheet, changed.feed.chat, now)
            val outbox = Outbox.reconcile(changed.composer.outbox, changed.feed.chat)
            val key = ComposerLogic.key(changed.link, changed.feed, sheet, outbox)
            changed.copy(sheet = sheet, composer = ComposerLogic.track(changed.composer.copy(outbox = outbox), key, now))
        }
        armSheet(next.sheet.shown)
        armComposer(next.composer.shown)
    }

    private fun armSheet(shown: Shown?) {
        if (shown == null || shown.armed || shown == armingFor) return
        armingFor = shown
        arming?.cancel()
        arming = work.launch {
            delay(PromptSheetLogic.ARM_MS)
            state.update { ui -> if (ui.sheet.shown == shown) ui.copy(sheet = ui.sheet.copy(shown = shown.copy(armed = true))) else ui }
        }
    }

    private fun armComposer(shown: ComposerShown?) {
        if (shown == null || shown.armed || shown == composerArmingFor) return
        composerArmingFor = shown
        composerArming?.cancel()
        composerArming = work.launch {
            delay(ComposerLogic.ARM_MS)
            state.update { ui -> if (ui.composer.shown == shown) ui.copy(composer = ui.composer.copy(shown = shown.copy(armed = true))) else ui }
        }
    }
}
