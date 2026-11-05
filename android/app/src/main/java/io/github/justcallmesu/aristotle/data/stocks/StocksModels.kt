package io.github.justcallmesu.aristotle.data.stocks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The `/v1/stocks/…` bodies (docs/protocol.md §20). Prices are integer IDR, times RFC 3339 in WIB. Read with
// HermesJson (unknown keys ignored, a null for a defaulted field takes the default), so a newer server's extra
// fields or a missing optional one never fail a whole answer.

/** One watchlist entry, in the user's order. */
@Serializable
data class WatchEntry(val code: String, val name: String = "")

@Serializable
internal data class WatchlistBody(val items: List<WatchEntry> = emptyList())

/** `market`: `open` (with [session] and [closesAt]), `break` or `closed` (with [nextOpen]). */
@Serializable
data class MarketStatus(
    val state: String = "",
    val session: Int? = null,
    @SerialName("closes_at") val closesAt: String? = null,
    @SerialName("next_open") val nextOpen: String? = null,
) {
    val isOpen: Boolean get() = state == OPEN

    companion object {
        const val OPEN = "open"
        const val BREAK = "break"
        const val CLOSED = "closed"
    }
}

/** One quote. [change] / [changePct] are null without a previous close; [spark] is today's 5-minute closes, oldest first. */
@Serializable
data class StockQuote(
    val code: String,
    val name: String = "",
    val price: Long,
    @SerialName("prev_close") val prevClose: Long? = null,
    val change: Long? = null,
    @SerialName("change_pct") val changePct: Double? = null,
    val high: Long? = null,
    val low: Long? = null,
    val volume: Long? = null,
    @SerialName("week52_high") val week52High: Long? = null,
    @SerialName("week52_low") val week52Low: Long? = null,
    @SerialName("market_time") val marketTime: String? = null,
    val spark: List<Long> = emptyList(),
    val tick: Long? = null,
    val ara: Long? = null,
    val arb: Long? = null,
    /** A cached price past its time to live: the source refused or failed meanwhile. */
    val stale: Boolean = false,
)

/** A code without a quote and why: `unknown_symbol`, `upstream_blocked` or `upstream_error`. */
@Serializable
data class QuoteError(val code: String, val error: String = "")

@Serializable
data class QuotesBody(
    val at: String = "",
    val market: MarketStatus = MarketStatus(),
    val quotes: List<StockQuote> = emptyList(),
    val errors: List<QuoteError> = emptyList(),
) {
    fun quoteOf(code: String): StockQuote? = quotes.firstOrNull { it.code == code }

    /**
     * The time the prices are from: the newest `market_time` of a quote (the source's last trade, about 10 minutes behind),
     * else the server's [at].
     */
    val asOf: String get() = quotes.mapNotNull { it.marketTime }.maxOrNull() ?: at
}

/** One holding priced by the server; the price-dependent fields are null without a price. */
@Serializable
data class HoldingRow(
    val code: String,
    val name: String = "",
    val lots: Long,
    @SerialName("avg_price") val avgPrice: Double,
    val price: Long? = null,
    val cost: Long = 0,
    val value: Long? = null,
    val pl: Long? = null,
    @SerialName("pl_pct") val plPct: Double? = null,
    val today: Long? = null,
    val stale: Boolean = false,
)

@Serializable
data class HoldingsTotal(
    val cost: Long = 0,
    val value: Long? = null,
    val pl: Long? = null,
    @SerialName("pl_pct") val plPct: Double? = null,
    val today: Long? = null,
)

@Serializable
data class HoldingsBody(val at: String = "", val holdings: List<HoldingRow> = emptyList(), val total: HoldingsTotal = HoldingsTotal()) {
    fun rowOf(code: String): HoldingRow? = holdings.firstOrNull { it.code == code }

    /** Largest value first (the board's "By value"); a row without a price sorts by its cost. */
    val byValue: List<HoldingRow> get() = holdings.sortedByDescending { it.value ?: it.cost }
}

/** Broker fees in percent (spec Q88): defaults 0.15 buy and 0.25 sell (incl. tax). */
@Serializable
data class StockFees(
    @SerialName("buy_fee_pct") val buyPct: Double = DEFAULT_BUY,
    @SerialName("sell_fee_pct") val sellPct: Double = DEFAULT_SELL,
) {
    companion object {
        const val DEFAULT_BUY = 0.15
        const val DEFAULT_SELL = 0.25
        const val MAX_PCT = 5.0
    }
}

@Serializable
data class NewsItem(
    val id: String = "",
    val source: String = "",
    val title: String = "",
    val link: String = "",
    @SerialName("published_at") val publishedAt: String = "",
    val summary: String = "",
    @SerialName("image_url") val imageUrl: String? = null,
    val codes: List<String> = emptyList(),
    val match: String? = null,
)

@Serializable
data class NewsSource(
    val source: String = "",
    val ok: Boolean = false,
    @SerialName("fetched_at") val fetchedAt: String? = null,
    val error: String? = null,
)

@Serializable
data class NewsBody(val items: List<NewsItem> = emptyList(), val sources: List<NewsSource> = emptyList())

@Serializable
data class SearchResult(val code: String, val name: String = "", @SerialName("in_universe") val inUniverse: Boolean = false)

@Serializable
internal data class SearchBody(val results: List<SearchResult> = emptyList())

@Serializable
internal data class ErrorBody(val error: String? = null, @SerialName("retry_after_s") val retryAfterSeconds: Int? = null)
