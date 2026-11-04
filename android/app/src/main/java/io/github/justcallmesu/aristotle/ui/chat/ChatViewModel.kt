package io.github.justcallmesu.aristotle.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.justcallmesu.aristotle.data.AuthPurpose
import io.github.justcallmesu.aristotle.data.FreshAuth
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.ProfileLink
import io.github.justcallmesu.aristotle.data.RelayLink
import io.github.justcallmesu.aristotle.data.ResumedSessions
import io.github.justcallmesu.aristotle.data.RpcException
import io.github.justcallmesu.aristotle.data.chat.ApprovalChoice
import io.github.justcallmesu.aristotle.data.chat.AttachmentSource
import io.github.justcallmesu.aristotle.data.chat.ChatDrafts
import io.github.justcallmesu.aristotle.data.chat.ChatFeed
import io.github.justcallmesu.aristotle.data.chat.ChatFeedState
import io.github.justcallmesu.aristotle.data.chat.ChatState
import io.github.justcallmesu.aristotle.data.chat.ClarifyAnswer
import io.github.justcallmesu.aristotle.data.chat.ModelGroup
import io.github.justcallmesu.aristotle.data.chat.PickKind
import io.github.justcallmesu.aristotle.data.chat.Prompt
import io.github.justcallmesu.aristotle.data.chat.PromptAnswer
import io.github.justcallmesu.aristotle.data.chat.PromptReplies
import io.github.justcallmesu.aristotle.data.chat.PromptReply
import io.github.justcallmesu.aristotle.data.chat.SlashCommand
import io.github.justcallmesu.aristotle.data.chat.SlashMenu
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
    /** The model and reasoning sheet (T15). */
    val model: ModelSheetState = ModelSheetState(),
    /** The `/` menu and the commands run from this chat (T15). */
    val slash: SlashState = SlashState(),
    /** The opened subagent's page (T17). */
    val subagent: SubagentPageState = SubagentPageState(),
    /** The session's background processes and their Stop dialog (TB1, Q77). */
    val background: BackgroundState = BackgroundState(),
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
 * (T13, [drafts] keeps the unsent text per session), the model/reasoning switch and slash commands (T15). [scope] is the ViewModel's own unless a test passes one.
 */
class ChatViewModel(
    private val sessionId: String,
    base: RelayLink,
    resumed: ResumedSessions,
    drafts: ChatDrafts? = null,
    private val background: CoroutineDispatcher = Dispatchers.Default,
    scope: CoroutineScope? = null,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    newId: () -> String = { UUID.randomUUID().toString().replace("-", "") },
    /** The session's Hermes profile (null = the relay's launch profile): every call of this chat goes there (T14). */
    profile: String? = null,
    /** Reads and prepares picked photos and files (T16); null: attaching is off. */
    attachments: AttachmentSource? = null,
    /** Wall-clock time, for a subagent's elapsed time (T17); [clock] stays the arming clock. */
    wallClock: () -> Long = System::currentTimeMillis,
    subagentPacing: SubagentPacing = SubagentPacing(),
    /** How often `process.list` is read while the chat is on screen (TB1); tests pass a short one. */
    backgroundPollMs: Long = BackgroundLogic.POLL_MS,
    /** The app lock's fresh check (Q61) before "Always allow" and a sudo/secret value; [FreshAuth.None]: no lock. */
    private val freshAuth: FreshAuth = FreshAuth.None,
) : ViewModel() {
    private val link: RelayLink = ProfileLink(base, profile)
    private val work: CoroutineScope = scope ?: viewModelScope
    private val feed = ChatFeed(link, resumed, sessionId, profile = profile)
    private val state = MutableStateFlow(
        ChatUiState(ChatFeedState(ChatState(sessionId)), link.state.value, composer = ComposerState(draft = drafts?.get(sessionId).orEmpty())),
    )
    val uiState: StateFlow<ChatUiState> = state.asStateFlow()
    private val composer = ChatComposer(sessionId, link, drafts, clock, newId, work, state::value, ::change, attachments)
    private val models = ChatModelControl(sessionId, link, newId, clock, work, state::value, ::change)
    private val commands = ChatCommands(sessionId, link, newId, clock, work, state::value, ::change, composer)
    private val subagents = ChatSubagents(sessionId, link, clock, wallClock, work, state::value, ::change, feed::offerSubagents, subagentPacing)
    private val processes = ChatBackground(sessionId, link, clock, wallClock, work, state::value, ::change, backgroundPollMs)

    private var job: Job? = null
    private var authorizing: Job? = null
    private var arming: Job? = null
    private var armingFor: Shown? = null
    private var composerArming: Job? = null
    private var composerArmingFor: ComposerShown? = null
    private var subagentArming: Job? = null
    private var subagentArmingFor: SubagentShown? = null

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

    /**
     * The second step of "Always allow"; nothing unless that step was asked for this prompt and has been seen. With
     * the app lock on, a fresh check comes first (Q61); a failed or cancelled one sends nothing.
     */
    fun confirmAlways(key: String) {
        if (state.value.sheet.confirmAlways != key || !armed(key, confirming = true)) return
        afterCheck(key, AuthPurpose.AlwaysAllow, stillWanted = { it.sheet.confirmAlways == key }) {
            send(key, PromptAnswer.Choose(ApprovalChoice.Always))
        }
    }

    fun cancelAlways() = updateSheet { it.copy(confirmAlways = null) }

    fun answerClarify(key: String, answers: Map<String, ClarifyAnswer>) {
        if (armed(key)) send(key, PromptAnswer.Answers(answers))
    }

    /**
     * Sends a sudo password or secret value once (Q51). The value is taken out of [field] (empty afterwards) and lives
     * only in the frame being sent; nothing here keeps, logs or shows it. A tap before the prompt was seen leaves it.
     * With the app lock on, a fresh check comes first (Q61): the value stays in the field until it passes, and a
     * failed or cancelled check sends nothing.
     */
    fun answerSecret(key: String, field: SecretField) {
        if (!armed(key) || field.isEmpty) return
        afterCheck(key, AuthPurpose.SecretValue, stillWanted = { true }) {
            val value = field.take()
            if (value.isNotEmpty()) send(key, PromptAnswer.Value(value))
        }
    }

    /**
     * Runs [answer] for [key] after [freshAuth] passed (at once with the app lock off). The arming time was checked
     * at the tap; the check's own dialog then pauses the screen and restarts arming, so afterwards only this is
     * asked: the same prompt is still the one on screen, still answerable, nothing else on its way, and
     * [stillWanted] (e.g. still in the "Always allow" step).
     */
    private fun afterCheck(key: String, purpose: AuthPurpose, stillWanted: (ChatUiState) -> Boolean, answer: () -> Unit) {
        if (authorizing?.isActive == true || state.value.sheet.sending != null) return
        updateSheet { it.copy(authorizing = key) }
        authorizing = work.launch {
            val passed = try {
                freshAuth.confirm(purpose)
            } finally {
                updateSheet { it.copy(authorizing = null) }
            }
            val ui = state.value
            val prompt = ui.sheet.current(ui.feed.chat)
            val onScreen = prompt?.key == key && ui.sheet.open && ui.sheet.withdrawn == null && ui.answerable(prompt)
            if (passed && onScreen && stillWanted(ui)) answer()
        }
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
                subagent = ui.subagent.copy(shown = ui.subagent.shown?.copy(at = now, armed = false)),
            )
        }
        val next = state.value
        armSheet(next.sheet.shown)
        armComposer(next.composer.shown)
        armSubagent(next.subagent.shown)
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

    fun editDraft(text: String) {
        composer.edit(text)
        commands.onDraft(text)
    }

    /** Send: a draft that starts with `/` runs as a command (T15), anything else is a message. */
    fun send() = if (SlashMenu.isCommand(state.value.composer.draft)) commands.run() else composer.send()

    fun queue() = composer.queue()

    fun steer() = composer.steer()

    fun stop() = composer.stop()

    fun cancelQueued(admissionId: String) = composer.cancelQueued(admissionId)

    fun retrySend(id: String) = composer.retry(id)

    fun dismissFailed(id: String) = composer.dismiss(id)

    fun composerMessageShown() = composer.messageShown()

    // ── attachments (T16) ─────────────────────────────────────────────────────────────────────────

    /** Items the photo picker, the camera or the document picker returned ([uris] as strings). */
    fun attach(uris: List<String>, kind: PickKind) = composer.attach(uris, kind)

    fun removeAttachment(id: String) = composer.removeAttachment(id)

    /** Where the camera writes its picture (a `content://` URI of this app), null when it cannot. */
    fun cameraTarget(): String? = composer.cameraTarget()

    fun cameraUnavailable() = composer.cameraUnavailable()

    fun cameraCancelled(target: String) = composer.cameraCancelled(target)

    // ── slash commands (T15) ──────────────────────────────────────────────────────────────────────

    fun pickCommand(command: SlashCommand) = commands.pick(command)

    fun retryCommands() = commands.reload()

    fun dismissCommand(id: String) = commands.dismiss(id)

    // ── model and reasoning (T15) ─────────────────────────────────────────────────────────────────

    fun openModels() = models.open()

    fun closeModels() = models.close()

    fun retryModels() = models.reload()

    fun pickModel(group: ModelGroup, model: String) = models.pickModel(group, model)

    fun pickReasoning(effort: String) = models.pickReasoning(effort)

    fun confirmModel() = models.confirm()

    fun cancelModelConfirm() = models.cancelConfirm()

    fun modelMessageShown() = models.messageShown()

    // ── subagents (T17) ───────────────────────────────────────────────────────────────────────────

    /** The app left the screen (ON_STOP): the subagent reads sleep (T17b) and the process poll stops (TB1). */
    fun onStop() {
        subagents.onStop()
        processes.onVisible(false)
    }

    /** The chat is on screen (ON_START): the subagent reads resume with one immediate read; the process poll starts. */
    fun onStart() {
        subagents.onStart()
        processes.onVisible(true)
    }

    /** The chat screen left composition (another screen, the app lock): no process poll until it is back. */
    fun onScreenGone() = processes.onVisible(false)

    /** A child row of a `delegate_task` card or the status stack: its page opens, read-only, from the parent's own stream. */
    fun openSubagent(id: String) = subagents.open(id)

    fun closeSubagent() = subagents.close()

    /** Stop on the child's page; only an armed tap counts (no confirmation, as on the Desktop). */
    fun stopSubagent(id: String) = subagents.stop(id)

    fun subagentMessageShown() = subagents.messageShown()

    // ── background processes (TB1) ────────────────────────────────────────────────────────────────

    /** Stop on a process row: the confirmation dialog opens; nothing is sent. */
    fun askStopProcess(processId: String) = processes.askStop(processId)

    /** Cancel, back or outside the dialog: nothing is sent. */
    fun cancelStopProcess() = processes.cancelStop()

    /** The dialog's red Stop: `process.kill` for the process it shows. */
    fun confirmStopProcess() = processes.confirmStop()

    fun processResultShown() = processes.resultShown()

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
            changed.copy(
                model = ModelLogic.dropStale(changed.model, changed.feed.chat),
                sheet = sheet,
                composer = ComposerLogic.track(changed.composer.copy(outbox = outbox), key, now),
                subagent = SubagentLogic.track(changed.subagent, changed.feed.chat, now),
            )
        }
        armSheet(next.sheet.shown)
        armComposer(next.composer.shown)
        armSubagent(next.subagent.shown)
        subagents.onState(next)
        processes.onState(next)
    }

    private fun armSubagent(shown: SubagentShown?) {
        if (shown == null || shown.armed || shown == subagentArmingFor) return
        subagentArmingFor = shown
        subagentArming?.cancel()
        subagentArming = work.launch {
            delay(SubagentLogic.ARM_MS)
            state.update { ui -> if (ui.subagent.shown == shown) ui.copy(subagent = ui.subagent.copy(shown = shown.copy(armed = true))) else ui }
        }
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
