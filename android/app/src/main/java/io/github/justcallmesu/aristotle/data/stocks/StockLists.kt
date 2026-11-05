package io.github.justcallmesu.aristotle.data.stocks

import java.time.LocalDate

/** Watchlist order on the phone (long press › Move up / Move down), sent whole to `PUT /v1/stocks/watchlist`. Pure. */
object WatchlistOrder {
    /** [codes] with [code] moved [by] places (−1 up, +1 down), clamped at the ends; unchanged if [code] is not listed. */
    fun move(codes: List<String>, code: String, by: Int): List<String> {
        val from = codes.indexOf(code)
        if (from < 0 || by == 0) return codes
        val to = (from + by).coerceIn(0, codes.lastIndex)
        if (to == from) return codes
        return codes.toMutableList().apply { add(to, removeAt(from)) }
    }

    fun canMove(codes: List<String>, code: String, by: Int): Boolean {
        val from = codes.indexOf(code)
        return from >= 0 && (from + by) in codes.indices
    }

    /** The entries in [order] (codes the server added meanwhile stay, at the end). */
    fun arrange(entries: List<WatchEntry>, order: List<String>): List<WatchEntry> =
        entries.sortedBy { order.indexOf(it.code).let { i -> if (i < 0) Int.MAX_VALUE else i } }
}

/** The News page filters (board: All / Watchlist / Holdings). */
enum class NewsFilter { All, Watchlist, Holdings }

/** A day of news: [date] in WIB and its items, newest first. */
data class NewsDay(val date: LocalDate?, val items: List<NewsItem>)

object NewsLists {
    /** Items whose matched codes meet [codes] (All: every item). The server matched them against both lists already. */
    fun filter(items: List<NewsItem>, filter: NewsFilter, watchlist: Collection<String>, holdings: Collection<String>): List<NewsItem> = when (filter) {
        NewsFilter.All -> items
        NewsFilter.Watchlist -> items.filter { item -> item.codes.any { it in watchlist } }
        NewsFilter.Holdings -> items.filter { item -> item.codes.any { it in holdings } }
    }

    /** Consecutive items by their WIB day, in the feed's order (newest first). */
    fun byDay(items: List<NewsItem>): List<NewsDay> {
        val out = mutableListOf<NewsDay>()
        items.forEach { item ->
            val date = StockTimes.date(item.publishedAt)
            val last = out.lastOrNull()
            if (last != null && last.date == date) out[out.lastIndex] = last.copy(items = last.items + item) else out += NewsDay(date, listOf(item))
        }
        return out
    }

    /** The newest successful fetch of any source (the page's "checked 10:40"). */
    fun checkedAt(sources: List<NewsSource>): String? = sources.mapNotNull { it.fetchedAt }.maxOrNull()

    /** The sources that failed their last fetch. */
    fun failing(sources: List<NewsSource>): List<String> = sources.filter { !it.ok }.map { it.source }
}

/**
 * The watchlist sparkline (stocks-notes §3: 64 × 28, today's 5-minute closes, a dotted previous-close line) as points
 * in a [width] × [height] box with [inset] on every side. Pure, so its scaling is tested.
 */
object SparkGeometry {
    data class Shape(val points: List<Pair<Float, Float>>, val baselineY: Float?)

    fun of(closes: List<Long>, prevClose: Long?, width: Float, height: Float, inset: Float): Shape? {
        if (closes.size < 2) return null
        val all = closes + listOfNotNull(prevClose)
        val lo = all.min()
        val hi = all.max()
        val span = (hi - lo).takeIf { it > 0 }?.toFloat() ?: 1f
        val innerW = width - 2 * inset
        val innerH = height - 2 * inset
        fun y(v: Long): Float = if (hi == lo) height / 2 else inset + innerH * (1f - (v - lo) / span)
        val points = closes.mapIndexed { i, v -> (inset + innerW * i / (closes.size - 1)) to y(v) }
        return Shape(points, prevClose?.let(::y))
    }
}
