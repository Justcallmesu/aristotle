package io.github.justcallmesu.aristotle.ui.sessions

import io.github.justcallmesu.aristotle.data.SessionSummary
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TR2 Home C: live cards, the day-grouped ledger, the bottom-bar menu, the notice cap and the profile mark. */
class HomeListTest {
    private val zone = ZoneId.of("Asia/Jakarta")
    private val now = ZonedDateTime.of(2026, 11, 3, 22, 31, 0, 0, zone) // a Tuesday

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Double =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toEpochSecond().toDouble()

    private fun session(id: String, started: Double?, running: Boolean = false) =
        SessionSummary(sessionId = id, id = id, title = id, startedAt = started, messageCount = 3, running = running)

    private fun shape(items: List<LedgerItem>): List<String> = items.map {
        when (it) {
            is LedgerItem.Header -> "#${it.section}" + (it.date?.let { d -> " $d" } ?: "")
            is LedgerItem.Row -> it.row.key
            is LedgerItem.Footer -> "footer:${it.end}"
            LedgerItem.NoMatch -> "no-match"
            else -> it.toString()
        }
    }

    private fun home(input: ListInput) = homeList(sessionListItems(input, now, Locale.ENGLISH))

    @Test
    fun rowsKnowTheirDayFromLastActivity() {
        val today = now.toLocalDate()
        fun day(s: SessionSummary) = sessionRow(s, now, today, Locale.ENGLISH).day
        assertEquals(RowDay(DayGroup.Today, "Tue 3 Nov"), day(session("t", at(2026, 11, 3, 0, 5))))
        assertEquals(RowDay(DayGroup.Yesterday, "Mon 2 Nov"), day(session("y", at(2026, 11, 2, 23, 59))))
        assertEquals(RowDay(DayGroup.Earlier, ""), day(session("e", at(2026, 11, 1, 9, 0))))
        assertNull(day(session("n", null)))
    }

    // Q75: live sessions become the cards; pinned and history fill the ledger, history by day with the date.
    @Test
    fun liveBecomesCardsAndHistoryGroupsByDay() {
        val live = listOf(session("idle", at(2026, 11, 3, 21, 0)), session("run", at(2026, 11, 1, 9, 0), running = true))
        val stored = listOf(
            session("t1", at(2026, 11, 3, 20, 0)),
            session("t2", at(2026, 11, 3, 8, 59)),
            session("y1", at(2026, 11, 2, 18, 27)),
            session("e1", at(2026, 10, 14, 9, 0)),
            session("pin", at(2026, 10, 1, 9, 0)),
        )
        val home = home(ListInput(live, stored, pins = setOf("pin"), end = HistoryEnd.More))
        assertEquals(listOf("run", "idle"), home.live.map { it.key })
        assertEquals(
            listOf("#Pinned", "pin", "#Today Tue 3 Nov", "t1", "t2", "#Yesterday Mon 2 Nov", "y1", "#Earlier", "e1", "footer:More"),
            shape(home.ledger),
        )
    }

    // A pinned live session stays in the ledger under Pinned, never as a card too.
    @Test
    fun pinnedLiveRowIsNotACard() {
        val home = home(ListInput(listOf(session("a", null, running = true), session("b", null)), emptyList(), pins = setOf("a")))
        assertEquals(listOf("b"), home.live.map { it.key })
        assertEquals(listOf("#Pinned", "a"), shape(home.ledger))
    }

    // Out-of-order activity never repeats a day header; rows without a time file under Earlier.
    @Test
    fun eachDayHeaderShowsOnceAndUntimedRowsAreEarlier() {
        val stored = listOf(
            session("t1", at(2026, 11, 3, 9, 0)),
            session("e1", at(2026, 9, 1, 9, 0)),
            session("t2", at(2026, 11, 3, 8, 0)),
            session("n1", null),
        )
        assertEquals(listOf("#Today Tue 3 Nov", "t1", "t2", "#Earlier", "e1", "n1"), shape(home(ListInput(emptyList(), stored)).ledger))
    }

    @Test
    fun noMatchAndFooterStayAtTheEnd() {
        val miss = home(ListInput(listOf(session("a", null)), listOf(session("b", null)), query = "zzz", end = HistoryEnd.Failed))
        assertTrue(miss.live.isEmpty())
        assertEquals(listOf("no-match", "footer:Failed"), shape(miss.ledger))
        val empty = home(ListInput(emptyList(), emptyList()))
        assertTrue(empty.live.isEmpty() && empty.ledger.isEmpty())
    }

    // Home C: Scheduled moved to its own bottom-bar button; the ⋮ menu keeps Terminal (when shown) and Settings.
    @Test
    fun bottomBarMenuHoldsTerminalAndSettingsOnly() {
        assertEquals(listOf(ListMenuEntry.Settings), homeMenuEntries(terminalAvailable = false))
        assertEquals(listOf(ListMenuEntry.Terminal, ListMenuEntry.Settings), homeMenuEntries(terminalAvailable = true))
    }

    // design-notes §5.1: never more than two notices.
    @Test
    fun whatsNewWaitsWhileTwoOtherNoticesShow() {
        assertTrue(showWhatsNewNotice(offline = false, incompatible = false))
        assertTrue(showWhatsNewNotice(offline = true, incompatible = false))
        assertTrue(showWhatsNewNotice(offline = false, incompatible = true))
        assertFalse(showWhatsNewNotice(offline = true, incompatible = true))
    }

    @Test
    fun profileInitialIsTheFirstCharacterUpperCased() {
        assertEquals("P", profileInitial("pm"))
        assertEquals("B", profileInitial("  bkm-reports-bot"))
        assertEquals("?", profileInitial("   "))
        assertEquals("\uD83D\uDE00", profileInitial("\uD83D\uDE00 smile")) // a surrogate pair stays whole
    }
}
