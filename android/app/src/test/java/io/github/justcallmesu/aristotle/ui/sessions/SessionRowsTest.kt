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

    @Test
    fun rowsAreGroupedByDayWithRunningSessionsInToday() {
        val items = sessionListItems(
            listOf(
                session("today", at(2026, 11, 3, 21, 40)),
                session("old-running", at(2026, 10, 1, 9, 0), running = true),
                session("yesterday", at(2026, 11, 2, 23, 5)),
                session("earlier", at(2026, 10, 1, 9, 0)),
                session("last-year", at(2025, 12, 31, 10, 0)),
                session("no-time", null),
            ),
            now,
            Locale.ENGLISH,
        )
        val shape = items.map {
            when (it) {
                is SessionListItem.Header -> "#${it.section}"
                is SessionListItem.Row -> it.row.key
            }
        }
        assertEquals(
            listOf("#Today", "old-running", "today", "#Yesterday", "yesterday", "#Earlier", "earlier", "last-year", "no-time"),
            shape,
        )
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
        assertEquals(DaySection.Today, sectionOf(s, now))
    }
}
