package io.github.justcallmesu.aristotle.data

import java.io.File
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatQuoteTest {
    private val quotes = QuoteParser.parse(File("../../docs/quotes.json").readText())

    private fun source() = QuoteSource({ File("../../docs/quotes.json").inputStream() }, AppSettings(MemoryStore()))

    @Test
    fun anInlineQuoteIsNeverTheLaunchQuoteNorThePreviousInlineOne() {
        repeat(200) { seed ->
            val s = source()
            val launch = s.next(Random(seed))!!
            var previous = -1
            repeat(30) {
                val q = s.nextInline(Random(seed * 31 + it))!!
                assertNotEquals(launch.id, q.id)
                assertNotEquals(previous, q.id)
                previous = q.id
            }
        }
    }

    @Test
    fun inlinePicksDoNotChangeTheRememberedLaunchQuote() {
        val settings = AppSettings(MemoryStore())
        val s = QuoteSource({ File("../../docs/quotes.json").inputStream() }, settings)
        val launch = s.next()!!
        repeat(10) { s.nextInline() }
        assertEquals(launch.id, settings.lastQuoteId)
    }

    @Test
    fun pickAvoidingFallsBackToAnyWhenEverythingIsAvoided() {
        val one = quotes.take(1)
        assertEquals(one[0], QuotePicker.pickAvoiding(one, setOf(one[0].id)))
        assertNull(QuotePicker.pickAvoiding(emptyList(), setOf(1)))
        assertNotEquals(quotes[0].id, QuotePicker.pickAvoiding(quotes, setOf(quotes[0].id))!!.id)
    }

    @Test
    fun withoutALaunchQuoteTwoInlineQuotesStillDiffer() {
        val s = source()
        var previous = s.nextInline()!!.id
        repeat(50) {
            val q = s.nextInline()!!
            assertNotEquals(previous, q.id)
            previous = q.id
        }
    }

    @Test
    fun theQuoteWaitsThreeHundredMillisThenStaysTwelveHundred() {
        assertEquals(300L, ChatQuoteTiming.delayBeforeShow(0))
        assertEquals(100L, ChatQuoteTiming.delayBeforeShow(200))
        assertEquals(0L, ChatQuoteTiming.delayBeforeShow(900))
        assertEquals(1200L, ChatQuoteTiming.holdRemaining(0))
        assertEquals(700L, ChatQuoteTiming.holdRemaining(500))
        assertEquals(0L, ChatQuoteTiming.holdRemaining(5000))
    }
}
