package io.github.justcallmesu.aristotle.data.stocks

import io.github.justcallmesu.aristotle.data.ConnectionProblem
import io.github.justcallmesu.aristotle.data.HermesJson
import io.github.justcallmesu.aristotle.data.PcCredentials
import io.github.justcallmesu.aristotle.data.ServerEndpoint
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One `/v1/stocks/…` exchange: [path] under `/v1/stocks/`, [query] parameters, an optional JSON [body]. [readTimeoutSeconds]:
 * a longer read timeout for this call only (the alerts long-poll, TBA2); null keeps the client's 10 s.
 */
data class StocksRequest(
    val method: String,
    val path: String,
    val query: Map<String, String> = emptyMap(),
    val body: String? = null,
    val readTimeoutSeconds: Int? = null,
)

/** What came back: an HTTP answer ([retryAfterSeconds] from a `Retry-After` header), or no answer at all. */
sealed interface StocksReply {
    data class Http(val status: Int, val body: String, val retryAfterSeconds: Int? = null) : StocksReply
    data class Failed(val problem: ConnectionProblem) : StocksReply
}

/** Sends one request to the PC with the device token over its pinned client ([io.github.justcallmesu.aristotle.data.HttpLinkDialer]). */
fun interface StocksTransport {
    suspend fun send(pc: PcCredentials, endpoint: ServerEndpoint, request: StocksRequest): StocksReply
}

/** Why a stocks call gave nothing usable. */
sealed interface StocksError {
    /** No HTTP answer: the PC is out of reach, or its certificate changed. */
    data class Unreachable(val problem: ConnectionProblem) : StocksError

    /** An error answer: [code] is the body's `error` (`limit_reached`, `upstream_blocked`, …), [retryAfterSeconds] for a block. */
    data class Server(val status: Int, val code: String?, val retryAfterSeconds: Int? = null) : StocksError

    /** A 200 whose body could not be read. */
    data object BadAnswer : StocksError

    companion object {
        const val LIMIT_REACHED = "limit_reached"
        const val UNKNOWN_SYMBOL = "unknown_symbol"
        const val UPSTREAM_BLOCKED = "upstream_blocked"
        const val UPSTREAM_ERROR = "upstream_error"
        const val STATE_ERROR = "state_error"
        const val TOO_SOON = "too_soon"
        const val SCAN_RUNNING = "scan_running"
        const val NO_BRIEF = "no_brief"
        const val BRIEF_RUNNING = "brief_running"
        const val NOT_TRADING_DAY = "not_trading_day"
    }
}

sealed interface StocksResult<out T> {
    data class Ok<T>(val value: T) : StocksResult<T>
    data class Failed(val error: StocksError) : StocksResult<Nothing>
}

fun <T> StocksResult<T>.valueOrNull(): T? = (this as? StocksResult.Ok)?.value

/**
 * The phone side of protocol §20, as typed calls over a [send] function (the transport bound to the PC and the link's
 * address). Pure apart from [send]: tests pass a fake that answers with the server's own JSON.
 */
class StocksApi(private val send: suspend (StocksRequest) -> StocksReply) {
    suspend fun watchlist(): StocksResult<List<WatchEntry>> = call(StocksRequest(GET, "watchlist"), WatchlistBody.serializer()) { it.items }

    suspend fun addToWatchlist(code: String): StocksResult<List<WatchEntry>> =
        call(StocksRequest(PUT, "watchlist/$code"), WatchlistBody.serializer()) { it.items }

    suspend fun removeFromWatchlist(code: String): StocksResult<List<WatchEntry>> =
        call(StocksRequest(DELETE, "watchlist/$code"), WatchlistBody.serializer()) { it.items }

    /** The same set in a new order; the server refuses anything else (`400 bad_request`). */
    suspend fun reorderWatchlist(codes: List<String>): StocksResult<List<WatchEntry>> {
        val body = buildJsonObject { put("codes", buildJsonArray { codes.forEach { add(it) } }) }
        return call(StocksRequest(PUT, "watchlist", body = body.toString()), WatchlistBody.serializer()) { it.items }
    }

    suspend fun holdings(): StocksResult<HoldingsBody> = call(StocksRequest(GET, "holdings"), HoldingsBody.serializer()) { it }

    /**
     * Adds or replaces a holding (idempotent). [avgPrice] is sent as the server keeps it, to 0.01 IDR. [stop] / [target]
     * (Q104, TBA1): the hand-set alert levels; the server clears a level the PUT leaves out, so an edit sends both again.
     */
    suspend fun putHolding(code: String, lots: Long, avgPrice: Double, stop: Long? = null, target: Long? = null): StocksResult<HoldingsBody> {
        val body = buildJsonObject {
            put("lots", lots)
            put("avg_price", avgPrice)
            stop?.let { put("stop", it) }
            target?.let { put("target", it) }
        }
        return call(StocksRequest(PUT, "holdings/$code", body = body.toString()), HoldingsBody.serializer()) { it }
    }

    suspend fun deleteHolding(code: String): StocksResult<HoldingsBody> =
        call(StocksRequest(DELETE, "holdings/$code"), HoldingsBody.serializer()) { it }

    suspend fun fees(): StocksResult<StockFees> = call(StocksRequest(GET, "settings"), StockFees.serializer()) { it }

    suspend fun putFees(fees: StockFees): StocksResult<StockFees> {
        val body = buildJsonObject {
            put("buy_fee_pct", fees.buyPct)
            put("sell_fee_pct", fees.sellPct)
        }
        return call(StocksRequest(PUT, "settings", body = body.toString()), StockFees.serializer()) { it }
    }

    /** Without [codes]: the watchlist, then the other holdings (the server's default). */
    suspend fun quotes(codes: List<String>? = null): StocksResult<QuotesBody> {
        val query = codes?.let { mapOf("codes" to it.joinToString(",")) } ?: emptyMap()
        return call(StocksRequest(GET, "quotes", query), QuotesBody.serializer()) { it }
    }

    suspend fun news(limit: Int = NEWS_LIMIT): StocksResult<NewsBody> =
        call(StocksRequest(GET, "news", mapOf("limit" to limit.toString())), NewsBody.serializer()) { it }

    /** The newest [limit] items that mention [code] (the server matches them against it, §20). */
    suspend fun newsAbout(code: String, limit: Int): StocksResult<NewsBody> =
        call(StocksRequest(GET, "news", mapOf("code" to code, "limit" to limit.toString())), NewsBody.serializer()) { it }

    /** Bars of one [range] (`?range=1d|5d|1mo|6mo|1y`). */
    suspend fun chart(code: String, range: ChartRange): StocksResult<ChartBody> =
        call(StocksRequest(GET, "chart/$code", mapOf("range" to range.query)), ChartBody.serializer()) { it }

    suspend fun search(query: String): StocksResult<List<SearchResult>> =
        call(StocksRequest(GET, "search", mapOf("q" to query)), SearchBody.serializer()) { it.results }

    /** Starts an analysis on the PC (§20.1, `202`); [lang] `id` or `en` ([AnalysisLang], Q101). */
    suspend fun analyze(code: String, lang: String): StocksResult<AnalysisStarted> {
        val body = buildJsonObject { put("lang", lang) }
        return call(StocksRequest(POST, "analyze/$code", body = body.toString()), AnalysisStarted.serializer()) { it }
    }

    /** The latest job for [code] and the kept verdict (§20.1); answers while Hermes is down. */
    suspend fun analysis(code: String): StocksResult<AnalysisStatus> =
        call(StocksRequest(GET, "analysis/$code"), AnalysisStatus.serializer()) { it }

    /** The screener list (§20.3); without [mode] the server picks: today's scans when one ran today, else the last close. */
    suspend fun screener(mode: ScreenerMode? = null): StocksResult<ScreenerBody> {
        val query = mode?.let { mapOf("mode" to it.query) } ?: emptyMap()
        return call(StocksRequest(GET, "screener", query), ScreenerBody.serializer()) { it }
    }

    suspend fun screenerSettings(): StocksResult<ScreenerSettings> =
        call(StocksRequest(GET, "screener/settings"), ScreenerSettings.serializer()) { it }

    /** Replaces the whole settings object: the server needs every field ([ScreenerSettingsJson]). */
    suspend fun putScreenerSettings(settings: ScreenerSettings): StocksResult<ScreenerSettings> =
        call(StocksRequest(PUT, "screener/settings", body = ScreenerSettingsJson.encode(settings)), ScreenerSettings.serializer()) { it }

    /** Scan now (`202`); `429 too_soon` with `retry_after_s` within 2 min of the last scan, `409 scan_running`. */
    suspend fun scanNow(): StocksResult<ScanStarted> = call(StocksRequest(POST, "screener/scan"), ScanStarted.serializer()) { it }

    suspend fun jobs(): StocksResult<List<JobView>> = call(StocksRequest(GET, "jobs"), JobsBody.serializer()) { it.jobs }

    /** An interval job's minutes (`screener.scan`: 5..30, §20.2). */
    suspend fun putJobEvery(id: String, everyMin: Int): StocksResult<JobView> {
        val body = buildJsonObject { put("every_min", everyMin) }
        return call(StocksRequest(PUT, "jobs/$id", body = body.toString()), JobView.serializer()) { it }
    }

    /** The jobs and today's automatic analyses (§20.2, §20.4 `auto_runs`). */
    suspend fun jobsWithRuns(): StocksResult<JobsWithRuns> = call(StocksRequest(GET, "jobs"), JobsWithRuns.serializer()) { it }

    /** A job on or off (`alerts`: off = no alert and no automatic analysis at all, §20.2). */
    suspend fun putJobOn(id: String, on: Boolean): StocksResult<JobView> {
        val body = buildJsonObject { put("on", on) }
        return call(StocksRequest(PUT, "jobs/$id", body = body.toString()), JobView.serializer()) { it }
    }

    /**
     * The alerts after [after] (§20.4), held up to [waitSeconds] when none is there yet; without [after] the newest 50.
     * [lang] records the phone's language for the alert and brief texts (Q105).
     */
    suspend fun alerts(after: Long?, waitSeconds: Int = 0, lang: String? = null): StocksResult<AlertsBody> {
        val query = buildMap {
            after?.let { put("after", it.toString()) }
            if (waitSeconds > 0) put("wait", waitSeconds.toString())
            lang?.let { put("lang", it) }
        }
        val timeout = if (waitSeconds > 0) StockAlertWatcher.READ_TIMEOUT_SECONDS else null
        return call(StocksRequest(GET, "alerts", query, readTimeoutSeconds = timeout), AlertsBody.serializer()) { it }
    }

    /** A day's brief (§20.4); without [date] the latest (today's `pending` before its run). `404 no_brief` when none. */
    suspend fun brief(date: String? = null): StocksResult<BriefBody> {
        val query = date?.let { mapOf("date" to it) } ?: emptyMap()
        return call(StocksRequest(GET, "brief", query), BriefBody.serializer()) { it }
    }

    /** The kept briefs, newest first (≤ 7). */
    suspend fun briefs(): StocksResult<List<BriefSummary>> = call(StocksRequest(GET, "briefs"), BriefsBody.serializer()) { it.briefs }

    /** Runs today's brief again (on-demand AI, not counted in the automatic runs); [lang] `id` or `en`. */
    suspend fun rerunBrief(lang: String): StocksResult<BriefStarted> {
        val body = buildJsonObject { put("lang", lang) }
        return call(StocksRequest(POST, "brief", body = body.toString()), BriefStarted.serializer()) { it }
    }

    suspend fun universe(): StocksResult<UniverseBody> = call(StocksRequest(GET, "universe"), UniverseBody.serializer()) { it }

    /** Replaces the stock list on the PC (§20.2); [validFrom] is optional. */
    suspend fun putUniverse(lq45: List<String>, idx80: List<String>, validTo: String, validFrom: String?): StocksResult<UniverseBody> {
        val body = buildJsonObject {
            put("lq45", buildJsonArray { lq45.forEach { add(it) } })
            put("idx80", buildJsonArray { idx80.forEach { add(it) } })
            put("valid_to", validTo)
            validFrom?.let { put("valid_from", it) }
        }
        return call(StocksRequest(PUT, "universe", body = body.toString()), UniverseBody.serializer()) { it }
    }

    /** Back to the bundled list (idempotent). */
    suspend fun resetUniverse(): StocksResult<UniverseBody> = call(StocksRequest(DELETE, "universe"), UniverseBody.serializer()) { it }

    private suspend fun <B, T> call(request: StocksRequest, serializer: KSerializer<B>, pick: (B) -> T): StocksResult<T> =
        when (val reply = send(request)) {
            is StocksReply.Failed -> StocksResult.Failed(StocksError.Unreachable(reply.problem))
            is StocksReply.Http -> if (reply.status in SUCCESS) {
                StocksParser.decode(reply.body, serializer)?.let { StocksResult.Ok(pick(it)) } ?: StocksResult.Failed(StocksError.BadAnswer)
            } else {
                StocksResult.Failed(StocksParser.error(reply))
            }
        }

    companion object {
        const val GET = "GET"
        const val PUT = "PUT"
        const val DELETE = "DELETE"
        const val POST = "POST"

        /** `200`, and `202` for a started analysis. */
        private val SUCCESS = 200..299

        /** The news feed reads this many (the server keeps 300). */
        const val NEWS_LIMIT = 100
    }
}

/** Reads `/v1/stocks/…` bodies; null for a body that is not the expected JSON. */
object StocksParser {
    fun <T> decode(text: String, serializer: KSerializer<T>): T? = runCatching { HermesJson.decodeFromString(serializer, text) }.getOrNull()

    fun watchlist(text: String): List<WatchEntry>? = decode(text, WatchlistBody.serializer())?.items
    fun quotes(text: String): QuotesBody? = decode(text, QuotesBody.serializer())
    fun holdings(text: String): HoldingsBody? = decode(text, HoldingsBody.serializer())
    fun fees(text: String): StockFees? = decode(text, StockFees.serializer())
    fun news(text: String): NewsBody? = decode(text, NewsBody.serializer())
    fun search(text: String): List<SearchResult>? = decode(text, SearchBody.serializer())?.results
    fun chart(text: String): ChartBody? = decode(text, ChartBody.serializer())
    fun analysis(text: String): AnalysisStatus? = decode(text, AnalysisStatus.serializer())
    fun screener(text: String): ScreenerBody? = decode(text, ScreenerBody.serializer())
    fun screenerSettings(text: String): ScreenerSettings? = decode(text, ScreenerSettings.serializer())
    fun jobs(text: String): List<JobView>? = decode(text, JobsBody.serializer())?.jobs
    fun universe(text: String): UniverseBody? = decode(text, UniverseBody.serializer())

    /** An error answer: the body's `error` and `retry_after_s`, the `Retry-After` header when the body has none. */
    fun error(reply: StocksReply.Http): StocksError.Server {
        val body = decode(reply.body, ErrorBody.serializer())
        return StocksError.Server(reply.status, body?.error, body?.retryAfterSeconds ?: reply.retryAfterSeconds)
    }
}
