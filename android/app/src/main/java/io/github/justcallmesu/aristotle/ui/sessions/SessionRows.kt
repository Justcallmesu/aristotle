package io.github.justcallmesu.aristotle.ui.sessions

import io.github.justcallmesu.aristotle.data.SessionSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The list's sections, top to bottom (Q48, mockup screen 2): pinned on this phone, live in Hermes, stored history. */
enum class ListSection { Pinned, Live, History }

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
    /** Pinned on this phone (T14; Hermes' own pin is not readable over the relay). */
    val pinned: Boolean = false,
    /** From the stored history: opening it resumes it, as the Desktop does (`session.resume`). */
    val stored: Boolean = false,
)

/** How the history ends: more pages to load, Hermes' paging limit reached, or nothing more. */
/** [Failed]: the history call failed (the live rows above are good); the footer offers Retry. */
enum class HistoryEnd { More, Loading, Capped, All, Failed }

sealed interface SessionListItem {
    data class Header(val section: ListSection) : SessionListItem
    data class Row(val row: SessionRow) : SessionListItem

    /** Under the history: load more, loading, or the limit of what the relay can page to. */
    data class Footer(val end: HistoryEnd) : SessionListItem

    /** A search matched nothing in the rows loaded so far. */
    data object NoMatch : SessionListItem
}

/** What the list shows: the merged rows of one listing and the phone's own state over them. */
data class ListInput(
    val live: List<SessionSummary>,
    val stored: List<SessionSummary>,
    val pins: Set<String> = emptySet(),
    /** Search text; blank = no search. Matches titles of the loaded rows only (no search over the relay). */
    val query: String = "",
    val end: HistoryEnd = HistoryEnd.All,
)

/**
 * Pinned rows first (in list order), then live sessions (running first, then newest started), then stored history in
 * Hermes' order (newest activity first, `gateway/session_listing.py:42-43`). A pinned row shows only under Pinned.
 * Empty sections have no header. A search keeps the rows whose title contains the text (case-insensitive; an untitled
 * row never matches) and drops the history footer unless more can be loaded.
 */
fun sessionListItems(input: ListInput, now: ZonedDateTime, locale: Locale): List<SessionListItem> {
    val today = now.toLocalDate()
    val search = input.query.trim()
    fun matches(s: SessionSummary) = search.isEmpty() || s.title.contains(search, ignoreCase = true)
    val live = input.live
        .sortedWith(compareByDescending<SessionSummary> { it.running }.thenByDescending { it.lastActivitySeconds ?: Double.NEGATIVE_INFINITY })
        .filter(::matches)
        .map { sessionRow(it, now, today, locale).copy(pinned = it.key in input.pins) }
    val stored = input.stored.filter(::matches).map { sessionRow(it, now, today, locale).copy(pinned = it.key in input.pins, stored = true) }
    val pinned = (live + stored).filter { it.pinned }
    val items = mutableListOf<SessionListItem>()
    fun section(section: ListSection, rows: List<SessionRow>) {
        if (rows.isEmpty()) return
        items += SessionListItem.Header(section)
        rows.mapTo(items) { SessionListItem.Row(it) }
    }
    section(ListSection.Pinned, pinned)
    section(ListSection.Live, live.filterNot { it.pinned })
    section(ListSection.History, stored.filterNot { it.pinned })
    if (search.isNotEmpty() && items.isEmpty()) items += SessionListItem.NoMatch
    val footer = input.end != HistoryEnd.All &&
        (search.isEmpty() || input.end == HistoryEnd.More || input.end == HistoryEnd.Loading || input.end == HistoryEnd.Failed)
    if (footer) items += SessionListItem.Footer(input.end)
    return items
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

private fun zoned(session: SessionSummary, now: ZonedDateTime): ZonedDateTime? {
    val seconds = session.lastActivitySeconds ?: return null
    if (seconds <= 0.0 || seconds.isNaN()) return null
    return Instant.ofEpochMilli((seconds * MILLIS).toLong()).atZone(now.zone)
}

private const val MILLIS = 1000.0

/** Entries of the session list's ⋮ menu (Q70), in display order. */
enum class ListMenuEntry { Scheduled, Terminal, Settings }

/** Scheduled and Settings always; Terminal only while the terminal is on and this phone is granted (T19b). */
fun listMenuEntries(terminalAvailable: Boolean): List<ListMenuEntry> =
    if (terminalAvailable) listOf(ListMenuEntry.Scheduled, ListMenuEntry.Terminal, ListMenuEntry.Settings)
    else listOf(ListMenuEntry.Scheduled, ListMenuEntry.Settings)
