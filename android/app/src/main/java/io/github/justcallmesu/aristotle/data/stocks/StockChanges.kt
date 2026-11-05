package io.github.justcallmesu.aristotle.data.stocks

/** Which way a value moved; [Unknown] when there is no value (no previous close, no price). */
enum class Direction {
    Up, Down, Flat, Unknown;

    companion object {
        fun of(value: Long?): Direction = when {
            value == null -> Unknown
            value > 0 -> Up
            value < 0 -> Down
            else -> Flat
        }

        fun of(value: Double?): Direction = when {
            value == null || value.isNaN() -> Unknown
            value > 0 -> Up
            value < 0 -> Down
            else -> Flat
        }
    }
}

/**
 * A change as drawn (spec Q91): the direction picks the arrow (up / down / a dash for flat) and the stock colour, and the
 * [text] always carries the sign, so colour is never the only cue. [Direction.Unknown] has no arrow and an em dash.
 */
data class ChangeDisplay(val direction: Direction, val text: String)

/** Pure mapping from the server's numbers to [ChangeDisplay]s (P/L display mapping, stocks-notes §3). */
object StockChanges {
    /** A quote's day change: "+90 · +1,86 %"; without a previous close, unknown. */
    fun quote(quote: StockQuote): ChangeDisplay {
        val change = quote.change ?: return unknown()
        val pct = quote.changePct?.let { " · " + StockFormat.percent(it) }.orEmpty()
        return ChangeDisplay(Direction.of(change), StockFormat.signedNumber(change) + pct)
    }

    /** Only the percent of a quote's day change ("+1,86 %"), for chips. */
    fun quotePercent(quote: StockQuote?): ChangeDisplay {
        val pct = quote?.changePct ?: return unknown()
        return ChangeDisplay(Direction.of(pct), StockFormat.percent(pct))
    }

    /** P/L after fees: "+Rp 110.475 · +2,30 %", the direction from the amount. */
    fun profit(pl: Long?, plPct: Double?): ChangeDisplay {
        pl ?: return unknown()
        val pct = plPct?.let { " · " + StockFormat.percent(it) }.orEmpty()
        return ChangeDisplay(Direction.of(pl), StockFormat.signedRupiah(pl) + pct)
    }

    /** Today's move in rupiah: "+Rp 90.000". */
    fun today(today: Long?): ChangeDisplay = today?.let { ChangeDisplay(Direction.of(it), StockFormat.signedRupiah(it)) } ?: unknown()

    private fun unknown() = ChangeDisplay(Direction.Unknown, StockFormat.missing)
}
