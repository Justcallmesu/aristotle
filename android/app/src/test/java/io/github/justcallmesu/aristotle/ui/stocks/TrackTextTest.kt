package io.github.justcallmesu.aristotle.ui.stocks

import io.github.justcallmesu.aristotle.R
import io.github.justcallmesu.aristotle.data.stocks.BarSegment
import io.github.justcallmesu.aristotle.data.stocks.BarTone
import io.github.justcallmesu.aristotle.data.stocks.CountKind
import io.github.justcallmesu.aristotle.data.stocks.CountPart
import io.github.justcallmesu.aristotle.data.stocks.RecordLine
import io.github.justcallmesu.aristotle.data.stocks.TrackDisplay
import io.github.justcallmesu.aristotle.data.stocks.TrackGroup
import io.github.justcallmesu.aristotle.data.stocks.TrackRecords
import io.github.justcallmesu.aristotle.data.stocks.Verdict
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TR2: which words say each verdict type's record (Q121 note), what a card's counts and bar hold, and the strings' two languages. */
class TrackTextTest {
    private fun group(verdict: String, n: Int = 0, win: Int = 0, loss: Int = 0, closed: Int = 0, notTriggered: Int = 0, right: Int = 0, wrong: Int = 0, noSession: Int = 0, unscorable: Int = 0, info3dN: Int = 0, info3dSum: Double = 0.0) =
        TrackGroup(verdict = verdict, n = n, win = win, loss = loss, closed = closed, notTriggered = notTriggered, right = right, wrong = wrong, noSession = noSession, unscorable = unscorable, info3dN = info3dN, info3dSum = info3dSum)

    private fun record(g: TrackGroup) = TrackRecords.byVerdict(listOf(g)).single()

    @Test
    fun `the card line says hit target for Buy today and Add, right for Avoid and Sell, held above the stop for Hold`() {
        assertEquals(Words(R.string.stocks_record_hits_target, listOf("8", "24")), TrackText.hits(Verdict.BuyToday, 8, 24))
        assertEquals(Words(R.string.stocks_record_hits_target, listOf("4", "12")), TrackText.hits(Verdict.Add, 4, 12))
        assertEquals(Words(R.string.stocks_record_hits_right, listOf("16", "26")), TrackText.hits(Verdict.Avoid, 16, 26))
        assertEquals(Words(R.string.stocks_record_hits_right, listOf("5", "10")), TrackText.hits(Verdict.Sell, 5, 10))
        assertEquals(Words(R.string.stocks_record_hits_held, listOf("18", "22")), TrackText.hits(Verdict.Hold, 18, 22))
    }

    @Test
    fun `the line per record state`() {
        assertEquals(LineWords.Sentence(Verdict.BuyToday, Words(R.string.stocks_record_hits_target, listOf("8", "24"))), TrackText.line(RecordLine.Hits(Verdict.BuyToday, 8, 24)))
        assertEquals(LineWords.Sentence(Verdict.Wait, Words(R.string.stocks_record_wait_avg, listOf("+0,20\u00A0%"))), TrackText.line(RecordLine.WaitAverage(0.2)))
        assertEquals(LineWords.Sentence(Verdict.Add, null), TrackText.line(RecordLine.Few(Verdict.Add, 4)))
        assertEquals(LineWords.None, TrackText.line(RecordLine.None))
        assertEquals(Words(R.string.stocks_record_few_n, listOf("8", "10")), TrackText.few(8, TrackRecords.MIN_DECIDED))
    }

    @Test
    fun `buy today counts read win, loss, not triggered, closed flat, and drop empty ones`() {
        val r = record(group("buy_today", n = 33, win = 8, loss = 13, closed = 3, notTriggered = 9))
        assertEquals(
            listOf(CountPart(CountKind.Win, 8), CountPart(CountKind.Loss, 13), CountPart(CountKind.NotTriggered, 9), CountPart(CountKind.ClosedFlat, 3)),
            TrackDisplay.counts(r),
        )
        assertEquals(
            listOf(BarSegment(8, BarTone.Gain), BarSegment(13, BarTone.Loss), BarSegment(3, BarTone.Flat), BarSegment(9, BarTone.Muted)),
            TrackDisplay.bar(r),
        )
        val sparse = record(group("add", n = 5, win = 2, loss = 3, noSession = 0))
        assertEquals(listOf(CountPart(CountKind.Win, 2), CountPart(CountKind.Loss, 3)), TrackDisplay.counts(sparse))
    }

    @Test
    fun `avoid and sell counts read right and wrong, never win and loss`() {
        val r = record(group("sell", n = 12, right = 7, wrong = 5, noSession = 1))
        assertEquals(listOf(CountPart(CountKind.Right, 7), CountPart(CountKind.Wrong, 5), CountPart(CountKind.NotScored, 1)), TrackDisplay.counts(r))
        assertEquals(listOf(BarSegment(7, BarTone.Gain), BarSegment(5, BarTone.Loss)), TrackDisplay.bar(r))
        assertTrue(TrackDisplay.inkAverage(Verdict.Sell))
        assertTrue(TrackDisplay.inkAverage(Verdict.Avoid))
        assertFalse(TrackDisplay.inkAverage(Verdict.BuyToday))
    }

    @Test
    fun `hold counts and a wait has neither counts nor a bar`() {
        val hold = record(group("hold", n = 22, win = 1, loss = 3, right = 18))
        assertEquals(listOf(CountPart(CountKind.TargetHit, 1), CountPart(CountKind.Held, 18), CountPart(CountKind.StopHit, 3)), TrackDisplay.counts(hold))
        assertEquals(listOf(BarSegment(19, BarTone.Gain), BarSegment(3, BarTone.Loss)), TrackDisplay.bar(hold))
        val wait = record(group("wait", n = 20))
        assertTrue(TrackDisplay.counts(wait).isEmpty())
        assertTrue(TrackDisplay.bar(wait).isEmpty())
    }

    @Test
    fun `the 3-day line needs 10 decided calls and a value`() {
        assertEquals(0.6, record(group("avoid", n = 10, right = 6, wrong = 4, info3dN = 5, info3dSum = 3.0)).info3dAvg!!, 1e-9)
        assertNull(record(group("avoid", n = 10, right = 6, wrong = 4)).info3dAvg)
        assertNull(record(group("avoid", n = 9, right = 6, wrong = 3, info3dN = 5, info3dSum = 3.0)).info3dAvg)
    }

    @Test
    fun `the card's rate words are the card line's rule, one copy`() {
        assertEquals(Words(R.string.stocks_record_hits_target, listOf("8", "24")), TrackText.rate(record(group("buy_today", n = 30, win = 8, loss = 12, closed = 4, notTriggered = 6))))
        assertEquals(Words(R.string.stocks_record_hits_right, listOf("9", "12")), TrackText.rate(record(group("avoid", n = 12, right = 9, wrong = 3))))
        assertEquals(Words(R.string.stocks_record_few_n, listOf("4", "10")), TrackText.rate(record(group("sell", n = 4, right = 3, wrong = 1))))
        val wait = TrackGroup(verdict = "wait", n = 12, wouldHave = 12, resultN = 12, resultSum = 2.4)
        assertEquals(TrackText.waitAverage(0.2), TrackText.rate(record(wait)))
        assertEquals(Words(R.string.stocks_record_few_n, listOf("6", "10")), TrackText.rate(record(TrackGroup(verdict = "wait", n = 6, wouldHave = 6))))
        for (g in listOf(group("hold", n = 22, win = 1, loss = 3, right = 18), group("add", n = 5, win = 2, loss = 3), wait)) {
            val r = record(g)
            val body = (TrackText.line(TrackRecords.line(r)) as LineWords.Sentence).body
            assertEquals(body ?: TrackText.few(r.sample, TrackRecords.MIN_DECIDED), TrackText.rate(r))
        }
    }

    @Test
    fun `bold ranges follow each number in order, even when the same digits repeat`() {
        val text = "AI record on Add: 4 of 12 hit target"
        assertEquals(listOf(18..18, 23..24), TrackDisplay.argRanges(text, listOf("4", "12")))
        assertEquals(listOf(0..1, 6..7), TrackDisplay.argRanges("12 of 12", listOf("12", "12")))
        assertTrue(TrackDisplay.argRanges("none", listOf("7")).isEmpty())
    }

    // ---- both languages --------------------------------------------------------------------------------------

    private fun names(path: String, tag: String, prefixes: List<String>): Set<String> =
        Regex("<$tag name=\"([a-z0-9_]+)\"").findAll(File(path).readText()).map { it.groupValues[1] }.filter { n -> prefixes.any { n.startsWith(it) } }.toSet()

    @Test
    fun `every track record string exists in English and Indonesian`() {
        val prefixes = listOf("stocks_record_", "stocks_past_", "stocks_chip_")
        for (tag in listOf("string", "plurals")) {
            val en = names("src/main/res/values/strings.xml", tag, prefixes)
            val id = names("src/main/res/values-in/strings.xml", tag, prefixes)
            assertTrue("no $tag added", en.isNotEmpty() || tag == "plurals")
            assertEquals("$tag: English only ${en - id}, Indonesian only ${id - en}", en, id)
        }
    }

    @Test
    fun `a result chip takes at most 52 percent of the row and the rest is left to the verdict and its date`() {
        // 393 dp wide, 16 dp gutters outside and 16 dp padding inside the grouped row.
        val row = 393f - 2 * 16f - 2 * 16f
        val chip = TrackDisplay.chipMaxWidth(row)
        assertEquals(0.52f * row, chip, 1e-3f)
        assertTrue("the verdict column keeps ${row - chip} dp", row - chip >= 150f)
    }
}
