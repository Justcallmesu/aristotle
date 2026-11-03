package io.github.justcallmesu.aristotle.ui.cron

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.justcallmesu.aristotle.data.InboundFrame
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.ProfileLink
import io.github.justcallmesu.aristotle.data.RelayLink
import io.github.justcallmesu.aristotle.data.RpcException
import io.github.justcallmesu.aristotle.data.chat.ComposerCall
import io.github.justcallmesu.aristotle.data.cron.CronAnswer
import io.github.justcallmesu.aristotle.data.cron.CronDraft
import io.github.justcallmesu.aristotle.data.cron.CronFrames
import io.github.justcallmesu.aristotle.data.cron.CronJob
import io.github.justcallmesu.aristotle.data.cron.CronSchedule
import io.github.justcallmesu.aristotle.data.cron.SchedulePlan
import io.github.justcallmesu.aristotle.ui.chat.PromptSheetLogic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A one-off line (snackbar) about a cron control. Hermes' reasons are shown as Hermes wrote them. */
sealed interface CronMessage {
    /** Hermes answered no (`success: false` / a JSON-RPC error); nothing is retried. */
    data class Refused(val reason: String) : CronMessage

    /** No socket took the call: Hermes never saw it. */
    data object NotSent : CronMessage

    /** The call went out but no answer came (timeout, or the link dropped after it): it may have happened. */
    data object NoAnswer : CronMessage
    data class Created(val name: String) : CronMessage
    data class Deleted(val name: String) : CronMessage
}

/** The create form; [error] is Hermes' refusal of the last try (its own schedule check included), shown as it is. */
data class CronForm(
    val name: String = "",
    val prompt: String = "",
    val plan: SchedulePlan = SchedulePlan(),
    val continuity: Boolean = false,
    val sending: Boolean = false,
    val error: String? = null,
) {
    /** The schedule string the form sends. */
    val schedule: String get() = CronSchedule.compose(plan)

    /** Name, prompt and schedule are there (the Desktop's Create condition, `hermes-bots/cron.tsx:1126`). */
    val complete: Boolean get() = name.isNotBlank() && prompt.isNotBlank() && schedule.isNotBlank()
}

/**
 * [changeWindowMillis]: one list refetch per window after `cron.changed` (it fires on edits and on every scheduler
 * tick, `tui_gateway/change_watcher.py:375-380`), as T14b does for `sessions.changed`. [pollMillis]: the Desktop's
 * refresh while the pane is shown (`refetchInterval: 20000`, `hermes-bots/cron.tsx:189`); `cron.changed` watches only
 * the launch profile's store (`change_watcher.py:57-58`), so a sibling profile needs the poll.
 */
data class CronTimings(val changeWindowMillis: Long = 2_000, val pollMillis: Long = 20_000)

data class CronUiState(
    val link: LinkState = LinkState.Connecting,
    /** The last list; null until the first arrives. Kept while offline. */
    val jobs: List<CronJob>? = null,
    /** `cron.manage list` failed (link up): Hermes' reason. */
    val problem: String? = null,
    /** Hermes' own warning that no gateway runs to fire jobs (`gateway_running: false`). */
    val warning: String? = null,
    val refreshing: Boolean = false,
    /** The job whose detail page is open. */
    val open: String? = null,
    /** Jobs with a change on the way. */
    val busy: Set<String> = emptySet(),
    /** The job the delete confirmation is about. */
    val confirmDelete: String? = null,
    val form: CronForm? = null,
    /** Control keys ([CronArm]) that have meant the same thing for [CronArm.ARM_MS]; their buttons are on. */
    val armed: Set<String> = emptySet(),
    val message: CronMessage? = null,
) {
    val online: Boolean get() = link is LinkState.Online
    val openJob: CronJob? get() = open?.let { id -> jobs?.firstOrNull { it.id == id } }

    fun job(id: String): CronJob? = jobs?.firstOrNull { it.id == id }

    fun canToggle(job: CronJob): Boolean = online && job.id !in busy && CronArm.toggleKey(job) in armed
}

/**
 * The tap guard (T12b): a control counts only once what it means has been on screen for [ARM_MS] unchanged. A
 * switch's key carries the job's state, so a refresh that flips it re-arms the switch. Pure.
 */
object CronArm {
    const val ARM_MS = PromptSheetLogic.ARM_MS
    const val CREATE = "create"

    fun toggleKey(job: CronJob) = "toggle:${job.id}:${job.active}"

    fun deleteKey(jobId: String) = "delete:$jobId"

    fun confirmKey(jobId: String) = "confirm:$jobId"

    /** The controls on screen in [state]: the form covers the rest, the confirmation its page, a page the list. */
    fun keys(state: CronUiState): Set<String> {
        if (!state.online) return emptySet()
        state.form?.let { return setOf(CREATE) }
        state.confirmDelete?.let { return setOf(confirmKey(it)) }
        state.openJob?.let { return setOf(toggleKey(it), deleteKey(it.id)) }
        return state.jobs.orEmpty().mapTo(HashSet(), ::toggleKey)
    }

    /** Since when each of [keys] is on screen: kept for those still there, now for new ones. */
    fun track(shown: Map<String, Long>, keys: Set<String>, now: Long): Map<String, Long> = keys.associateWith { shown[it] ?: now }

    fun armed(shown: Map<String, Long>, now: Long): Set<String> = shown.filterValues { now - it >= ARM_MS }.keys
}

/**
 * Hermes' scheduled jobs of one profile (T18), over the relay the way the Desktop's Bot Mode pane does it: `cron.manage`
 * list / pause / resume / add / remove (`apps/desktop/src/plugins/hermes-bots/cron.tsx`). Lists on every new relay
 * socket, after `cron.changed` (coalesced, [CronTimings.changeWindowMillis]), every [CronTimings.pollMillis] while
 * the screen is resumed, and after each own change. Each change is sent once: a refusal or a lost answer is said,
 * never retried. Delete asks first (the Desktop's Cron page does, `apps/desktop/src/app/cron/index.tsx:826-840`).
 * [profile]: null = the launch profile. [clock] is the arming clock.
 */
class CronViewModel(
    base: RelayLink,
    val profile: String?,
    private val timings: CronTimings = CronTimings(),
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    scope: CoroutineScope? = null,
) : ViewModel() {
    private val work: CoroutineScope = scope ?: viewModelScope
    private val link: RelayLink = ProfileLink(base, profile)
    private val state = MutableStateFlow(CronUiState())
    val uiState: StateFlow<CronUiState> = state.asStateFlow()

    private val reloads = MutableStateFlow(0)
    private var shown: Map<String, Long> = emptyMap()
    private var armTimer: Job? = null
    private var poll: Job? = null
    private var changeWindow: Job? = null
    private var listed = false

    init {
        work.launch { link.state.collect { l -> change { it.copy(link = l, refreshing = it.refreshing && l is LinkState.Online) } } }
        work.launch {
            link.state
                .map { (it as? LinkState.Online)?.generation }
                .distinctUntilChanged()
                .collectLatest { generation -> if (generation != null) connection() }
        }
    }

    // ── lifecycle ──────────────────────────────────────────────────────────────────────────────────

    /** The screen came to the front: what is on it arms again (D38), and the poll runs. */
    fun onResume() {
        val now = clock()
        synchronized(this) { shown = shown.mapValues { now } }
        change { it }
        poll?.cancel()
        poll = work.launch {
            while (true) {
                delay(timings.pollMillis)
                if (state.value.online) reload()
            }
        }
    }

    fun onPause() {
        poll?.cancel()
        poll = null
    }

    // ── list ───────────────────────────────────────────────────────────────────────────────────────

    /** Pull to refresh; offline it is a Retry of the link. */
    fun refresh() {
        if (!state.value.online) return link.retryNow()
        change { it.copy(refreshing = true) }
        reload()
    }

    /** Retry after a failed list. */
    fun retry() {
        link.retryNow()
        change { it.copy(problem = null) }
        reload()
    }

    fun open(jobId: String) = change { if (it.job(jobId) != null) it.copy(open = jobId) else it }

    fun close() = change { it.copy(open = null, confirmDelete = null) }

    fun messageShown() = state.update { it.copy(message = null) }

    // ── controls ───────────────────────────────────────────────────────────────────────────────────

    /** The switch: pause or resume, once armed. */
    fun toggle(jobId: String) {
        val current = state.value
        val job = current.job(jobId) ?: return
        if (!current.online || jobId in current.busy || !armed(CronArm.toggleKey(job))) return
        perform(jobId, CronFrames.toggle(job))
    }

    /** Delete on the detail page asks first. */
    fun askDelete(jobId: String) {
        val current = state.value
        if (!current.online || jobId in current.busy || current.open != jobId || !armed(CronArm.deleteKey(jobId))) return
        change { it.copy(confirmDelete = jobId) }
    }

    fun cancelDelete() = change { it.copy(confirmDelete = null) }

    /** The confirmation's Delete, once armed. */
    fun confirmDelete() {
        val current = state.value
        val jobId = current.confirmDelete ?: return
        val job = current.job(jobId) ?: return
        if (!current.online || jobId in current.busy || !armed(CronArm.confirmKey(jobId))) return
        change { it.copy(confirmDelete = null) }
        perform(jobId, CronFrames.remove(jobId), done = CronMessage.Deleted(job.name)) { it.copy(open = it.open.takeIf { open -> open != jobId }) }
    }

    fun newJob() = change { if (it.online && it.form == null) it.copy(form = CronForm()) else it }

    fun closeForm() = change { if (it.form?.sending == true) it else it.copy(form = null) }

    /** A field of the form changed; Hermes' last refusal goes with it. */
    fun editForm(edit: (CronForm) -> CronForm) = change { s -> s.form?.takeIf { !it.sending }?.let { s.copy(form = edit(it).copy(error = null)) } ?: s }

    /** Create, once armed: one `add`; Hermes checks the schedule and says no with its own text. */
    fun create() {
        val current = state.value
        val form = current.form ?: return
        if (!current.online || form.sending || !form.complete || !armed(CronArm.CREATE)) return
        val draft = CronDraft(form.name.trim(), form.prompt.trim(), form.schedule, CronSchedule.repeat(form.plan), form.continuity)
        change { it.copy(form = form.copy(sending = true, error = null), message = null) }
        work.launch {
            val outcome = send(CronFrames.add(draft))
            change { s ->
                val shownForm = s.form ?: return@change s
                when {
                    outcome == null -> s.copy(form = null, message = CronMessage.Created(draft.name))
                    outcome is CronMessage.Refused -> s.copy(form = shownForm.copy(sending = false, error = outcome.reason))
                    else -> s.copy(form = shownForm.copy(sending = false), message = outcome)
                }
            }
            if (outcome != CronMessage.NotSent) reload()
        }
    }

    // ── internals ──────────────────────────────────────────────────────────────────────────────────

    private fun reload() = reloads.update { it + 1 }

    /** One relay socket: list now and on each reload; `cron.changed` asks for one, coalesced. */
    private suspend fun connection() = coroutineScope {
        listed = false
        launch(start = CoroutineStart.UNDISPATCHED) {
            link.events.collect { frame -> if (frame is InboundFrame.Event && frame.type == CronFrames.CHANGED) onChanged() }
        }
        reloads.collectLatest { load() }
    }

    /** The first `cron.changed` opens a window; later ones join it: one refetch per window, only once a list came. */
    private fun CoroutineScope.onChanged() {
        if (!listed || changeWindow?.isActive == true) return
        changeWindow = launch {
            delay(timings.changeWindowMillis)
            reload()
        }
    }

    private suspend fun load() {
        val list = try {
            val call = CronFrames.list()
            CronFrames.jobs(link.call(call.method, call.params))
        } catch (e: RpcException) {
            // A closed link is the link state's to show; only Hermes' own failure is the list's.
            if (!e.linkClosed) change { it.copy(problem = reason(e), refreshing = false) }
            return
        } catch (e: IllegalArgumentException) {
            change { it.copy(problem = e.message ?: CronFrames.METHOD, refreshing = false) }
            return
        }
        listed = true
        change { s ->
            val known = list.jobs.mapTo(HashSet()) { it.id }
            s.copy(
                jobs = list.jobs,
                warning = list.warning,
                problem = null,
                refreshing = false,
                open = s.open?.takeIf { it in known },
                confirmDelete = s.confirmDelete?.takeIf { it in known },
            )
        }
    }

    /** Sends [call] for [jobId] once; the list is fetched again after any answer. */
    private fun perform(jobId: String, call: ComposerCall, done: CronMessage? = null, onDone: (CronUiState) -> CronUiState = { it }) {
        change { it.copy(busy = it.busy + jobId, message = null) }
        work.launch {
            val outcome = send(call)
            change { s ->
                val base = s.copy(busy = s.busy - jobId)
                if (outcome == null) onDone(base).copy(message = done) else base.copy(message = outcome)
            }
            if (outcome != CronMessage.NotSent) reload()
        }
    }

    /** One `cron.manage` change: null when done, else what to tell the user. */
    private suspend fun send(call: ComposerCall): CronMessage? = try {
        when (val answer = CronFrames.answer(link.call(call.method, call.params))) {
            is CronAnswer.Done -> null
            is CronAnswer.Refused -> CronMessage.Refused(answer.reason)
        }
    } catch (e: RpcException) {
        when {
            !e.written -> CronMessage.NotSent
            e.error != null -> CronMessage.Refused(reason(e))
            else -> CronMessage.NoAnswer
        }
    }

    private fun armed(key: String): Boolean = synchronized(this) { shown[key] }?.let { clock() - it >= CronArm.ARM_MS } == true

    /** Applies [transform], then follows the arming of what is now on screen. */
    private fun change(transform: (CronUiState) -> CronUiState) {
        state.update(transform)
        rearm()
    }

    @Synchronized
    private fun rearm() {
        val now = clock()
        shown = CronArm.track(shown, CronArm.keys(state.value), now)
        val armed = CronArm.armed(shown, now)
        if (state.value.armed != armed) state.update { it.copy(armed = armed) }
        armTimer?.cancel()
        val next = shown.values.filter { now - it < CronArm.ARM_MS }.minOrNull() ?: return
        armTimer = work.launch {
            delay(next + CronArm.ARM_MS - now)
            rearm()
        }
    }

    private fun reason(e: RpcException): String = e.error?.let { error -> error.reason ?: error.message.ifBlank { null } } ?: e.message.orEmpty()
}
