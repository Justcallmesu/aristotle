package io.github.justcallmesu.aristotle.ui.chat

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.alpha
import io.github.justcallmesu.aristotle.data.ChatQuoteTiming
import io.github.justcallmesu.aristotle.data.Quote
import io.github.justcallmesu.aristotle.data.QuoteMotion
import io.github.justcallmesu.aristotle.data.QuoteSource
import io.github.justcallmesu.aristotle.ui.components.reducedMotion
import io.github.justcallmesu.aristotle.ui.quote.InlineQuote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.chat.ApprovalChoice
import io.github.justcallmesu.aristotle.data.chat.ChatItem
import io.github.justcallmesu.aristotle.data.chat.ClarifyAnswer
import io.github.justcallmesu.aristotle.data.chat.ChatState
import io.github.justcallmesu.aristotle.data.chat.ModelGroup
import io.github.justcallmesu.aristotle.data.chat.PickKind
import io.github.justcallmesu.aristotle.data.chat.ReasoningLevels
import io.github.justcallmesu.aristotle.data.chat.SlashCommand
import io.github.justcallmesu.aristotle.data.chat.ToolStatus
import io.github.justcallmesu.aristotle.ui.components.RunningSpinner
import io.github.justcallmesu.aristotle.ui.theme.AristotleTextStyles
import io.github.justcallmesu.aristotle.ui.theme.ButtonShape
import io.github.justcallmesu.aristotle.ui.theme.Sizes
import io.github.justcallmesu.aristotle.ui.theme.Spacing
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    fallbackTitle: String?,
    onBack: () -> Unit,
    focusComposer: Boolean = false,
    /** The opened row's profile, shown until the session says its own (TU1 C4). */
    fallbackProfile: String? = null,
    /** The quotes shown while the history loads (Q59); null: only the quiet line. */
    quotes: QuoteSource? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onStart() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onStop() }
    // Leaving the screen without ON_STOP (the app lock covers it): the process poll stops too (TB1).
    DisposableEffect(viewModel) { onDispose { viewModel.onScreenGone() } }
    val actions = remember(viewModel) { ViewModelPromptActions(viewModel) }
    val composer = remember(viewModel) { ViewModelComposerActions(viewModel) }
    val models = remember(viewModel) { ViewModelModelActions(viewModel) }
    val subagents = remember(viewModel) { ViewModelSubagentActions(viewModel) }
    val background = remember(viewModel) { ViewModelBackgroundActions(viewModel) }
    ChatContentView(
        state = state,
        fallbackTitle = fallbackTitle,
        onBack = onBack,
        onRetry = viewModel::retry,
        onOpenSheet = viewModel::openSheet,
        onMessageShown = viewModel::messageShown,
        actions = actions,
        composer = composer,
        echoes = EchoActions(
            onRetry = viewModel::retrySend,
            onDismiss = viewModel::dismissFailed,
            onMessageShown = viewModel::composerMessageShown,
            onDismissCommand = viewModel::dismissCommand,
        ),
        models = models,
        focusComposer = focusComposer,
        subagents = subagents,
        onOpenSubagent = viewModel::openSubagent,
        fallbackProfile = fallbackProfile,
        quotes = quotes,
        background = background,
    )
}

private class ViewModelBackgroundActions(private val vm: ChatViewModel) : BackgroundActions {
    override fun askStop(processId: String) = vm.askStopProcess(processId)
    override fun confirmStop() = vm.confirmStopProcess()
    override fun cancelStop() = vm.cancelStopProcess()
    override fun resultShown() = vm.processResultShown()
}

private class ViewModelSubagentActions(private val vm: ChatViewModel) : SubagentActions {
    override fun close() = vm.closeSubagent()
    override fun stop(id: String) = vm.stopSubagent(id)
    override fun messageShown() = vm.subagentMessageShown()
}

private class ViewModelModelActions(private val vm: ChatViewModel) : ModelActions {
    override fun open() = vm.openModels()
    override fun close() = vm.closeModels()
    override fun retry() = vm.retryModels()
    override fun pickModel(group: ModelGroup, model: String) = vm.pickModel(group, model)
    override fun pickReasoning(effort: String) = vm.pickReasoning(effort)
    override fun confirm() = vm.confirmModel()
    override fun cancelConfirm() = vm.cancelModelConfirm()
    override fun messageShown() = vm.modelMessageShown()
}

private class ViewModelComposerActions(private val vm: ChatViewModel) : ComposerActions {
    override fun edit(text: String) = vm.editDraft(text)
    override fun send() = vm.send()
    override fun queue() = vm.queue()
    override fun steer() = vm.steer()
    override fun stop() = vm.stop()
    override fun cancelQueued(admissionId: String) = vm.cancelQueued(admissionId)
    override fun pickCommand(command: SlashCommand) = vm.pickCommand(command)
    override fun retryCommands() = vm.retryCommands()
    override fun attach(uris: List<String>, kind: PickKind) = vm.attach(uris, kind)
    override fun removeAttachment(id: String) = vm.removeAttachment(id)
    override fun cameraTarget(): String? = vm.cameraTarget()
    override fun cameraUnavailable() = vm.cameraUnavailable()
    override fun cameraCancelled(target: String) = vm.cameraCancelled(target)
}

/** What a sent message's row can do: Retry, remove a failed one, clear the composer's snackbar, remove a command's row. */
class EchoActions(
    val onRetry: (String) -> Unit,
    val onDismiss: (String) -> Unit,
    val onMessageShown: () -> Unit,
    val onDismissCommand: (String) -> Unit = {},
)

private class ViewModelPromptActions(private val vm: ChatViewModel) : PromptActions {
    override fun hide() = vm.hideSheet()
    override fun closeWithdrawn() = vm.closeWithdrawn()
    override fun choose(key: String, choice: ApprovalChoice) = vm.choose(key, choice)
    override fun confirmAlways(key: String) = vm.confirmAlways(key)
    override fun cancelAlways() = vm.cancelAlways()
    override fun answerClarify(key: String, answers: Map<String, ClarifyAnswer>) = vm.answerClarify(key, answers)
    override fun answerSecret(key: String, field: SecretField) = vm.answerSecret(key, field)
    override fun declineSecret(key: String, field: SecretField) = vm.declineSecret(key, field)
}

/**
 * design-notes §5.2 chat (v2 board): top bar with the session, the transcript with this phone's own messages until
 * Hermes shows them, the composer (T13; it holds the waiting chip and the model chip) and the prompt sheet (T12).
 * Edge-to-edge: the composer carries the bottom insets (navigation bar, keyboard).
 */
@Composable
fun ChatContentView(
    state: ChatUiState,
    fallbackTitle: String?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onOpenSheet: () -> Unit,
    onMessageShown: () -> Unit,
    actions: PromptActions,
    composer: ComposerActions,
    echoes: EchoActions,
    /** The top bar's model and reasoning switch (T15). */
    models: ModelActions,
    /** A session just created (T14): the composer takes focus once it shows. */
    focusComposer: Boolean = false,
    /** The opened subagent's page (T17); null: no page. */
    subagents: SubagentActions? = null,
    /** A `delegate_task` child row was tapped (T17); null: rows do not open. */
    onOpenSubagent: ((String) -> Unit)? = null,
    /** The opened row's profile, shown in the top bar until the session says its own (TU1 C4). */
    fallbackProfile: String? = null,
    /** The quotes shown while the history loads (Q59); null: only the quiet line. */
    quotes: QuoteSource? = null,
    /** The status stack's process rows and their Stop dialog (TB1); null: no stack. */
    background: BackgroundActions? = null,
) {
    val feed = state.feed
    val chat = feed.chat
    val live = ChatLive.of(feed, state.link)
    val snackbar = remember { SnackbarHostState() }
    val message = state.sheet.message
    val messageText = message?.let { promptMessage(it) }
    LaunchedEffect(message) {
        if (messageText != null) {
            snackbar.showSnackbar(messageText)
            onMessageShown()
        }
    }
    val composerMessage = state.composer.message
    val composerText = composerMessage?.let { composerMessage(it) }
    LaunchedEffect(composerMessage) {
        if (composerText != null) {
            snackbar.showSnackbar(composerText)
            echoes.onMessageShown()
        }
    }
    val modelMessage = state.model.message
    val modelText = modelMessage?.let { modelMessage(it) }
    LaunchedEffect(modelMessage) {
        if (modelText != null) {
            snackbar.showSnackbar(modelText)
            models.messageShown()
        }
    }
    val killResult = state.background.result
    val processResultText = killResult?.let { processResult(it) }
    LaunchedEffect(killResult) {
        if (processResultText != null) {
            snackbar.showSnackbar(processResultText)
            background?.resultShown()
        }
    }
    // T17: an opened child covers the chat, which stays composed underneath (its scroll position survives) but is
    // hidden from accessibility services while covered.
    val child = state.subagent.open?.let { chat.subagents.byId(it) }?.takeIf { subagents != null }
    val covered = if (child != null) Modifier.clearAndSetSemantics {} else Modifier
    // Q80a: long-press actions. The host is rebuilt only when Steer's availability flips.
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sheetFor by remember { mutableStateOf<MessageTarget?>(null) }
    var selecting by remember { mutableStateOf<MessageTarget?>(null) }
    var draftEdit by remember { mutableStateOf<DraftEdit?>(null) }
    val steerAllowed = MessageActions.steerAllowed(state.composer)
    val copiedText = stringResource(R.string.msg_copied)
    val actionHost = remember(steerAllowed, context, snackbar, scope, copiedText) {
        val copied: () -> Unit = { scope.launch { snackbar.showSnackbar(copiedText) } }
        MessageActionHost(
            steerAllowed = steerAllowed,
            open = { sheetFor = it },
            copied = copied,
            perform = { action, target ->
                when (action) {
                    MessageAction.Copy -> if (!copyToClipboard(context, target.text)) copied()
                    MessageAction.Share -> shareText(context, target.text)
                    MessageAction.SelectText -> selecting = target
                    MessageAction.Quote -> draftEdit = DraftEdit(System.currentTimeMillis(), MessageActions.quote(target.text), reply = true)
                    // Puts the words in the draft and nothing else: Steer stays the user's tap.
                    MessageAction.Steer -> if (steerAllowed) draftEdit = DraftEdit(System.currentTimeMillis(), target.text, reply = false)
                }
            },
        )
    }
    CompositionLocalProvider(LocalMessageActions provides actionHost) {
        Surface(modifier = Modifier.fillMaxSize().then(covered), color = MaterialTheme.colorScheme.surface) {
            // Top and sides: the top bar and banner stay clear of a landscape cut-out (T11 review NIT).
            Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))) {
                val sides = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                // A prompt waits for this phone's answer (not while that answer is on its way, T12b).
                val waitingFor = ChatSubtitle.waitingFor(state.sheet.current(chat))
                ChatTopBar(chat, state.link, live, waitingFor, fallbackTitle, fallbackProfile, onBack, modifier = sides)
                if (ChatLive.reattachFailed(feed)) ReattachBanner(onRetry, sides)
                // Without the composer (still loading, load failed) this area reaches the bottom edge itself.
                val edge = if (feed.loaded) WindowInsetsSides.Horizontal else WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal
                val insets = WindowInsets.safeDrawing.only(edge).asPaddingValues()
                // The stack sits in the room left after the top bar and the composer, so it is measured against that (TB1b).
                Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        val outbox = state.composer.outbox
                        val runs = state.slash.runs
                        when {
                            feed.loaded && chat.items.isEmpty() && outbox.isEmpty() && runs.isEmpty() && !chat.running && chat.prompts.isEmpty() -> QuietLine(stringResource(R.string.chat_empty))
                            feed.loaded -> Transcript(
                                chat,
                                live = live,
                                composer = state.composer,
                                runs = runs,
                                insets = insets,
                                echoes = echoes,
                                onOpenSubagent = onOpenSubagent,
                            )
                            feed.problem != null -> LoadFailed(feed.problem, onRetry)
                            else -> QuietLine(stringResource(R.string.chat_loading))
                        }
                        if (quotes != null && feed.problem == null) LoadingQuote(quotes, feed.loaded)
                        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(insets))
                    }
                    if (feed.loaded && background != null) {
                        StatusStack(
                            sessionId = chat.sessionId,
                            children = chat.subagents.items,
                            background = state.background,
                            live = live,
                            onOpenSubagent = onOpenSubagent,
                            onStop = background::askStop,
                            modifier = sides,
                        )
                    }
                }
                if (feed.loaded) {
                    Composer(
                        state.composer,
                        running = chat.running,
                        queued = ComposerLogic.queuedRows(feed, state.composer.outbox),
                        actions = composer,
                        slash = state.slash,
                        focusOnStart = focusComposer,
                        // design-notes §5.2: the model and effort moved from the top bar's subtitle into the composer's chip.
                        // TR MINOR-1: without a model name the chip still shows ("Model"), so the sheet stays reachable.
                        modelChip = ComposerLogic.chipLabel(
                            chat.model,
                            ReasoningLevels.current(chat.reasoningEffort)?.let { reasoningLabel(it) },
                            fallback = stringResource(R.string.composer_model_fallback),
                        ),
                        onOpenModels = models::open,
                        onOpenPrompt = onOpenSheet,
                        waitingFor = ChatSubtitle.waitingFor(state.sheet.current(chat) ?: chat.prompts.firstOrNull()) ?: WaitingFor.Approval,
                    draftEdit = draftEdit,
                    )
                }
            }
        }
    }
    sheetFor?.let { target ->
        MessageActionSheet(target, steerAllowed, onDismiss = { sheetFor = null }) { action ->
            sheetFor = null
            actionHost.perform(action, target)
        }
    }
    selecting?.let { SelectTextScreen(it, onClose = { selecting = null }) }
    if (child != null && subagents != null) SubagentPage(child, state.subagent, live, subagents)
    if (state.sheet.visible(chat)) {
        val context = listOfNotNull(chat.title ?: fallbackTitle, chat.profileName).joinToString(" · ")
        val prompt = state.sheet.current(chat).takeIf { state.sheet.open }
        PromptSheet(prompt, state.sheet, context, actions, answerable = state.answerable(prompt))
    }
    if (state.model.open) ModelSheet(state.model, chat, models)
    state.model.confirm?.let { ModelConfirmDialog(it, models) }
    if (background != null) state.background.confirm?.let { StopProcessDialog(it, background) }
}

/**
 * Q59: after [ChatQuoteTiming.DELAY_MILLIS] of loading a quote covers the transcript area, stays at least
 * [ChatQuoteTiming.MIN_SHOW_MILLIS], then fades out over the transcript. A history that is quicker never sees it.
 */
@Composable
private fun LoadingQuote(quotes: QuoteSource, loaded: Boolean) {
    val reduced = reducedMotion()
    var quote by remember { mutableStateOf<Quote?>(null) }
    var covering by remember { mutableStateOf(false) }
    val alpha = remember { Animatable(1f) }
    val loadedNow by rememberUpdatedState(loaded)
    LaunchedEffect(Unit) {
        delay(ChatQuoteTiming.delayBeforeShow(0))
        if (loadedNow) return@LaunchedEffect
        val picked = withContext(Dispatchers.Default) { quotes.nextInline() } ?: return@LaunchedEffect
        if (loadedNow) return@LaunchedEffect
        quote = picked
        val shownAt = SystemClock.elapsedRealtime()
        covering = true
        snapshotFlow { loadedNow }.first { it }
        delay(ChatQuoteTiming.holdRemaining(SystemClock.elapsedRealtime() - shownAt))
        alpha.animateTo(0f, tween(QuoteMotion.durationMillis(reduced)))
        covering = false
    }
    val shown = quote
    if (covering && shown != null) InlineQuote(shown, reduced, Modifier.alpha(alpha.value))
}

@Composable
private fun modelMessage(message: ModelMessage): String = when (message) {
    is ModelMessage.Deferred -> stringResource(R.string.model_deferred, reasoningLabelOrModel(message.value))
    ModelMessage.Changed -> stringResource(R.string.model_changed)
    is ModelMessage.Refused -> stringResource(R.string.model_refused, message.reason)
    ModelMessage.NoAnswer -> stringResource(R.string.model_no_answer)
    ModelMessage.NotReady -> stringResource(R.string.model_not_ready)
    ModelMessage.NoToken -> stringResource(R.string.model_no_token)
}

/** A deferred pick names a model or a reasoning level; a level reads as its label. */
@Composable
private fun reasoningLabelOrModel(value: String): String =
    if (value == ReasoningLevels.OFF || value in ReasoningLevels.LEVELS) reasoningLabel(value) else value

@Composable
private fun composerMessage(message: ComposerMessage): String = when (message) {
    is ComposerMessage.StopFailed -> stringResource(R.string.composer_stop_failed, message.reason)
    ComposerMessage.StopUnavailable -> stringResource(R.string.composer_stop_unavailable)
    is ComposerMessage.CancelFailed -> stringResource(R.string.composer_cancel_failed, message.reason)
    is ComposerMessage.TooManyAttachments -> pluralStringResource(R.plurals.attach_too_many, message.limit, message.limit)
    ComposerMessage.CameraUnavailable -> stringResource(R.string.attach_camera_unavailable)
}

@Composable
private fun promptMessage(message: PromptMessage): String = when (message) {
    PromptMessage.AnsweredFirst -> stringResource(R.string.prompt_answered_first)
    PromptMessage.NotSent -> stringResource(R.string.prompt_not_sent)
    is PromptMessage.Refused -> stringResource(R.string.prompt_refused, message.reason)
}

/**
 * design-notes §5.2 / §5.6: back, the session's title, and one subtitle line (where it stands, then the profile). Not a
 * control: the model and reasoning switch is the composer's chip, and a chat keeps the profile it was opened in (T14).
 */
@Composable
private fun ChatTopBar(
    chat: ChatState,
    link: LinkState,
    live: Boolean,
    waitingFor: WaitingFor?,
    fallbackTitle: String?,
    fallbackProfile: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = Sizes.topBarHeight).padding(horizontal = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(painter = painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.nav_back))
        }
        Column(modifier = Modifier.weight(1f).heightIn(min = Sizes.minTouchTarget).padding(horizontal = Spacing.xs), verticalArrangement = Arrangement.Center) {
            Text(
                text = chat.title ?: fallbackTitle ?: stringResource(R.string.session_untitled),
                style = AristotleTextStyles.chatTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            Subtitle(chat, link, fallbackProfile, live = live, waitingFor = waitingFor)
        }
    }
}

/**
 * One text, so every " · " is the same (TU1 C3): the lead ([ChatSubtitle.lead]) with its icon, then the 16 dp profile
 * mark and the profile's name. "Running · step N" in verdigris with the spinner, only while [live]; "Needs your
 * approval" in red with the hand; a link that is not plain online names its state (cloud-off when offline). The line
 * is always there, empty until the chat loads, so the bar does not move when it fills in (C4).
 */
@Composable
private fun Subtitle(chat: ChatState, link: LinkState, fallbackProfile: String?, live: Boolean, waitingFor: WaitingFor?) {
    val linkText = linkLabel(link)
    val lead = ChatSubtitle.lead(linkDown = linkText != null, live = live, waiting = waitingFor != null, running = chat.running)
    val leadText = when (lead) {
        SubtitleLead.Link -> linkText
        // TR MINOR-4: an approval needs approving; a question, sudo or secret needs an answer.
        SubtitleLead.Waiting -> stringResource(if (waitingFor == WaitingFor.Answer) R.string.chat_needs_answer else R.string.chat_needs_approval)
        SubtitleLead.Running -> chat.step?.let { stringResource(R.string.chat_running_step, it) } ?: stringResource(R.string.session_running)
        SubtitleLead.None -> null
    }
    val profile = (chat.profileName ?: fallbackProfile)?.takeIf { it.isNotBlank() }
    val leadSpan = when (lead) {
        SubtitleLead.Running -> AristotleTextStyles.liveStatusSmall.toSpanStyle().copy(color = MaterialTheme.colorScheme.tertiary)
        // Red on `surface` (5.7:1, design.md §1); 600 like the live lead.
        SubtitleLead.Waiting -> AristotleTextStyles.liveStatusSmall.toSpanStyle().copy(color = MaterialTheme.colorScheme.error)
        SubtitleLead.Link, SubtitleLead.None -> SpanStyle()
    }
    val text = remember(leadText, profile, leadSpan) {
        buildAnnotatedString {
            leadText?.let { withStyle(leadSpan) { append(it) } }
            if (profile != null) {
                if (leadText != null) append(StatusText.SEPARATOR)
                // Decorative (the name follows): an invisible stand-in, so TalkBack does not read the letter.
                appendInlineContent(PROFILE_MARK_ID, MARK_STAND_IN)
                append(' ')
                append(profile)
            }
        }
    }
    val markSize = MaterialTheme.typography.bodySmall.lineHeight
    val inline = remember(profile, markSize) {
        if (profile == null) {
            emptyMap()
        } else {
            mapOf(
                PROFILE_MARK_ID to InlineTextContent(Placeholder(markSize, markSize, PlaceholderVerticalAlign.TextCenter)) {
                    ProfileMark(profile)
                },
            )
        }
    }
    Row(modifier = Modifier.heightIn(min = Sizes.chipIcon), verticalAlignment = Alignment.CenterVertically) {
        when (lead) {
            SubtitleLead.Running -> RunningSpinner()
            SubtitleLead.Waiting -> LeadIcon(R.drawable.ic_pan_tool, MaterialTheme.colorScheme.error)
            SubtitleLead.Link -> if (link is LinkState.Offline) LeadIcon(R.drawable.ic_cloud_off, MaterialTheme.colorScheme.onSurfaceVariant)
            SubtitleLead.None -> Unit
        }
        if (lead == SubtitleLead.Running || lead == SubtitleLead.Waiting || (lead == SubtitleLead.Link && link is LinkState.Offline)) {
            Spacer(Modifier.width(Spacing.xs))
        }
        Text(
            text = text,
            inlineContent = inline,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LeadIcon(res: Int, tint: Color) {
    Icon(painter = painterResource(res), contentDescription = null, tint = tint, modifier = Modifier.size(Sizes.lineIcon))
}

/**
 * design-notes §5.6: the profile's initial on a 16 dp Soft square (`extraSmall`, `secondaryContainer`), Literata 600
 * at `labelSmall` size, so the chat echoes the avatar tapped on Home. Decorative: the name beside it is the text.
 */
@Composable
private fun ProfileMark(name: String) {
    val style = MaterialTheme.typography.labelSmall.copy(
        fontFamily = AristotleTextStyles.wordmark.fontFamily,
        fontWeight = FontWeight.W600,
        lineHeight = MaterialTheme.typography.labelSmall.fontSize,
        letterSpacing = 0.sp,
    )
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxSize().clearAndSetSemantics { },
    ) {
        Box(contentAlignment = Alignment.Center) { Text(text = ChatSubtitle.initial(name), style = style, maxLines = 1) }
    }
}

private const val PROFILE_MARK_ID = "aristotle.profileMark"
private const val MARK_STAND_IN = "\u2060"

/** The link state when it is not plain online; null: online. */
@Composable
private fun linkLabel(link: LinkState): String? = when (link) {
    is LinkState.Online, LinkState.Revoked -> null
    LinkState.Idle, LinkState.Connecting -> stringResource(R.string.chat_link_connecting)
    is LinkState.Offline -> stringResource(if (link.trying) R.string.chat_link_reconnecting else R.string.chat_link_offline)
    is LinkState.HermesDown -> stringResource(R.string.chat_link_hermes_down)
    is LinkState.HermesUpdated -> stringResource(R.string.chat_link_hermes_updated)
    LinkState.Stopped -> stringResource(R.string.chat_link_stopped)
}

/**
 * The message list. It follows new content while the user is at the bottom; scrolling up stops that and
 * shows "Jump to latest". Stable keys: a delta recomposes only the last row.
 */
@Composable
private fun Transcript(
    chat: ChatState,
    live: Boolean,
    composer: ComposerState,
    runs: List<CommandRun>,
    insets: PaddingValues,
    echoes: EchoActions,
    onOpenSubagent: ((String) -> Unit)?,
) {
    val outbox = composer.outbox
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val follow = remember { mutableStateOf(true) }
    val folds = rememberFolds()
    val lastFailed = remember(chat.items) { chat.items.lastOrNull { it is ChatItem.Tool && it.status == ToolStatus.Failed }?.key }
    val rows = remember(chat.items) { ToolFolding.rows(chat.items) }
    val thinking = live && chat.running && chat.thinking && !chat.streaming
    val userScroll = remember(listState) { UserScrollUp(listState, follow) }
    LaunchedEffect(listState) {
        // At the bottom: follow again. Content grew under a follower (a new chunk, a Markdown block that finished
        // parsing, a row that unfolded): keep the end in view.
        snapshotFlow { listState.canScrollForward }.collect { more ->
            if (!more) follow.value = true else if (follow.value) listState.scrollToEnd()
        }
    }
    val tail = remember(outbox) { ComposerLogic.tail(outbox) }
    val anchored = remember(outbox) { ComposerLogic.anchored(outbox) }
    LaunchedEffect(chat.items.size, chat.items.lastOrNull(), thinking, outbox, runs) {
        if (follow.value) listState.scrollToEnd()
    }
    val direction = LocalLayoutDirection.current
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().nestedScroll(userScroll),
            contentPadding = PaddingValues(
                start = Spacing.listGutter + insets.calculateStartPadding(direction),
                end = Spacing.listGutter + insets.calculateEndPadding(direction),
                top = Spacing.s,
                bottom = Spacing.l + insets.calculateBottomPadding(),
            ),
        ) {
            // Q67: each row's top gap is chosen by the row before it, so the list has no fixed spacing.
            val rhythm = Rhythm()
            echoItems(anchored[null].orEmpty(), echoes, rhythm)
            for (row in rows) {
                val gap = rhythm.next(ChatRhythm.kindOf(row))
                val contentType: Any = if (row is TranscriptRow.Single) row.item::class else TranscriptRow.Folded::class
                item(key = row.key, contentType = contentType) {
                    Box(modifier = Modifier.padding(top = gap)) {
                        TranscriptRowView(row, folds, lastFailed, live, attachmentsOf = { ComposerLogic.userAttachments(it, composer) }, onOpenSubagent = onOpenSubagent)
                    }
                }
                val members = if (row is TranscriptRow.Folded) row.items else listOf((row as TranscriptRow.Single).item)
                members.forEach { member -> anchored[member.key]?.let { echoItems(it, echoes, rhythm) } }
            }
            echoItems(tail, echoes, rhythm)
            for (run in runs) {
                val gap = rhythm.next(RowKind.User)
                item(key = "cmd-${run.id}", contentType = "command") { Box(modifier = Modifier.padding(top = gap)) { CommandRunRow(run, echoes.onDismissCommand) } }
            }
            if (thinking) {
                val gap = rhythm.next(RowKind.Work)
                item(key = "thinking") { Box(modifier = Modifier.padding(top = gap)) { ThinkingLine() } }
            }
        }
        if (!follow.value && listState.canScrollForward) {
            FilledTonalButton(
                onClick = {
                    follow.value = true
                    scope.launch { listState.scrollToEnd() }
                },
                shape = ButtonShape,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = Spacing.l + insets.calculateBottomPadding()),
            ) {
                Icon(painter = painterResource(R.drawable.ic_arrow_downward), contentDescription = null, modifier = Modifier.size(Sizes.chipIcon))
                Spacer(Modifier.size(Spacing.s))
                Text(stringResource(R.string.chat_jump_latest))
            }
        }
    }
}

/** This phone's own messages (T13 optimistic echo); keyed apart from Hermes' rows. They are user bubbles. */
private fun LazyListScope.echoItems(items: List<Outgoing>, echoes: EchoActions, rhythm: Rhythm) {
    for (outgoing in items) {
        val gap = rhythm.next(RowKind.User)
        item(key = "echo-${outgoing.id}", contentType = "echo") {
            Box(modifier = Modifier.padding(top = gap)) { EchoRow(outgoing, echoes.onRetry, echoes.onDismiss) }
        }
    }
}

/** Walks the rows in list order and hands out each one's top gap ([ChatRhythm]). */
internal class Rhythm {
    private var previous: RowKind? = null

    fun next(kind: RowKind): Dp {
        val gap = ChatRhythm.gap(previous, kind)
        previous = kind
        return gap.size
    }
}

/** A drag or fling towards older messages (only the user's, programmatic scrolls do not pass here). */
private class UserScrollUp(private val list: LazyListState, private val follow: MutableState<Boolean>) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (available.y > 0f && list.canScrollBackward) follow.value = false
        return Offset.Zero
    }
}

/** To the very end of the last item (a long streaming message is taller than the screen). */
private suspend fun LazyListState.scrollToEnd() {
    val last = layoutInfo.totalItemsCount - 1
    if (last < 0) return
    scrollToItem(last)
    scrollBy(END_SCROLL_PX)
}

private const val END_SCROLL_PX = 1_000_000f

@Composable
internal fun rememberFolds(): FoldState {
    val opened = rememberSaveable(saver = foldSaver) { mutableStateMapOf() }
    return remember(opened) {
        object : FoldState {
            override fun isOpen(key: String, default: Boolean): Boolean = opened[key] ?: default
            override fun toggle(key: String, default: Boolean) {
                opened[key] = !(opened[key] ?: default)
            }
        }
    }
}

private val foldSaver = listSaver<SnapshotStateMap<String, Boolean>, String>(
    save = { map -> map.flatMap { (key, open) -> listOf(key, open.toString()) } },
    restore = { flat -> mutableStateMapOf<String, Boolean>().apply { flat.chunked(2).forEach { (key, open) -> put(key, open.toBoolean()) } } },
)

@Composable
private fun QuietLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.listGutter, vertical = Spacing.xxl),
    )
}

/**
 * design.md §5 banner under the top bar: Hermes refused (or did not answer) the resume on this socket, so the
 * transcript below is what was loaded and no longer follows the session. Retry subscribes again.
 */
@Composable
private fun ReattachBanner(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.m, vertical = Spacing.xs),
    ) {
        Column {
            Row(modifier = Modifier.padding(start = Spacing.l, end = Spacing.m, top = Spacing.m)) {
                Icon(
                    painter = painterResource(R.drawable.ic_error),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(Sizes.fieldIcon),
                )
                Column(modifier = Modifier.padding(start = Spacing.m).weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(
                        text = stringResource(R.string.chat_reattach_failed_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    Text(
                        text = stringResource(R.string.chat_reattach_failed_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(end = Spacing.xs), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onRetry, shape = ButtonShape) { Text(stringResource(R.string.chat_reattach_retry)) }
            }
        }
    }
}

/** design.md §5 connection-page form: left-aligned headline, one paragraph, mono details, one button. */
@Composable
private fun LoadFailed(details: String, onRetry: () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = Spacing.xxl, vertical = Spacing.xxl), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        Text(text = stringResource(R.string.chat_load_failed_title), style = MaterialTheme.typography.headlineSmall)
        Text(text = stringResource(R.string.chat_load_failed_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = details, style = AristotleTextStyles.details, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(Spacing.s))
        Button(onClick = onRetry, shape = ButtonShape) { Text(stringResource(R.string.conn_retry_now)) }
    }
}
