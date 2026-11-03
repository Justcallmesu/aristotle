package io.github.justcallmesu.aristotle.ui.sessions

import io.github.justcallmesu.aristotle.data.SessionSummary
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionRowsTest {
    private val zone = ZoneId.of("Asia/Jakarta")
    private val now = ZonedDateTime.of(2026, 11, 3, 22, 31, 0, 0, zone)

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Double =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toEpochSecond().toDouble()

    private fun session(id: String, started: Double?, running: Boolean = false, title: String = id, messages: Int = 3) =
        SessionSummary(sessionId = id, id = id, title = title, startedAt = started, messageCount = messages, running = running)

    private fun shape(items: List<SessionListItem>): List<String> = items.map {
        when (it) {
            is SessionListItem.Header -> "#${it.section}"
            is SessionListItem.Row -> it.row.key + if (it.row.stored) "(stored)" else ""
            is SessionListItem.Footer -> "footer:${it.end}"
            SessionListItem.NoMatch -> "no-match"
        }
    }

    // Q48: live sessions on top (running first, then newest), stored history below in Hermes' order.
    @Test
    fun liveOnTopThenHistoryInHermesOrder() {
        val live = listOf(
            session("idle-new", at(2026, 11, 3, 21, 40)),
            session("old-running", at(2026, 10, 1, 9, 0), running = true),
            session("idle-old", at(2026, 11, 2, 23, 5)),
        )
        val stored = listOf(session("h2", at(2026, 10, 1, 9, 0)), session("h1", at(2026, 11, 1, 9, 0)))
        val items = sessionListItems(ListInput(live, stored, end = HistoryEnd.More), now, Locale.ENGLISH)
        assertEquals(
            listOf("#Live", "old-running", "idle-new", "idle-old", "#History", "h2(stored)", "h1(stored)", "footer:More"),
            shape(items),
        )
    }

    @Test
    fun pinnedRowsLeadAndShowOnlyThere() {
        val live = listOf(session("a", at(2026, 11, 3, 21, 0)), session("b", at(2026, 11, 3, 20, 0)))
        val stored = listOf(session("h", at(2026, 10, 1, 9, 0)))
        val items = sessionListItems(ListInput(live, stored, pins = setOf("h", "b")), now, Locale.ENGLISH)
        assertEquals(listOf("#Pinned", "b", "h(stored)", "#Live", "a"), shape(items))
        assertEquals(listOf(true, true, false), items.rows().map { it.pinned })
    }

    // T14: search is over the titles of the rows loaded on the phone (the relay has no session search).
    @Test
    fun searchFiltersLoadedTitlesCaseInsensitively() {
        val live = listOf(session("a", null, title = "Fix flaky relay test"), session("b", null, title = "IDX calendar"))
        val stored = listOf(session("c", null, title = "Review RELAY listener"), session("d", null, title = " "))
        val hit = sessionListItems(ListInput(live, stored, query = " relay ", end = HistoryEnd.More), now, Locale.ENGLISH)
        assertEquals(listOf("#Live", "a", "#History", "c(stored)", "footer:More"), shape(hit))
        val miss = sessionListItems(ListInput(live, stored, query = "untitled", end = HistoryEnd.Capped), now, Locale.ENGLISH)
        assertEquals(listOf("no-match"), shape(miss))
    }

    // T14b: a failed history shows its Retry row, searching or not (the live rows above are good).
    @Test
    fun aFailedHistoryKeepsItsRetryRowEvenWhileSearching() {
        val live = listOf(session("a", null, title = "relay"))
        assertEquals(listOf("#Live", "a", "footer:Failed"), shape(sessionListItems(ListInput(live, emptyList(), end = HistoryEnd.Failed), now, Locale.ENGLISH)))
        assertEquals(listOf("#Live", "a", "footer:Failed"), shape(sessionListItems(ListInput(live, emptyList(), query = "rel", end = HistoryEnd.Failed), now, Locale.ENGLISH)))
    }

    @Test
    fun historyEndsSayWhyThereIsNoMore() {
        val stored = listOf(session("h", null))
        assertEquals("footer:Capped", shape(sessionListItems(ListInput(emptyList(), stored, end = HistoryEnd.Capped), now, Locale.ENGLISH)).last())
        assertEquals("footer:Loading", shape(sessionListItems(ListInput(emptyList(), stored, end = HistoryEnd.Loading), now, Locale.ENGLISH)).last())
        assertEquals(listOf("#History", "h(stored)"), shape(sessionListItems(ListInput(emptyList(), stored), now, Locale.ENGLISH)))
        assertEquals(emptyList<String>(), shape(sessionListItems(ListInput(emptyList(), emptyList()), now, Locale.ENGLISH)))
    }

    @Test
    fun trailingTimeIsNowWhileRunningElseClockOrDate() {
        val today = now.toLocalDate()
        fun time(s: SessionSummary) = sessionRow(s, now, today, Locale.ENGLISH).time
        assertEquals(RowTime.Now, time(session("r", at(2026, 10, 1, 9, 0), running = true)))
        assertEquals(RowTime.At("21:40"), time(session("t", at(2026, 11, 3, 21, 40))))
        assertEquals(RowTime.At("23:05"), time(session("y", at(2026, 11, 2, 23, 5))))
        assertEquals(RowTime.At("1 Oct"), time(session("e", at(2026, 10, 1, 9, 0))))
        assertEquals(RowTime.At("31 Dec 2025"), time(session("l", at(2025, 12, 31, 10, 0))))
        assertNull(time(session("n", null)))
    }

    @Test
    fun blankTitleBecomesUntitledAndCountsPassThrough() {
        val row = sessionRow(session("x", null, title = "  ", messages = 214), now, now.toLocalDate(), Locale.ENGLISH)
        assertNull(row.title)
        assertEquals(214, row.messageCount)
    }

    @Test
    fun lastActiveWinsOverStartedAtWhenPresent() {
        val s = SessionSummary(sessionId = "a", startedAt = at(2026, 10, 1, 9, 0), lastActive = at(2026, 11, 3, 20, 0))
        assertEquals(RowTime.At("20:00"), sessionRow(s, now, now.toLocalDate(), Locale.ENGLISH).time)
    }
}
