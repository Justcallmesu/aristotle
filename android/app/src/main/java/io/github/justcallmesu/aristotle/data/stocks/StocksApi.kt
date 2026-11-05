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

/** One `/v1/stocks/…` exchange: [path] under `/v1/stocks/`, [query] parameters, an optional JSON [body]. */
data class StocksRequest(val method: String, val path: String, val query: Map<String, String> = emptyMap(), val body: String? = null)

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

    /** Adds or replaces a holding (idempotent). [avgPrice] is sent as the server keeps it, to 0.01 IDR. */
    suspend fun putHolding(code: String, lots: Long, avgPrice: Double): StocksResult<HoldingsBody> {
        val body = buildJsonObject {
            put("lots", lots)
            put("avg_price", avgPrice)
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

    suspend fun search(query: String): StocksResult<List<SearchResult>> =
        call(StocksRequest(GET, "search", mapOf("q" to query)), SearchBody.serializer()) { it.results }

    private suspend fun <B, T> call(request: StocksRequest, serializer: KSerializer<B>, pick: (B) -> T): StocksResult<T> =
        when (val reply = send(request)) {
            is StocksReply.Failed -> StocksResult.Failed(StocksError.Unreachable(reply.problem))
            is StocksReply.Http -> if (reply.status == OK) {
                StocksParser.decode(reply.body, serializer)?.let { StocksResult.Ok(pick(it)) } ?: StocksResult.Failed(StocksError.BadAnswer)
            } else {
                StocksResult.Failed(StocksParser.error(reply))
            }
        }

    companion object {
        const val GET = "GET"
        const val PUT = "PUT"
        const val DELETE = "DELETE"
        private const val OK = 200

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

    /** An error answer: the body's `error` and `retry_after_s`, the `Retry-After` header when the body has none. */
    fun error(reply: StocksReply.Http): StocksError.Server {
        val body = decode(reply.body, ErrorBody.serializer())
        return StocksError.Server(reply.status, body?.error, body?.retryAfterSeconds ?: reply.retryAfterSeconds)
    }
}
