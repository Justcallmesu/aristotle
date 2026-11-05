package io.github.justcallmesu.aristotle.data.stocks

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** The market line's first line (stocks-notes §3 `MarketStatusLine`), before it becomes words. */
sealed interface MarketLine {
    /** "Session 1 · closes 12:00". */
    data class Open(val session: Int?, val closesAt: String?) : MarketLine

    /** "Session break · session 2 opens 13:30". */
    data class Break(val opensAt: String?) : MarketLine

    /** "Market closed · opens Fri 09:00" ([opensAt] carries the day only when it is not the same day). */
    data class Closed(val opensAt: String?) : MarketLine

    data object Unknown : MarketLine
}

/**
 * Times and days of the stocks screens, in WIB (the exchange's zone, which the server already writes, `+07:00`) and the
 * app's locale for day and month names. Pure: the locale and "today" are parameters.
 */
object StockTimes {
    val WIB: ZoneId = ZoneId.of("Asia/Jakarta")
    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

    fun parse(rfc3339: String?): OffsetDateTime? = rfc3339?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }

    /** "10:42" in WIB; null for a missing or unreadable time. */
    fun clock(rfc3339: String?): String? = parse(rfc3339)?.atZoneSameInstant(WIB)?.format(CLOCK)

    /** "10:42" on [today] (WIB), else "Fri 09:00" / "Jum 09:00". */
    fun dayClock(rfc3339: String?, today: LocalDate, locale: Locale): String? {
        val at = parse(rfc3339)?.atZoneSameInstant(WIB) ?: return null
        val time = at.format(CLOCK)
        return if (at.toLocalDate() == today) time else "${at.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)} $time"
    }

    /** "Thu 5 Nov" / "Kam 5 Nov" for news day headers. */
    fun day(date: LocalDate, locale: Locale): String =
        "${date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)} ${date.dayOfMonth} ${date.month.getDisplayName(TextStyle.SHORT, locale)}"

    /** The WIB date of a server time. */
    fun date(rfc3339: String?): LocalDate? = parse(rfc3339)?.atZoneSameInstant(WIB)?.toLocalDate()

    fun today(nowMillis: Long): LocalDate = java.time.Instant.ofEpochMilli(nowMillis).atZone(WIB).toLocalDate()

    /** The market line for [market], with times read against [today]. */
    fun marketLine(market: MarketStatus?, today: LocalDate, locale: Locale): MarketLine = when (market?.state) {
        MarketStatus.OPEN -> MarketLine.Open(market.session, clock(market.closesAt))
        MarketStatus.BREAK -> MarketLine.Break(clock(market.nextOpen))
        MarketStatus.CLOSED -> MarketLine.Closed(dayClock(market.nextOpen, today, locale))
        else -> MarketLine.Unknown
    }
}
