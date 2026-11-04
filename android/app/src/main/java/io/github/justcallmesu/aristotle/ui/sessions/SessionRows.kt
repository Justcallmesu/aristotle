package io.github.justcallmesu.aristotle.ui.sessions

import io.github.justcallmesu.aristotle.data.HistoryGrouping
import io.github.justcallmesu.aristotle.data.HistorySort
import io.github.justcallmesu.aristotle.data.SessionSummary
import io.github.justcallmesu.aristotle.data.StatusBucket
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
    /** The Home C ledger day this row files under (phone's local date of last activity); null = no time known. */
    val day: RowDay? = null,
)

/** Home C ledger day groups (design-notes §5.1), top to bottom. */
enum class DayGroup { Today, Yesterday, Earlier }

/** [date]: the day as the header writes it ("Wed 4 Nov"); empty for [DayGroup.Earlier], whose header has no date. */
data class RowDay(val group: DayGroup, val date: String)

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
    day = rowDay(session, now, today, locale),
)

private fun rowDay(session: SessionSummary, now: ZonedDateTime, today: LocalDate, locale: Locale): RowDay? {
    val date = zoned(session, now)?.toLocalDate() ?: return null
    val group = when (date) {
        today -> DayGroup.Today
        today.minusDays(1) -> DayGroup.Yesterday
        else -> return RowDay(DayGroup.Earlier, "")
    }
    return RowDay(group, date.format(DateTimeFormatter.ofPattern(DAY_HEADER, locale)))
}

private const val DAY_HEADER = "EEE d MMM"

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

/** Home C (Q75): Scheduled has its own bottom-bar button, so the ⋮ menu holds Terminal (when shown) and Settings. */
fun homeMenuEntries(terminalAvailable: Boolean): List<ListMenuEntry> = listMenuEntries(terminalAvailable) - ListMenuEntry.Scheduled

/** Section headers of the Home C ledger: pinned rows, then history by day. */
enum class LedgerSection { Pinned, Today, Yesterday, Earlier }

/** One line of the Home C ledger (everything under the live cards). */
sealed interface LedgerItem {
    /** [date]: "Wed 4 Nov" after Today / Yesterday; null for Pinned and Earlier. */
    data class Header(val section: LedgerSection, val date: String? = null) : LedgerItem
    data class Row(val row: SessionRow) : LedgerItem
    data class Footer(val end: HistoryEnd) : LedgerItem
    data object NoMatch : LedgerItem

    /** Q81d: the history's "Group · Sort" control, above Pinned; [sort] is the one applied. */
    data class ViewBar(val grouping: HistoryGrouping, val sort: HistorySort) : LedgerItem

    /** Grouped by status: one bucket, in the Desktop's order. */
    data class StatusHeader(val bucket: StatusBucket) : LedgerItem

    /** Grouped by project: its name ([home] = Hermes' Home bucket), Hermes' session count, folded or open. */
    data class ProjectHeader(val id: String, val label: String, val count: Int, val collapsed: Boolean, val home: Boolean = false) : LedgerItem

    /** Under a project's rows: more to show, the drill-in loading, or it failed. */
    data class ProjectFooter(val id: String, val more: ProjectMore) : LedgerItem

    /** The project tree is being read. */
    data object ProjectsLoading : LedgerItem

    /** `projects.tree` failed; the line offers Retry. */
    data object ProjectsFailed : LedgerItem

    /** The tree came back without a project that has sessions. */
    data object ProjectsEmpty : LedgerItem

    /** The tree reads only the newest [io.github.justcallmesu.aristotle.data.ProjectFrames.SESSION_LIMIT] sessions. */
    data object ProjectsCapped : LedgerItem
}

/** Home C: live sessions as cards on top, then the ledger. */
data class HomeList(val live: List<SessionRow>, val ledger: List<LedgerItem>)

/**
 * Home C layout (design-notes §5.1) over [sessionListItems]' order: the Live section becomes the cards; Pinned stays a
 * ledger section; History splits into Today, Yesterday and Earlier by each row's [SessionRow.day] (no day = Earlier),
 * keeping Hermes' order inside a day. The no-match line and the history footer keep their places at the end.
 */
fun homeList(items: List<SessionListItem>): HomeList {
    var section: ListSection? = null
    val live = mutableListOf<SessionRow>()
    val pinned = mutableListOf<SessionRow>()
    val history = mutableListOf<SessionRow>()
    var tail: List<LedgerItem> = emptyList()
    for (item in items) {
        when (item) {
            is SessionListItem.Header -> section = item.section
            is SessionListItem.Row -> when (section) {
                ListSection.Pinned -> pinned += item.row
                ListSection.Live -> live += item.row
                ListSection.History, null -> history += item.row
            }
            is SessionListItem.Footer -> tail = tail + LedgerItem.Footer(item.end)
            SessionListItem.NoMatch -> tail = tail + LedgerItem.NoMatch
        }
    }
    val ledger = mutableListOf<LedgerItem>()
    if (pinned.isNotEmpty()) {
        ledger += LedgerItem.Header(LedgerSection.Pinned)
        pinned.mapTo(ledger) { LedgerItem.Row(it) }
    }
    val byDay = history.groupBy { it.day?.group ?: DayGroup.Earlier }
    DayGroup.entries.forEach { group ->
        val rows = byDay[group] ?: return@forEach
        val header = when (group) {
            DayGroup.Today -> LedgerItem.Header(LedgerSection.Today, rows.first().day?.date)
            DayGroup.Yesterday -> LedgerItem.Header(LedgerSection.Yesterday, rows.first().day?.date)
            DayGroup.Earlier -> LedgerItem.Header(LedgerSection.Earlier)
        }
        ledger += header
        rows.mapTo(ledger) { LedgerItem.Row(it) }
    }
    ledger += tail
    return HomeList(live, ledger)
}

/** design-notes §5.1: never more than two notices; What's new waits while the offline and compatibility notices both show. */
fun showWhatsNewNotice(offline: Boolean, incompatible: Boolean): Boolean = !(offline && incompatible)
