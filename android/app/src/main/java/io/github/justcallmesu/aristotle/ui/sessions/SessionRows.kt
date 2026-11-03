package io.github.justcallmesu.aristotle.ui.sessions

import io.github.justcallmesu.aristotle.data.SessionSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class DaySection { Today, Yesterday, Earlier }

/** Trailing text of a session row: "now" while running, else the last-activity time. */
sealed interface RowTime {
    data object Now : RowTime
    data class At(val text: String) : RowTime
}

/** A formatted session row (design.md §5 "Session row"). A null [title] renders as "Untitled session". */
data class SessionRow(
    val key: String,
    val title: String?,
    val running: Boolean,
    val messageCount: Int,
    val time: RowTime?,
)

sealed interface SessionListItem {
    data class Header(val section: DaySection) : SessionListItem
    data class Row(val row: SessionRow) : SessionListItem
}

/**
 * Groups `session.list` rows into Today / Yesterday / Earlier by last activity in [now]'s zone.
 * Running sessions count as active now. Within a section: running first, then newest first.
 */
fun sessionListItems(sessions: List<SessionSummary>, now: ZonedDateTime, locale: Locale): List<SessionListItem> {
    val today = now.toLocalDate()
    return sessions
        .map { it to sectionOf(it, now) }
        .sortedWith(compareBy<Pair<SessionSummary, DaySection>> { it.second }
            .thenByDescending { it.first.running }
            .thenByDescending { it.first.lastActivitySeconds ?: Double.NEGATIVE_INFINITY })
        .groupBy({ it.second }, { it.first })
        .flatMap { (section, rows) ->
            listOf(SessionListItem.Header(section)) + rows.map { SessionListItem.Row(sessionRow(it, now, today, locale)) }
        }
}

fun sectionOf(session: SessionSummary, now: ZonedDateTime): DaySection {
    if (session.running) return DaySection.Today
    val date = localDate(session, now) ?: return DaySection.Earlier
    val today = now.toLocalDate()
    return when (date) {
        today -> DaySection.Today
        today.minusDays(1) -> DaySection.Yesterday
        else -> DaySection.Earlier
    }
}

fun sessionRow(session: SessionSummary, now: ZonedDateTime, today: LocalDate, locale: Locale): SessionRow = SessionRow(
    key = session.key,
    title = session.title.trim().ifEmpty { null },
    running = session.running,
    messageCount = session.messageCount,
    time = rowTime(session, now, today, locale),
)

private fun rowTime(session: SessionSummary, now: ZonedDateTime, today: LocalDate, locale: Locale): RowTime? {
    if (session.running) return RowTime.Now
    val at = zoned(session, now) ?: return null
    val date = at.toLocalDate()
    val pattern = when {
        date == today || date == today.minusDays(1) -> "HH:mm"
        date.year == today.year -> "d MMM"
        else -> "d MMM yyyy"
    }
    return RowTime.At(at.format(DateTimeFormatter.ofPattern(pattern, locale)))
}

private fun localDate(session: SessionSummary, now: ZonedDateTime): LocalDate? = zoned(session, now)?.toLocalDate()

private fun zoned(session: SessionSummary, now: ZonedDateTime): ZonedDateTime? {
    val seconds = session.lastActivitySeconds ?: return null
    if (seconds <= 0.0 || seconds.isNaN()) return null
    return Instant.ofEpochMilli((seconds * MILLIS).toLong()).atZone(now.zone)
}

private const val MILLIS = 1000.0
