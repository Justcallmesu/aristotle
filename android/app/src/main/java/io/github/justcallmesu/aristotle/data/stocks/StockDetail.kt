package io.github.justcallmesu.aristotle.data.stocks

/** The detail page's holding block (board "Your holding"): lots · avg, value, today, P/L after fees. */
data class HoldingView(val lots: Long, val avgPrice: Double, val value: Long?, val today: Long?, val pl: Long?, val plPct: Double?)

object HoldingBlock {
    /**
     * The block for [row]. With a fresher [quote] than the holdings answer and the [fees] known, the P/L after fees is
     * worked out again by the server's rule ([HoldingMath.preview]) and today as (last − prev close) × shares;
     * otherwise the server's own numbers stand.
     */
    fun of(row: HoldingRow, quote: StockQuote?, fees: StockFees?): HoldingView {
        val price = quote?.price
        if (price == null || fees == null) return HoldingView(row.lots, row.avgPrice, row.value, row.today, row.pl, row.plPct)
        val p = HoldingMath.preview(row.lots, row.avgPrice, price, fees)
        val today = quote.change?.let { it * p.shares } ?: row.today
        return HoldingView(row.lots, row.avgPrice, p.value, today, p.pl, p.plPct)
    }
}

/** When the detail page reads a range's bars (TS3 brief §3). Pure. */
object ChartCache {
    /**
     * Read [range] when nothing is cached for it; 1D also once its copy is a minute old (it changes while the market is
     * open; the 60 s refresh keeps it current while it is shown). Other ranges stay cached while the page lives, unless
     * the copy is [stale] (the server's cache while Yahoo was not answering): that is read again on each show / pick.
     */
    fun due(range: ChartRange, fetchedAt: Long?, now: Long, stale: Boolean = false): Boolean =
        fetchedAt == null || stale || (range == ChartRange.Day && now - fetchedAt >= QuoteRefresh.INTERVAL_MILLIS)
}

/** Which model the chart draws while a finger holds the crosshair (TM3a): the 60 s refresh waits for the release. */
object ChartHold {
    /** The model to draw: [shown] (what is on screen) while [holding] a bar of the same series, else [fresh]. */
    fun <T : Any> pick(shown: T?, fresh: T?, holding: Boolean, sameSeries: Boolean): T? =
        if (holding && sameSeries && shown != null) shown else fresh
}

/** The ⋮ menu's and the broker button's small rules on the detail page (TM3a). Pure. */
object DetailRules {
    /** "Add / Edit holding" waits for the holdings answer: the PUT replaces, so an unread holding could be overwritten. */
    fun holdingEnabled(reachable: Boolean, holdingsLoaded: Boolean): Boolean = reachable && holdingsLoaded

    /** A broker tap: [open] it; when that fails (uninstalled meanwhile) say so and hide the button. */
    fun brokerTap(open: () -> Boolean, notInstalled: () -> Unit) {
        if (!open()) notInstalled()
    }
}

/** The day's opening price: the first 1D bar's open, when the 1D bars are of the quote's day (or there is no quote time). */
object TodayOpen {
    fun of(day: ChartBody?, quote: StockQuote?): Long? {
        val first = day?.bars?.firstOrNull() ?: return null
        val quoteDay = StockTimes.date(quote?.marketTime) ?: return first.o
        return first.o.takeIf { BarAxis.dateOf(first) == quoteDay }
    }
}
