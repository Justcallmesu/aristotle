package io.github.justcallmesu.aristotle.ui.stocks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.PcCredentials
import io.github.justcallmesu.aristotle.data.serverEndpoint
import io.github.justcallmesu.aristotle.data.stocks.AnalysisProblem
import io.github.justcallmesu.aristotle.data.stocks.AnalysisStates
import io.github.justcallmesu.aristotle.data.stocks.AnalysisStatus
import io.github.justcallmesu.aristotle.data.stocks.AnalysisView
import io.github.justcallmesu.aristotle.data.stocks.ChartBody
import io.github.justcallmesu.aristotle.data.stocks.ChartCache
import io.github.justcallmesu.aristotle.data.stocks.ChartRange
import io.github.justcallmesu.aristotle.data.stocks.MarketStatus
import io.github.justcallmesu.aristotle.data.stocks.NewsItem
import io.github.justcallmesu.aristotle.data.stocks.QuoteRefresher
import io.github.justcallmesu.aristotle.data.stocks.QuotesBody
import io.github.justcallmesu.aristotle.data.stocks.StockQuote
import io.github.justcallmesu.aristotle.data.stocks.StocksApi
import io.github.justcallmesu.aristotle.data.stocks.StocksError
import io.github.justcallmesu.aristotle.data.stocks.StocksResult
import io.github.justcallmesu.aristotle.data.stocks.StocksTransport
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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

enum class ChartType { Candles, Line }

/** One range's bars: the last good answer ([fetchedAt] = when it was asked), and why the last read failed, if it did. */
data class ChartSlot(val body: ChartBody? = null, val fetchedAt: Long? = null, val loading: Boolean = false, val problem: StocksError? = null)

data class StockDetailState(
    val code: String,
    val link: LinkState = LinkState.Connecting,
    val range: ChartRange = ChartRange.DEFAULT,
    val type: ChartType = ChartType.Candles,
    val charts: Map<ChartRange, ChartSlot> = emptyMap(),
    val quotes: QuotesBody? = null,
    val quoteProblem: StocksError? = null,
    /** News that mentions the code, newest first; null until read. */
    val news: List<NewsItem>? = null,
    val newsProblem: StocksError? = null,
    /** The last `GET /analysis` (TA2); null until read. */
    val analysis: AnalysisStatus? = null,
    /** Why the last `GET /analysis` failed (the last answer stays). */
    val analysisReadProblem: StocksError? = null,
    /** A `POST /analyze` is on its way. */
    val analyzing: Boolean = false,
    /** Why the last start was refused; cleared by the next start or a job seen running or done. */
    val analyzeProblem: AnalysisProblem? = null,
) {
    val reachable: Boolean get() = link.serverEndpoint != null
    val quote: StockQuote? get() = quotes?.quoteOf(code)
    val chart: ChartSlot get() = charts[range] ?: ChartSlot()
    val day: ChartBody? get() = charts[ChartRange.Day]?.body

    /** The Analyze section on [today] (WIB); [market] decides the current session (the page's quotes, else the home's). */
    fun analysisView(market: MarketStatus?, today: LocalDate): AnalysisView =
        AnalysisStates.view(analysis, analyzing, analyzeProblem, AnalysisStates.currentSession(market, today))
}

/**
 * A stock's page (TS3): its quote, the bars of each range asked for (kept per range while the page lives) and the
 * news that mentions it, over protocol §20. Changing range reads that range unless it is cached ([ChartCache]).
 * While the page is visible and the server answers, the quote and the 1D bars are read again every 60 s while the
 * market is open, by the Stocks home's rules ([QuoteRefresher]); outside a session once on showing, then at the next
 * open. 1D is always read (the day's open comes from it). A range Yahoo refused is read again after its
 * `retry_after_s`.
 *
 * The analyzer (TA2, protocol §20.1): the job's state is read once each time the page shows; while a job runs and the
 * page is visible it is read every 3 s ([AnalysisStates.POLL_MILLIS], [analysisPause] is the test seam) and the polling
 * stops at done / failed or when the page hides. The job runs on the PC, so leaving the page loses nothing.
 */
class StockDetailViewModel(
    private val code: String,
    private val pc: PcCredentials,
    private val link: StateFlow<LinkState>,
    private val transport: StocksTransport,
    scope: CoroutineScope? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    pause: suspend (Long) -> Unit = { delay(it) },
    private val retryPause: suspend (Long) -> Unit = { delay(it) },
    private val analysisPause: suspend (Long) -> Unit = { delay(it) },
) : ViewModel() {
    private val work: CoroutineScope = scope ?: viewModelScope
    private val state = MutableStateFlow(StockDetailState(code, link = link.value))
    val uiState: StateFlow<StockDetailState> = state.asStateFlow()

    private val visible = MutableStateFlow(false)
    private val active: StateFlow<Boolean> =
        combine(visible, link) { on, l -> on && l.serverEndpoint != null }.distinctUntilChanged().stateIn(work, SharingStarted.Eagerly, false)
    private val refresher = QuoteRefresher(work, active, market = { state.value.quotes?.market }, read = ::refresh, clock = clock, pause = pause)
    private val inFlight = mutableSetOf<ChartRange>()
    private var retryJob: Job? = null
    private var pollJob: Job? = null

    init {
        work.launch { link.collect { l -> state.update { it.copy(link = l) } } }
        work.launch {
            active.collect { on ->
                if (!on) return@collect
                // 1D comes with the refresher's read; another range is read here when it is not cached.
                val range = state.value.range
                if (range != ChartRange.Day && due(range)) loadChart(range)
                if (state.value.news == null) loadNews()
            }
        }
        work.launch {
            active.collect { on -> if (on) follow(readFirst = true) else pollJob?.cancel() }
        }
        refresher.start()
    }

    /** Starts an analysis in [lang] (`id` / `en`, [io.github.justcallmesu.aristotle.data.stocks.AnalysisLang]); then follows it. */
    fun analyze(lang: String) {
        val now = state.value
        if (!now.reachable || now.analyzing || now.analysis?.running == true) return
        state.update { it.copy(analyzing = true, analyzeProblem = null) }
        work.launch {
            when (val result = api()?.analyze(code, lang)) {
                null -> state.update { it.copy(analyzing = false) }
                is StocksResult.Ok -> {
                    state.update {
                        val running = AnalysisStatus(code, AnalysisStatus.RUNNING, result.value.jobId, result.value.startedAt, result = it.analysis?.result)
                        it.copy(analyzing = false, analysis = running)
                    }
                    follow(readFirst = false)
                }
                is StocksResult.Failed -> {
                    val problem = AnalysisProblem.ofPost(result.error)
                    if (problem == AnalysisProblem.AlreadyRunning) {
                        // Another phone or a raced tap: read that job, which shows and follows it.
                        state.update { it.copy(analyzing = false) }
                        follow(readFirst = true)
                    } else {
                        state.update { it.copy(analyzing = false, analyzeProblem = problem) }
                    }
                }
            }
        }
    }

    /** Reads the analysis again after a failed read. */
    fun retryAnalysisRead() {
        if (state.value.reachable) follow(readFirst = true)
    }

    /** Reads the job's state (when [readFirst]), then every [AnalysisStates.POLL_MILLIS] while it runs and the page is visible. */
    private fun follow(readFirst: Boolean) {
        pollJob?.cancel()
        pollJob = work.launch {
            if (readFirst) readAnalysis()
            while (active.value && state.value.analysis?.running == true) {
                analysisPause(AnalysisStates.POLL_MILLIS)
                if (!active.value) break
                readAnalysis()
            }
        }
    }

    private suspend fun readAnalysis() {
        when (val result = api()?.analysis(code) ?: return) {
            is StocksResult.Ok -> state.update {
                val body = result.value
                it.copy(analysis = body, analysisReadProblem = null, analyzeProblem = if (body.running || body.state == AnalysisStatus.DONE) null else it.analyzeProblem)
            }
            is StocksResult.Failed -> state.update { it.copy(analysisReadProblem = result.error) }
        }
    }

    /** The page is on screen and resumed (or not). */
    fun visible(on: Boolean) {
        visible.value = on
    }

    fun selectRange(range: ChartRange) {
        state.update { it.copy(range = range) }
        // Checked where the reads run, so a read finishing meanwhile counts.
        work.launch { if (due(range)) loadChart(range) }
    }

    fun selectType(type: ChartType) = state.update { it.copy(type = type) }

    /** Retry after a failed read: this range's bars, and the quote and news if they failed too. */
    fun retry() {
        if (!state.value.reachable) return
        work.launch {
            loadChart(state.value.range)
            if (state.value.quotes == null || state.value.quoteProblem != null) readQuote()
            if (state.value.news == null) loadNews()
        }
    }

    private fun due(range: ChartRange): Boolean = state.value.charts[range].let { ChartCache.due(range, it?.fetchedAt, clock(), stale = it?.body?.stale == true) }

    /** One refresher tick: the quote and today's bars. */
    private suspend fun refresh() {
        readQuote()
        loadChart(ChartRange.Day)
    }

    private suspend fun readQuote() {
        when (val result = api()?.quotes(listOf(code)) ?: return) {
            is StocksResult.Ok -> state.update { it.copy(quotes = result.value, quoteProblem = null) }
            is StocksResult.Failed -> state.update { it.copy(quoteProblem = result.error) }
        }
    }

    /** Reads [range]'s bars; a failure keeps the bars already shown. One read per range at a time. */
    private suspend fun loadChart(range: ChartRange) {
        val api = api() ?: return
        if (!inFlight.add(range)) return
        try {
            val asked = clock()
            update(range) { it.copy(loading = true) }
            when (val result = api.chart(code, range)) {
                is StocksResult.Ok -> update(range) { ChartSlot(body = result.value, fetchedAt = asked) }
                is StocksResult.Failed -> {
                    update(range) { it.copy(loading = false, problem = result.error) }
                    retryWhenUnblocked(range, result.error)
                }
            }
        } finally {
            inFlight.remove(range)
        }
    }

    /** Yahoo refused (503 `upstream_blocked`): read [range] again after `retry_after_s` if it is still on screen. */
    private fun retryWhenUnblocked(range: ChartRange, error: StocksError) {
        val seconds = (error as? StocksError.Server)?.takeIf { it.code == StocksError.UPSTREAM_BLOCKED }?.retryAfterSeconds ?: return
        retryJob?.cancel()
        retryJob = work.launch {
            retryPause(seconds * MILLIS_PER_SECOND)
            if (active.value && state.value.range == range) loadChart(range)
        }
    }

    private fun update(range: ChartRange, change: (ChartSlot) -> ChartSlot) =
        state.update { it.copy(charts = it.charts + (range to change(it.charts[range] ?: ChartSlot()))) }

    private suspend fun loadNews() {
        when (val result = api()?.newsAbout(code, NEWS_COUNT) ?: return) {
            is StocksResult.Ok -> state.update { it.copy(news = result.value.items, newsProblem = null) }
            is StocksResult.Failed -> state.update { it.copy(newsProblem = result.error) }
        }
    }

    private fun api(): StocksApi? {
        val endpoint = link.value.serverEndpoint ?: return null
        return StocksApi { request -> transport.send(pc, endpoint, request) }
    }

    companion object {
        /** Related news rows on the page (then "All news"). */
        const val NEWS_COUNT = 5
        private const val MILLIS_PER_SECOND = 1_000L
    }
}
