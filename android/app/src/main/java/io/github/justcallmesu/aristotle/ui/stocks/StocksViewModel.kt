package io.github.justcallmesu.aristotle.ui.stocks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.justcallmesu.aristotle.data.ConnectionProblem
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.PcCredentials
import io.github.justcallmesu.aristotle.data.serverEndpoint
import io.github.justcallmesu.aristotle.data.stocks.HoldingInput
import io.github.justcallmesu.aristotle.data.stocks.HoldingsBody
import io.github.justcallmesu.aristotle.data.stocks.NewsBody
import io.github.justcallmesu.aristotle.data.stocks.NewsFilter
import io.github.justcallmesu.aristotle.data.stocks.QuoteRefresher
import io.github.justcallmesu.aristotle.data.stocks.QuotesBody
import io.github.justcallmesu.aristotle.data.stocks.SearchResult
import io.github.justcallmesu.aristotle.data.stocks.StockFees
import io.github.justcallmesu.aristotle.data.stocks.StockQuote
import io.github.justcallmesu.aristotle.data.stocks.StocksApi
import io.github.justcallmesu.aristotle.data.stocks.StocksError
import io.github.justcallmesu.aristotle.data.stocks.StocksResult
import io.github.justcallmesu.aristotle.data.stocks.StocksTransport
import io.github.justcallmesu.aristotle.data.stocks.WatchEntry
import io.github.justcallmesu.aristotle.data.stocks.WatchlistOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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

/** What a failed action was, for its one-line message. */
enum class StocksAction { AddWatch, RemoveWatch, Reorder, SaveHolding, RemoveHolding, SaveFees }

/** A one-off snackbar: [action] on [code] failed with [error]. */
data class StocksMessage(val action: StocksAction, val error: StocksError, val code: String? = null)

/** The code search sheet: to add to the watchlist, or to pick the stock of a new holding. */
data class StockSearch(
    val forHolding: Boolean = false,
    val query: String = "",
    val results: List<SearchResult> = emptyList(),
    val searching: Boolean = false,
    val problem: StocksError? = null,
    /** The code being added to the watchlist right now. */
    val adding: String? = null,
)

/** The add / edit holding sheet: the typed text is kept as typed; [HoldingInput] checks it. */
data class HoldingSheet(
    val code: String,
    val name: String,
    val editing: Boolean,
    val lots: String = "",
    val avgPrice: String = "",
    /** The last price, for the P/L preview; null until known. */
    val price: Long? = null,
    val saving: Boolean = false,
)

data class StocksUiState(
    val link: LinkState = LinkState.Connecting,
    val watchlist: List<WatchEntry>? = null,
    val quotes: QuotesBody? = null,
    /** Quotes of codes on neither list (a news chip, a search pick), read one by one. */
    val extraQuotes: Map<String, StockQuote> = emptyMap(),
    val holdings: HoldingsBody? = null,
    val news: NewsBody? = null,
    val newsFilter: NewsFilter = NewsFilter.All,
    val fees: StockFees? = null,
    /** Why the last read of prices (or the lists) failed; null after a good one. */
    val problem: StocksError? = null,
    val newsProblem: StocksError? = null,
    val feesProblem: StocksError? = null,
    val feesSaving: Boolean = false,
    /** Pull to refresh is running. */
    val refreshing: Boolean = false,
    val search: StockSearch? = null,
    /** The watchlist code whose actions (move, remove) are open. */
    val watchActions: String? = null,
    val holdingSheet: HoldingSheet? = null,
    /** The holding waiting for "Remove" to be confirmed. */
    val confirmRemove: String? = null,
    val message: StocksMessage? = null,
) {
    /** aristotle-server answers (online, or Hermes down / updated): stocks need only the server and the token (§20). */
    val reachable: Boolean get() = link.serverEndpoint != null

    fun quoteOf(code: String): StockQuote? = quotes?.quoteOf(code) ?: extraQuotes[code]

    val watchCodes: List<String> get() = watchlist.orEmpty().map { it.code }
    val holdingCodes: List<String> get() = holdings?.holdings.orEmpty().map { it.code }

    /** The name of [code] from whatever list knows it. */
    fun nameOf(code: String): String = watchlist?.firstOrNull { it.code == code }?.name?.takeIf { it.isNotEmpty() }
        ?: holdings?.rowOf(code)?.name?.takeIf { it.isNotEmpty() }
        ?: quoteOf(code)?.name.orEmpty()
}

/**
 * The stocks pages (TS2): Home's Stocks view, Holdings, News, a stock's page and Settings › Stocks, over `/v1/stocks/…`
 * (protocol §20) on the paired PC. Calls go out whenever aristotle-server answers, on the address it answered on
 * ([serverEndpoint]: online, Hermes down or Hermes updated; stocks need no Hermes). Prices are read every 60 s only while
 * the Stocks view is visible, the market open and the server reachable ([QuoteRefresher]). Each view (Home's Stocks,
 * Holdings, News) loads when it is shown and again each time the server becomes reachable while it is shown.
 */
class StocksViewModel(
    private val pc: PcCredentials,
    private val link: StateFlow<LinkState>,
    private val transport: StocksTransport,
    private val retryLink: () -> Unit = {},
    scope: CoroutineScope? = null,
    clock: () -> Long = System::currentTimeMillis,
    pause: suspend (Long) -> Unit = { delay(it) },
    private val searchPause: suspend () -> Unit = { delay(SEARCH_DEBOUNCE_MILLIS) },
) : ViewModel() {
    private val work: CoroutineScope = scope ?: viewModelScope
    private val state = MutableStateFlow(StocksUiState(link = link.value))
    val uiState: StateFlow<StocksUiState> = state.asStateFlow()

    private val homeVisible = MutableStateFlow(false)
    private val holdingsVisible = MutableStateFlow(false)
    private val newsVisible = MutableStateFlow(false)
    private val active: StateFlow<Boolean> = shownAndReachable(homeVisible).stateIn(work, SharingStarted.Eagerly, false)
    private val refresher = QuoteRefresher(work, active, market = { state.value.quotes?.market }, read = ::readPrices, clock = clock, pause = pause)
    private var searchJob: Job? = null

    init {
        work.launch { link.collect { l -> state.update { it.copy(link = l) } } }
        work.launch { active.collect { on -> if (on) loadLists() } }
        work.launch { shownAndReachable(holdingsVisible).collect { on -> if (on) loadHoldingsPage() } }
        work.launch { shownAndReachable(newsVisible).collect { on -> if (on) loadNews() } }
        refresher.start()
    }

    /** True while [shown] and the server answers; each rise (shown again, or the server back) is one load. */
    private fun shownAndReachable(shown: StateFlow<Boolean>) =
        combine(shown, link) { on, l -> on && l.serverEndpoint != null }.distinctUntilChanged()

    /** The Stocks view is on screen and resumed (or not). */
    fun homeVisible(visible: Boolean) {
        homeVisible.value = visible
    }

    fun retry() = retryLink()

    /** Pull to refresh: the lists, prices and news again; offline it retries the link. */
    fun refresh() {
        if (!state.value.reachable) return retryLink()
        state.update { it.copy(refreshing = true) }
        work.launch {
            refresher.noteRead()
            val lists = async { loadLists() }
            val prices = async { readPrices() }
            val news = async { loadNews() }
            lists.await(); prices.await(); news.await()
            state.update { it.copy(refreshing = false) }
        }
    }

    // ── pages ──

    /** The Holdings page is on screen and resumed (or not). */
    fun holdingsVisible(visible: Boolean) {
        holdingsVisible.value = visible
    }

    /** The News page is on screen and resumed (or not). */
    fun newsVisible(visible: Boolean) {
        newsVisible.value = visible
    }

    fun setNewsFilter(filter: NewsFilter) = state.update { it.copy(newsFilter = filter) }

    /** A stock's page: its quote is read unless a list already has it. */
    fun showStock(code: String) {
        if (state.value.quoteOf(code) != null) return
        work.launch {
            val body = api()?.quotes(listOf(code))?.let { (it as? StocksResult.Ok)?.value } ?: return@launch
            body.quoteOf(code)?.let { q -> state.update { it.copy(extraQuotes = it.extraQuotes + (code to q)) } }
        }
    }

    fun showFees() {
        work.launch { if (api() == null) state.update { it.copy(feesProblem = offline().error) } else loadFees() }
    }

    fun saveFees(fees: StockFees) {
        state.update { it.copy(feesSaving = true) }
        work.launch {
            when (val result = api()?.putFees(fees) ?: offline()) {
                is StocksResult.Ok -> {
                    state.update { it.copy(fees = result.value, feesProblem = null, feesSaving = false) }
                    readHoldings()
                }
                is StocksResult.Failed -> state.update { it.copy(feesSaving = false, message = StocksMessage(StocksAction.SaveFees, result.error)) }
            }
        }
    }

    fun messageShown() = state.update { it.copy(message = null) }

    // ── watchlist ──

    fun openWatchSearch() = state.update { it.copy(search = StockSearch()) }

    fun closeSearch() {
        searchJob?.cancel()
        state.update { it.copy(search = null) }
    }

    /** Typed into the search field: asks the PC after a short pause (1..40 characters, §20). */
    fun search(query: String) {
        val current = state.value.search ?: return
        state.update { it.copy(search = current.copy(query = query, problem = null)) }
        searchJob?.cancel()
        val q = query.trim()
        if (q.isEmpty()) {
            state.update { s -> s.copy(search = s.search?.copy(results = emptyList(), searching = false)) }
            return
        }
        searchJob = work.launch {
            searchPause()
            state.update { s -> s.copy(search = s.search?.copy(searching = true)) }
            val result = api()?.search(q.take(MAX_QUERY)) ?: offline()
            state.update { s ->
                val open = s.search ?: return@update s
                when (result) {
                    is StocksResult.Ok -> s.copy(search = open.copy(results = result.value, searching = false))
                    is StocksResult.Failed -> s.copy(search = open.copy(searching = false, problem = result.error))
                }
            }
        }
    }

    fun addToWatchlist(code: String) {
        state.update { s -> s.copy(search = s.search?.copy(adding = code)) }
        work.launch {
            when (val result = api()?.addToWatchlist(code) ?: offline()) {
                is StocksResult.Ok -> {
                    state.update { s -> s.copy(watchlist = result.value, search = s.search?.copy(adding = null)) }
                    readQuotes()
                }
                is StocksResult.Failed -> state.update { s ->
                    s.copy(search = s.search?.copy(adding = null), message = StocksMessage(StocksAction.AddWatch, result.error, code))
                }
            }
        }
    }

    fun openWatchActions(code: String) = state.update { it.copy(watchActions = code) }

    fun closeWatchActions() = state.update { it.copy(watchActions = null) }

    fun removeFromWatchlist(code: String) {
        state.update { it.copy(watchActions = null) }
        work.launch {
            when (val result = api()?.removeFromWatchlist(code) ?: offline()) {
                is StocksResult.Ok -> state.update { it.copy(watchlist = result.value) }
                is StocksResult.Failed -> state.update { it.copy(message = StocksMessage(StocksAction.RemoveWatch, result.error, code)) }
            }
        }
    }

    /** Move up (−1) / down (+1): shown at once, sent whole; put back if the PC refuses. */
    fun moveInWatchlist(code: String, by: Int) {
        val before = state.value.watchlist ?: return
        val order = WatchlistOrder.move(before.map { it.code }, code, by)
        if (order == before.map { it.code }) return
        state.update { it.copy(watchlist = WatchlistOrder.arrange(before, order)) }
        work.launch {
            when (val result = api()?.reorderWatchlist(order) ?: offline()) {
                is StocksResult.Ok -> state.update { it.copy(watchlist = result.value) }
                is StocksResult.Failed -> state.update { it.copy(watchlist = before, message = StocksMessage(StocksAction.Reorder, result.error, code)) }
            }
        }
    }

    // ── holdings ──

    /** Add holding: the code search first; picking a stock opens the form. */
    fun addHolding() = state.update { it.copy(search = StockSearch(forHolding = true)) }

    fun pickForHolding(result: SearchResult) {
        val held = state.value.holdings?.rowOf(result.code)
        if (held != null) return editHolding(result.code)
        state.update { it.copy(search = null, holdingSheet = HoldingSheet(result.code, result.name, editing = false, price = it.quoteOf(result.code)?.price)) }
        if (state.value.quoteOf(result.code) == null) {
            work.launch {
                val body = (api()?.quotes(listOf(result.code)) as? StocksResult.Ok)?.value ?: return@launch
                val quote = body.quoteOf(result.code) ?: return@launch
                state.update { s ->
                    s.copy(
                        extraQuotes = s.extraQuotes + (result.code to quote),
                        holdingSheet = s.holdingSheet?.takeIf { it.code == result.code }?.copy(price = quote.price) ?: s.holdingSheet,
                    )
                }
            }
        }
    }

    fun editHolding(code: String) {
        val row = state.value.holdings?.rowOf(code) ?: return
        state.update {
            it.copy(
                search = null,
                holdingSheet = HoldingSheet(
                    code = row.code,
                    name = row.name,
                    editing = true,
                    lots = row.lots.toString(),
                    avgPrice = HoldingInput.avgPriceText(row.avgPrice),
                    price = row.price ?: it.quoteOf(code)?.price,
                ),
            )
        }
    }

    fun editLots(text: String) = state.update { s -> s.copy(holdingSheet = s.holdingSheet?.copy(lots = text.take(MAX_FIELD))) }

    fun editAvgPrice(text: String) = state.update { s -> s.copy(holdingSheet = s.holdingSheet?.copy(avgPrice = text.take(MAX_FIELD))) }

    fun closeHoldingSheet() = state.update { it.copy(holdingSheet = null) }

    fun saveHolding() {
        val sheet = state.value.holdingSheet ?: return
        val lots = HoldingInput.lots(sheet.lots).value ?: return
        val avg = HoldingInput.avgPrice(sheet.avgPrice).value ?: return
        state.update { it.copy(holdingSheet = sheet.copy(saving = true)) }
        work.launch {
            when (val result = api()?.putHolding(sheet.code, lots, avg) ?: offline()) {
                is StocksResult.Ok -> state.update { it.copy(holdings = result.value, holdingSheet = null) }
                is StocksResult.Failed -> state.update { s ->
                    s.copy(holdingSheet = s.holdingSheet?.copy(saving = false), message = StocksMessage(StocksAction.SaveHolding, result.error, sheet.code))
                }
            }
        }
    }

    fun askRemoveHolding(code: String) = state.update { it.copy(confirmRemove = code) }

    fun cancelRemoveHolding() = state.update { it.copy(confirmRemove = null) }

    fun removeHolding() {
        val code = state.value.confirmRemove ?: return
        state.update { it.copy(confirmRemove = null, holdingSheet = null) }
        work.launch {
            when (val result = api()?.deleteHolding(code) ?: offline()) {
                is StocksResult.Ok -> state.update { it.copy(holdings = result.value) }
                is StocksResult.Failed -> state.update { it.copy(message = StocksMessage(StocksAction.RemoveHolding, result.error, code)) }
            }
        }
    }

    // ── reads ──

    /** The watchlist on showing; the news and the fees (the holding sheet's preview needs them) once. */
    private suspend fun loadLists() {
        when (val result = api()?.watchlist() ?: return) {
            is StocksResult.Ok -> state.update { it.copy(watchlist = result.value) }
            is StocksResult.Failed -> state.update { it.copy(problem = result.error) }
        }
        if (state.value.news == null) loadNews()
        if (state.value.fees == null) loadFees()
    }

    /** Holdings, and the fees its summary line shows when they are not known yet. */
    private suspend fun loadHoldingsPage() {
        readHoldings()
        if (state.value.fees == null) loadFees()
    }

    private suspend fun loadFees() {
        when (val result = api()?.fees() ?: return) {
            is StocksResult.Ok -> state.update { it.copy(fees = result.value, feesProblem = null) }
            is StocksResult.Failed -> state.update { it.copy(feesProblem = result.error) }
        }
    }

    /** One refresher tick: quotes and the holdings priced with them. */
    private suspend fun readPrices() {
        readQuotes()
        readHoldings()
    }

    private suspend fun readQuotes() {
        when (val result = api()?.quotes() ?: return) {
            is StocksResult.Ok -> state.update { it.copy(quotes = result.value, problem = null) }
            is StocksResult.Failed -> state.update { it.copy(problem = result.error) }
        }
    }

    private suspend fun readHoldings() {
        when (val result = api()?.holdings() ?: return) {
            is StocksResult.Ok -> state.update { it.copy(holdings = result.value) }
            is StocksResult.Failed -> state.update { it.copy(problem = it.problem ?: result.error) }
        }
    }

    private suspend fun loadNews() {
        when (val result = api()?.news() ?: return) {
            is StocksResult.Ok -> state.update { it.copy(news = result.value, newsProblem = null) }
            is StocksResult.Failed -> state.update { it.copy(newsProblem = result.error) }
        }
    }

    /** The API on the address aristotle-server answered on; null while it does not answer ([serverEndpoint]). */
    private fun api(): StocksApi? {
        val endpoint = link.value.serverEndpoint ?: return null
        return StocksApi { request -> transport.send(pc, endpoint, request) }
    }

    private fun offline(): StocksResult.Failed = StocksResult.Failed(StocksError.Unreachable(ConnectionProblem.Unreachable))

    private companion object {
        const val SEARCH_DEBOUNCE_MILLIS = 300L
        const val MAX_QUERY = 40
        const val MAX_FIELD = 16
    }
}
