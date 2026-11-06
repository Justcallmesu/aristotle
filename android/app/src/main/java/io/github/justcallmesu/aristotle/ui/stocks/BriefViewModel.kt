package io.github.justcallmesu.aristotle.ui.stocks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.justcallmesu.aristotle.data.ConnectionProblem
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.PcCredentials
import io.github.justcallmesu.aristotle.data.serverEndpoint
import io.github.justcallmesu.aristotle.data.stocks.BriefBody
import io.github.justcallmesu.aristotle.data.stocks.BriefCard
import io.github.justcallmesu.aristotle.data.stocks.BriefStates
import io.github.justcallmesu.aristotle.data.stocks.BriefSummary
import io.github.justcallmesu.aristotle.data.stocks.JobsWithRuns
import io.github.justcallmesu.aristotle.data.stocks.StocksApi
import io.github.justcallmesu.aristotle.data.stocks.StocksError
import io.github.justcallmesu.aristotle.data.stocks.StocksResult
import io.github.justcallmesu.aristotle.data.stocks.StocksTransport
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The brief's card and page (TBA2, protocol §20.4). [latest] is `GET /brief` (today's, or `pending`, or the newest kept
 * one); [latestMissing] the server has none (`404 no_brief`). [picked] a day chosen from the last 7 ([list]); [shown] the
 * page's brief (the latest unless a day is picked). [jobs] tells the brief's time and whether its job is on.
 */
data class BriefUiState(
    val link: LinkState = LinkState.Connecting,
    val latest: BriefBody? = null,
    val latestMissing: Boolean = false,
    val jobs: JobsWithRuns? = null,
    val picked: String? = null,
    val shown: BriefBody? = null,
    val list: List<BriefSummary>? = null,
    val problem: StocksError? = null,
    val rerunning: Boolean = false,
    val rerunProblem: StocksError? = null,
) {
    val reachable: Boolean get() = link.serverEndpoint != null

    /** Stocks home's card on WIB [today] at [now]. */
    fun card(today: LocalDate, now: LocalTime): BriefCard {
        val job = jobs?.job(JobsWithRuns.BRIEF)
        return BriefStates.card(latest, latestMissing, today, now, job?.on, job?.at)
    }

    /** The brief job's time ("08:30"), once `/jobs` has answered. */
    val briefAt: String? get() = jobs?.job(JobsWithRuns.BRIEF)?.at

    /**
     * The page's day chips, newest first: the latest brief (today's, even a made-up `pending` that is not in [list],
     * §20.4) then the kept days. A chip is selected, and picks, by date against [latest]: the latest's chip picks null, so
     * it stays reachable after another day is picked. None when there is no other day to open.
     */
    fun dayChips(): List<DayChip> {
        val latestDate = latest?.date
        val dates = (listOfNotNull(latestDate) + list.orEmpty().map { it.date }).distinct()
        if (latestDate != null && dates.size < 2) return emptyList()
        val current = picked ?: latestDate
        return dates.map { DayChip(it, selected = it == current, pick = it.takeUnless { d -> d == latestDate }) }
    }

    /**
     * The status line source on WIB [today] at [now]: the card's own state for the latest brief; for a picked day, what
     * that brief says (running, failed, missed, written late). One function with [card], so the page and the card agree.
     */
    fun pageStatus(today: LocalDate, now: LocalTime): BriefCard? {
        if (picked == null) return card(today, now)
        val body = shown ?: return null
        return BriefStates.ofBody(body, now, briefAt).takeUnless { it is BriefCard.Pending || it == BriefCard.NoneToday }
    }

    /** The page's brief: the picked day's, else the latest. */
    val page: BriefBody? get() = if (picked == null) latest else shown

    /** "Run again" reruns today's brief only (§20.4 POST): shown on today's page. */
    fun canRerun(today: LocalDate): Boolean = reachable && !rerunning && page?.running != true && page?.date == today.toString()
}

/** One day chip of the brief page: its [date], whether it is the page's brief, and what a tap picks (null: the latest). */
data class DayChip(val date: String, val selected: Boolean, val pick: String?)

class BriefViewModel(
    private val pc: PcCredentials,
    private val link: StateFlow<LinkState>,
    private val transport: StocksTransport,
    scope: CoroutineScope? = null,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) : ViewModel() {
    private val work: CoroutineScope = scope ?: viewModelScope
    private val state = MutableStateFlow(BriefUiState(link = link.value))
    val uiState: StateFlow<BriefUiState> = state.asStateFlow()

    private val homeVisible = MutableStateFlow(false)
    private val pageVisible = MutableStateFlow(false)
    private var follow: Job? = null

    init {
        work.launch { link.collect { l -> state.update { it.copy(link = l) } } }
        work.launch { shownAndReachable(homeVisible).collect { on -> if (on) loadHome() } }
        work.launch { shownAndReachable(pageVisible).collect { on -> if (on) loadPage() } }
    }

    private fun shownAndReachable(shown: StateFlow<Boolean>) = combine(shown, link) { on, l -> on && l.serverEndpoint != null }.distinctUntilChanged()

    fun homeVisible(visible: Boolean) {
        homeVisible.value = visible
    }

    /** Entering the page shows the latest brief: a day picked on an earlier visit is forgotten. */
    fun pageVisible(visible: Boolean) {
        if (visible && !pageVisible.value) forgetPick()
        pageVisible.value = visible
        if (!visible) follow?.cancel()
    }

    /**
     * The page was opened by a tap (Home's card, a notification, Alerts) - also when it is already the page on screen:
     * back to the latest brief, read again.
     */
    fun opened() {
        forgetPick()
        if (pageVisible.value) work.launch { loadLatest() }
    }

    private fun forgetPick() = state.update { it.copy(picked = null, shown = it.latest) }

    /** One of the last 7 briefs (null: back to the latest). */
    fun pick(date: String?) {
        state.update { it.copy(picked = date, shown = if (date == null) it.latest else it.shown?.takeIf { s -> s.date == date }) }
        work.launch { loadShown() }
    }

    /** "Run again" (on-demand AI, not counted in the automatic runs, §20.4); [lang] the phone's language. */
    fun runAgain(lang: String) {
        if (state.value.rerunning) return
        state.update { it.copy(rerunning = true, rerunProblem = null) }
        work.launch {
            val result = api()?.rerunBrief(lang) ?: offline()
            state.update { it.copy(rerunning = false, rerunProblem = (result as? StocksResult.Failed)?.error?.takeUnless(::alreadyRunning)) }
            loadLatest()
            followRunning()
        }
    }

    fun rerunProblemShown() = state.update { it.copy(rerunProblem = null) }

    private suspend fun loadHome() {
        loadLatest()
        loadJobs()
    }

    private suspend fun loadPage() {
        loadLatest()
        if (state.value.picked != null) loadShown()
        loadList()
        if (state.value.jobs == null) loadJobs()
        followRunning()
    }

    /** While the page shows a running brief, read it again every [POLL_MILLIS] (the server answers in ~30-90 s). */
    private fun followRunning() {
        if (follow?.isActive == true) return
        follow = work.launch {
            while (pageVisible.value && state.value.reachable && state.value.page?.running == true) {
                pause(POLL_MILLIS)
                if (state.value.picked == null) loadLatest() else loadShown()
            }
            if (pageVisible.value) loadList()
        }
    }

    private suspend fun loadLatest() {
        when (val result = api()?.brief() ?: return) {
            is StocksResult.Ok -> state.update { s ->
                // A brief of a later day than the one held makes the picked day old news: the page goes to the new latest.
                val newDay = s.latest != null && result.value.date > s.latest.date
                val keepPick = s.picked != null && !newDay
                s.copy(latest = result.value, latestMissing = false, problem = null, picked = s.picked.takeIf { keepPick }, shown = if (keepPick) s.shown else result.value)
            }
            is StocksResult.Failed -> state.update { s ->
                if (noBrief(result.error)) s.copy(latest = null, latestMissing = true, problem = null) else s.copy(problem = result.error)
            }
        }
    }

    private suspend fun loadShown() {
        val date = state.value.picked ?: return loadLatest()
        when (val result = api()?.brief(date) ?: return) {
            is StocksResult.Ok -> state.update { s -> if (s.picked == date) s.copy(shown = result.value, problem = null) else s }
            is StocksResult.Failed -> state.update { it.copy(problem = result.error) }
        }
    }

    private suspend fun loadList() {
        when (val result = api()?.briefs() ?: return) {
            is StocksResult.Ok -> state.update { it.copy(list = result.value) }
            is StocksResult.Failed -> state.update { it.copy(problem = it.problem ?: result.error) }
        }
    }

    private suspend fun loadJobs() {
        (api()?.jobsWithRuns() as? StocksResult.Ok)?.value?.let { jobs -> state.update { it.copy(jobs = jobs) } }
    }

    private fun noBrief(error: StocksError): Boolean = (error as? StocksError.Server)?.let { it.status == NOT_FOUND && it.code == StocksError.NO_BRIEF } == true

    /** `409 brief_running`: another tap (or phone) already started it; the page follows that run. */
    private fun alreadyRunning(error: StocksError): Boolean = (error as? StocksError.Server)?.code == StocksError.BRIEF_RUNNING

    private fun api(): StocksApi? {
        val endpoint = link.value.serverEndpoint ?: return null
        return StocksApi { request -> transport.send(pc, endpoint, request) }
    }

    private fun offline(): StocksResult.Failed = StocksResult.Failed(StocksError.Unreachable(ConnectionProblem.Unreachable))

    companion object {
        const val POLL_MILLIS = 5_000L
        private const val NOT_FOUND = 404
    }
}
