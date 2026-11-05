package io.github.justcallmesu.aristotle.ui.stocks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.justcallmesu.aristotle.data.ConnectionProblem
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.PcCredentials
import io.github.justcallmesu.aristotle.data.serverEndpoint
import io.github.justcallmesu.aristotle.data.stocks.JobView
import io.github.justcallmesu.aristotle.data.stocks.ScanCooldown
import io.github.justcallmesu.aristotle.data.stocks.ScreenerBody
import io.github.justcallmesu.aristotle.data.stocks.ScreenerDraft
import io.github.justcallmesu.aristotle.data.stocks.ScreenerField
import io.github.justcallmesu.aristotle.data.stocks.ScreenerMode
import io.github.justcallmesu.aristotle.data.stocks.ScreenerRefresh
import io.github.justcallmesu.aristotle.data.stocks.ScreenerRefresher
import io.github.justcallmesu.aristotle.data.stocks.ScreenerSettings
import io.github.justcallmesu.aristotle.data.stocks.ScreenerState
import io.github.justcallmesu.aristotle.data.stocks.ScreenerStates
import io.github.justcallmesu.aristotle.data.stocks.SetupFilter
import io.github.justcallmesu.aristotle.data.stocks.SetupKey
import io.github.justcallmesu.aristotle.data.stocks.SetupLabel
import io.github.justcallmesu.aristotle.data.stocks.SetupLabels
import io.github.justcallmesu.aristotle.data.stocks.StocksApi
import io.github.justcallmesu.aristotle.data.stocks.StocksError
import io.github.justcallmesu.aristotle.data.stocks.StocksResult
import io.github.justcallmesu.aristotle.data.stocks.StocksTransport
import io.github.justcallmesu.aristotle.data.stocks.UniverseBody
import io.github.justcallmesu.aristotle.data.stocks.UniverseInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What a screener action was, for its snackbar line. */
enum class ScreenerAction { ScanNow, SaveSettings, SaveUniverse, ResetUniverse }

/** A one-off snackbar: [action] failed with [error], or succeeded when [error] is null. */
data class ScreenerMessage(val action: ScreenerAction, val error: StocksError? = null)

/** The Edit list sheet as typed (§20.2 universe): both lists, the period; [confirmReset] asks before the bundled list. */
data class UniverseSheet(
    val lq45: String,
    val idx80: String,
    val validFrom: String,
    val validTo: String,
    val saving: Boolean = false,
    val confirmReset: Boolean = false,
) {
    val check get() = UniverseInput.check(lq45, idx80, validFrom, validTo)

    companion object {
        fun of(u: UniverseBody) = UniverseSheet(UniverseInput.text(u.lq45), UniverseInput.text(u.idx80), u.validFrom.orEmpty(), u.validTo)
    }
}

data class ScreenerUiState(
    val link: LinkState = LinkState.Connecting,
    /** The server's default view (`GET` without `mode`): the Stocks home section and the row labels. */
    val home: ScreenerBody? = null,
    /** The last answer of each mode the page asked for. */
    val byMode: Map<ScreenerMode, ScreenerBody> = emptyMap(),
    /** Today / Last close as picked on the page; null = the server's default. */
    val pageMode: ScreenerMode? = null,
    val filter: SetupFilter = SetupFilter.All,
    /** Why the last read failed; null after a good one. */
    val problem: StocksError? = null,
    val refreshing: Boolean = false,
    /** Scan now waits until this phone time (`429 too_soon`, or 2 min after this phone's own `202`). */
    val cooldownUntil: Long? = null,
    val scanStarting: Boolean = false,
    /** A scan this phone started, in [followMode], is followed every 3 s until it ends or [followErrors] reaches the cap. */
    val following: Boolean = false,
    val followMode: ScreenerMode? = null,
    /** Reads in a row that failed while [following]. */
    val followErrors: Int = 0,
    val settings: ScreenerSettings? = null,
    val scanJob: JobView? = null,
    val universe: UniverseBody? = null,
    val settingsProblem: StocksError? = null,
    val draft: ScreenerDraft? = null,
    val saving: Boolean = false,
    val universeSheet: UniverseSheet? = null,
    val message: ScreenerMessage? = null,
) {
    val reachable: Boolean get() = link.serverEndpoint != null

    /**
     * The page's list: the picked mode's answer (the default view when it is that mode), else the default view. A picked
     * mode not read yet has no list (Loading / NotLoaded), never the other mode's.
     */
    val pageBody: ScreenerBody?
        get() = when (pageMode) {
            null -> home
            else -> byMode[pageMode] ?: home?.takeIf { it.screenerMode == pageMode }
        }

    /** Today / Last close as shown: the pick, else the mode the server chose. */
    val shownMode: ScreenerMode? get() = pageMode ?: home?.screenerMode

    val homeState: ScreenerState get() = ScreenerStates.of(home, reachable)
    val pageState: ScreenerState get() = ScreenerStates.of(pageBody, reachable)

    /** A scan runs (started here, or the shown mode's scan). */
    val scanBusy: Boolean get() = scanStarting || following || pageBody?.scan?.running == true

    /** Seconds Scan now still waits at [now] (the cooldown), null when it does not. */
    fun scanWaitSeconds(now: Long): Int? = ScanCooldown.secondsLeft(cooldownUntil, now)

    /** Scan now can be asked at [now]: the PC answers, no scan runs, no cooldown. The ⋮ item and the stale notice both use it. */
    fun canScan(now: Long): Boolean = reachable && !scanBusy && scanWaitSeconds(now) == null

    /** Labels for a watchlist / holding row (Q106: bearish flags only there, never hidden behind a candidate's labels). */
    fun labelsFor(code: String): List<SetupLabel> = SetupLabels.forCode(code, home)

    /** The row labels drawn muted: the default view is stale (its last run did not replace it) or the PC is offline. */
    val labelsMuted: Boolean get() = homeState.muted
}

/**
 * The screener (TSC3, protocol §20.2-§20.3): the Stocks home section, the Screener page, the setup labels on watchlist
 * and holding rows, and Settings › Stocks › Screener with the scan job and the stock list. Reads whenever aristotle-server
 * answers ([serverEndpoint]; no Hermes needed): on every show of a screener view, then by [ScreenerRefresh] (60 s inside a
 * scan window, a 3 s follow-up of a scan started here). Pull to refresh only reads (no scan).
 */
class ScreenerViewModel(
    private val pc: PcCredentials,
    private val link: StateFlow<LinkState>,
    private val transport: StocksTransport,
    private val retryLink: () -> Unit = {},
    scope: CoroutineScope? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    pause: suspend (Long) -> Unit = { delay(it) },
) : ViewModel() {
    private val work: CoroutineScope = scope ?: viewModelScope
    private val state = MutableStateFlow(ScreenerUiState(link = link.value))
    val uiState: StateFlow<ScreenerUiState> = state.asStateFlow()

    private val homeVisible = MutableStateFlow(false)
    private val pageVisible = MutableStateFlow(false)
    private val holdingsVisible = MutableStateFlow(false)
    private val settingsVisible = MutableStateFlow(false)
    private val active: StateFlow<Boolean> = combine(homeVisible, pageVisible, holdingsVisible, link) { home, page, holdings, l ->
        (home || page || holdings) && l.serverEndpoint != null
    }.distinctUntilChanged().stateIn(work, SharingStarted.Eagerly, false)
    private val refresher = ScreenerRefresher(
        work,
        active,
        nextDelay = { last, now ->
            val s = state.value
            val closed = ScreenerRefresh.closedToday(listOfNotNull(s.home) + s.byMode.values, now)
            ScreenerRefresh.nextDelay(active = true, following = s.following, closedToday = closed, lastReadAt = last, now = now)
        },
        read = ::readLists,
        clock = clock,
        pause = pause,
    )

    init {
        work.launch { link.collect { l -> state.update { it.copy(link = l) } } }
        work.launch {
            combine(settingsVisible, link) { on, l -> on && l.serverEndpoint != null }.distinctUntilChanged().collect { on -> if (on) loadSettings() }
        }
        refresher.start()
    }

    fun homeVisible(visible: Boolean) {
        homeVisible.value = visible
    }

    fun pageVisible(visible: Boolean) {
        pageVisible.value = visible
    }

    fun holdingsVisible(visible: Boolean) {
        holdingsVisible.value = visible
    }

    fun settingsVisible(visible: Boolean) {
        settingsVisible.value = visible
    }

    fun retry() = retryLink()

    fun messageShown() = state.update { it.copy(message = null) }

    // ── page ──

    /** Today / Last close: shown at once from what is known, read again. */
    fun selectMode(mode: ScreenerMode) {
        state.update { it.copy(pageMode = mode) }
        if (!state.value.reachable) return
        work.launch {
            refresher.noteRead()
            readMode(mode)
        }
    }

    fun setFilter(filter: SetupFilter) = state.update { it.copy(filter = filter) }

    /** Pull to refresh: reads the list again (never a scan); offline it retries the link. */
    fun refresh() {
        if (!state.value.reachable) return retryLink()
        state.update { it.copy(refreshing = true) }
        work.launch {
            refresher.noteRead()
            readLists()
            state.update { it.copy(refreshing = false) }
        }
    }

    /** ⋮ Scan now (§20.3 POST): refused here during the 2 min cooldown, as the server would; followed until it ends. */
    fun scanNow() {
        val s = state.value
        if (!s.canScan(clock())) return
        state.update { it.copy(scanStarting = true) }
        work.launch {
            val result = api()?.scanNow() ?: offline()
            val now = clock()
            when (result) {
                is StocksResult.Ok -> state.update {
                    it.copy(
                        scanStarting = false,
                        following = true,
                        followMode = ScreenerMode.of(result.value.mode),
                        followErrors = 0,
                        cooldownUntil = ScanCooldown.later(it.cooldownUntil, ScanCooldown.untilFromStart(now)),
                    )
                }
                is StocksResult.Failed -> {
                    val error = result.error
                    val code = (error as? StocksError.Server)?.code
                    state.update {
                        when (code) {
                            StocksError.TOO_SOON -> it.copy(scanStarting = false, cooldownUntil = ScanCooldown.untilFromRefusal((error as StocksError.Server).retryAfterSeconds, now))
                            StocksError.SCAN_RUNNING -> it.copy(scanStarting = false, following = true, followErrors = 0, message = ScreenerMessage(ScreenerAction.ScanNow, error))
                            else -> it.copy(scanStarting = false, message = ScreenerMessage(ScreenerAction.ScanNow, error))
                        }
                    }
                }
            }
            refresher.poke()
        }
    }

    // ── settings ──

    fun editField(field: ScreenerField, text: String) = updateDraft { it.withText(field, text) }

    fun stepField(field: ScreenerField, by: Int) = updateDraft { it.stepped(field, by) }

    fun setSetupOn(key: SetupKey, on: Boolean) = updateDraft { it.withOn(key, on) }

    fun setGap(up: Boolean, down: Boolean) = updateDraft { it.withGap(up, down) }

    /** "Defaults": the server's starting values (Q111) and every 10 min, not saved until Save. */
    fun defaults() = updateDraft { ScreenerDraft.defaults(it.base, it.baseEvery) }

    /** Leaving the settings page drops what was typed and not saved. */
    fun discardDraft() = state.update { s -> s.copy(draft = s.settings?.let { ScreenerDraft.of(it, s.scanJob?.everyMin) }) }

    /** Save: `PUT /screener/settings` when a setting changed, then `PUT /jobs/screener.scan` when the interval did. */
    fun save() {
        val draft = state.value.draft ?: return
        if (!draft.canSave || !state.value.reachable || state.value.saving) return
        val settings = draft.settings() ?: return
        state.update { it.copy(saving = true) }
        work.launch {
            var failed: StocksError? = null
            var saved = draft.base
            var job = state.value.scanJob
            if (draft.settingsChanged) {
                when (val r = api()?.putScreenerSettings(settings) ?: offline()) {
                    is StocksResult.Ok -> saved = r.value
                    is StocksResult.Failed -> failed = r.error
                }
            }
            val every = draft.every()
            if (failed == null && draft.everyChanged && every != null) {
                when (val r = api()?.putJobEvery(JobView.SCAN, every) ?: offline()) {
                    is StocksResult.Ok -> job = r.value
                    is StocksResult.Failed -> failed = r.error
                }
            }
            state.update { s ->
                val rebased = s.draft?.copy(base = saved, baseEvery = job?.everyMin)
                s.copy(
                    settings = saved,
                    scanJob = job,
                    saving = false,
                    draft = if (failed == null) ScreenerDraft.of(saved, job?.everyMin) else rebased,
                    message = ScreenerMessage(ScreenerAction.SaveSettings, failed),
                )
            }
            // The server re-ranked the kept lists at once (§20.3).
            if (failed == null && draft.settingsChanged) readLists()
        }
    }

    // ── universe (Edit list) ──

    fun openUniverse() {
        val u = state.value.universe ?: return
        state.update { it.copy(universeSheet = UniverseSheet.of(u)) }
    }

    fun editUniverse(sheet: UniverseSheet) = state.update { s -> s.copy(universeSheet = s.universeSheet?.let { sheet.copy(saving = it.saving) }) }

    fun closeUniverse() = state.update { it.copy(universeSheet = null) }

    fun askResetUniverse(ask: Boolean) = state.update { s -> s.copy(universeSheet = s.universeSheet?.copy(confirmReset = ask)) }

    fun saveUniverse() {
        val sheet = state.value.universeSheet ?: return
        val check = sheet.check
        if (!check.ok || sheet.saving) return
        universeCall(ScreenerAction.SaveUniverse) { it.putUniverse(check.lq45, check.idx80, check.validTo, check.validFrom) }
    }

    /** "Use bundled list" (`DELETE /universe`). */
    fun resetUniverse() = universeCall(ScreenerAction.ResetUniverse) { it.resetUniverse() }

    private fun universeCall(action: ScreenerAction, call: suspend (StocksApi) -> StocksResult<UniverseBody>) {
        state.update { s -> s.copy(universeSheet = s.universeSheet?.copy(saving = true, confirmReset = false)) }
        work.launch {
            when (val r = api()?.let { call(it) } ?: offline()) {
                is StocksResult.Ok -> {
                    state.update { it.copy(universe = r.value, universeSheet = null, message = ScreenerMessage(action)) }
                    readDefault()
                }
                is StocksResult.Failed -> state.update { s ->
                    s.copy(universeSheet = s.universeSheet?.copy(saving = false), message = ScreenerMessage(action, r.error))
                }
            }
        }
    }

    // ── reads ──

    /**
     * One refresher tick: the default view, the page's pick, and the mode of a scan being followed. A tick with a failed
     * read counts toward [FOLLOW_MAX_ERRORS]: a run of server errors ends the 3 s follow and the normal rule takes over.
     */
    private suspend fun readLists() {
        var failed = !readDefault()
        val s = state.value
        val modes = buildSet {
            if (pageVisible.value) s.pageMode?.let(::add)
            if (s.following) s.followMode?.let(::add)
        }
        modes.filter { it != state.value.home?.screenerMode }.forEach { if (!readMode(it)) failed = true }
        state.update { st ->
            when {
                !st.following -> st.copy(followErrors = 0)
                !failed -> st.copy(followErrors = 0)
                st.followErrors + 1 >= FOLLOW_MAX_ERRORS -> st.copy(following = false, followMode = null, followErrors = 0)
                else -> st.copy(followErrors = st.followErrors + 1)
            }
        }
    }

    /** False when the read failed (offline counts as not failed: the loop is off then). */
    private suspend fun readDefault(): Boolean = when (val r = api()?.screener()) {
        null -> true
        is StocksResult.Ok -> true.also { took(r.value, default = true) }
        is StocksResult.Failed -> false.also { state.update { it.copy(problem = r.error) } }
    }

    private suspend fun readMode(mode: ScreenerMode): Boolean = when (val r = api()?.screener(mode)) {
        null -> true
        is StocksResult.Ok -> true.also { took(r.value, default = false) }
        is StocksResult.Failed -> false.also { state.update { it.copy(problem = r.error) } }
    }

    private fun took(body: ScreenerBody, default: Boolean) {
        val now = clock()
        state.update { s ->
            val mode = body.screenerMode
            val byMode = if (mode != null) s.byMode + (mode to body) else s.byMode
            // A followed scan ends when its mode's answer no longer says running.
            val ended = s.following && (s.followMode == null || s.followMode == mode) && !body.scan.running
            s.copy(
                home = if (default) body else s.home,
                byMode = byMode,
                problem = null,
                following = s.following && !ended,
                followMode = if (ended) null else s.followMode,
                cooldownUntil = s.cooldownUntil?.takeIf { it > now },
            )
        }
    }

    /** Settings › Stocks › Screener: settings, the scan job, the list, and the default view for its skipped count. */
    private suspend fun loadSettings() {
        val api = api() ?: return
        val settings = api.screenerSettings()
        val jobs = api.jobs()
        val universe = api.universe()
        state.update { s ->
            val loaded = settings.valueOrNull() ?: s.settings
            val job = jobs.valueOrNull()?.firstOrNull { it.id == JobView.SCAN } ?: s.scanJob
            val keep = s.draft?.takeIf { it.canSave || !it.valid }
            s.copy(
                settings = loaded,
                scanJob = job,
                universe = universe.valueOrNull() ?: s.universe,
                settingsProblem = listOf(settings, jobs, universe).firstNotNullOfOrNull { (it as? StocksResult.Failed)?.error },
                draft = keep?.copy(base = loaded ?: keep.base, baseEvery = job?.everyMin) ?: loaded?.let { ScreenerDraft.of(it, job?.everyMin) },
            )
        }
        if (state.value.home == null) readDefault()
    }

    private fun updateDraft(change: (ScreenerDraft) -> ScreenerDraft) = state.update { s -> s.copy(draft = s.draft?.let(change)) }

    private fun api(): StocksApi? {
        val endpoint = link.value.serverEndpoint ?: return null
        return StocksApi { request -> transport.send(pc, endpoint, request) }
    }

    private fun offline(): StocksResult.Failed = StocksResult.Failed(StocksError.Unreachable(ConnectionProblem.Unreachable))

    companion object {
        /** Failed reads in a row that end the 3 s follow of a scan started here. */
        const val FOLLOW_MAX_ERRORS = 10
    }
}

private fun <T> StocksResult<T>.valueOrNull(): T? = (this as? StocksResult.Ok)?.value
