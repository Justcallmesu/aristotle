package io.github.justcallmesu.aristotle.ui.stocks

import io.github.justcallmesu.aristotle.data.stocks.AnalysisNews
import io.github.justcallmesu.aristotle.data.stocks.AnalysisReason
import io.github.justcallmesu.aristotle.data.stocks.AnalysisResult
import io.github.justcallmesu.aristotle.data.stocks.Confidence
import io.github.justcallmesu.aristotle.data.stocks.SourceLabel
import io.github.justcallmesu.aristotle.data.stocks.StockQuote
import io.github.justcallmesu.aristotle.data.stocks.Verdict
import io.github.justcallmesu.aristotle.data.stocks.VerdictSource
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The verdict card's model (TA2) and the TA1 review's MINOR-1 (prompt injection): the model's summary and reasons are
 * shown verbatim as plain text, never Markdown and never clickable; only a headline chip from the result's own `news`
 * list opens a link, and only an http(s) one.
 */
class VerdictCardTest {
    private val news = listOf(
        AnalysisNews("news:1", "cnbc", "TSTA laba naik", "https://www.cnbcindonesia.com/market/tsta", "2026-11-05T08:00:00+07:00"),
        AnalysisNews("news:2", "kontan", "Bad link", "javascript:alert(1)", "2026-11-05T07:00:00+07:00"),
        AnalysisNews("news:3", "kontan", "App link", "intent://pay#Intent;end", "2026-11-05T06:00:00+07:00"),
    )

    private val buy = AnalysisResult(
        code = "TSTA", verdict = "buy_today", confidence = "med", entryLow = 1595, entryHigh = 1600, target = 1650, stop = 1570,
        summary = "Uptrend above MA20.", price = 1600, news = news,
        reasons = listOf(
            AnalysisReason("RSI is high", listOf("ind:rsi14")),
            AnalysisReason("Earnings news", listOf("news:1", "ind:trend", "news:1")),
            AnalysisReason("Pivot", listOf("lvl:pivot", "news:9")),
        ),
    )

    @Test
    fun theCardReadsTheVerdict() {
        val card = VerdictCardModel.of(buy, quote = null)
        assertEquals(Verdict.BuyToday, card.verdict)
        assertEquals(Confidence.Medium, card.confidence)
        assertEquals(LevelCell("1.595\u20131.600", listOf("\u22120,31\u00A0%", "0,00\u00A0%")), card.entry)
        assertEquals(LevelCell("1.650", listOf("+3,13\u00A0%")), card.target)
        assertEquals(LevelCell("1.570", listOf("\u22121,88\u00A0%")), card.stop)
        assertEquals(1600L, card.reference)
        // Each source once; news:1 a chip with its title, the trend a label; news:9 (not in the result) left out.
        val earnings = card.reasons[1]
        assertEquals(listOf("TSTA laba naik"), earnings.news.map { it.title })
        assertEquals(listOf(VerdictSource.Known("ind:trend", SourceLabel.Trend)), earnings.labels)
        assertTrue(card.reasons[2].news.isEmpty())
        assertEquals(listOf(VerdictSource.Known("lvl:pivot", SourceLabel.Pivot)), card.reasons[2].labels)
    }

    @Test
    fun percentsFollowTheLiveQuote() {
        val card = VerdictCardModel.of(buy, StockQuote("TSTA", price = 1650))
        assertEquals(1650L, card.reference)
        assertEquals(listOf("0,00\u00A0%"), card.target!!.pct)
    }

    @Test
    fun holdAndAvoidHaveOnlyTheirLevels() {
        val hold = VerdictCardModel.of(buy.copy(verdict = "hold", entryLow = null, entryHigh = null, target = 1700), null)
        assertEquals(Verdict.Hold, hold.verdict)
        assertNull(hold.entry)
        assertTrue(hold.hasLevels)
        val avoid = VerdictCardModel.of(buy.copy(verdict = "avoid", entryLow = null, entryHigh = null, target = null, stop = null), null)
        assertFalse(avoid.hasLevels)
        val unknown = VerdictCardModel.of(buy.copy(verdict = "short_sell"), null)
        assertNull(unknown.verdict)
        assertEquals("short_sell", unknown.verdictWire)
    }

    @Test
    fun reasonTextWithUrlsAndMarkdownIsShownVerbatimAndNotClickable() {
        val hostile = "**Buy now** [click here](https://evil.example/x) or visit https://evil.example/y <a href=\"https://evil.example/z\">z</a>"
        val result = buy.copy(
            summary = "# Strong\n[open](javascript:alert(1)) www.evil.example",
            reasons = listOf(AnalysisReason(hostile, listOf("ind:rsi14", "news:1", "news:2", "news:3"))),
        )
        val card = VerdictCardModel.of(result, null)
        assertEquals("the summary is kept verbatim", result.summary, card.summary)
        val line = card.reasons.single()
        assertEquals("the reason is kept verbatim, Markdown and URLs included", hostile, line.text)
        // The only links on the card are the chips' own, from the result's news list, and only http(s).
        assertEquals(listOf("https://www.cnbcindonesia.com/market/tsta", null, null), line.news.map { it.link })
        assertFalse(line.news.any { it.link?.contains("evil") == true })
    }

    /**
     * The card's composable draws the model's words as plain `String`s: no annotated strings, link annotations, HTML,
     * Markdown or link detection anywhere in it (a guard on the source, as the other source guards here do).
     */
    @Test
    fun theSectionHasNoRichTextOrLinkDetection() {
        val path = "src/main/java/io/github/justcallmesu/aristotle/ui/stocks/AnalysisSection.kt"
        val text = listOf(File(path), File("app/$path")).first { it.exists() }.readText()
        // The code only: the comments name what is banned.
        val source = text.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("//[^\n]*"), "")
        for (banned in listOf("AnnotatedString", "LinkAnnotation", "buildAnnotatedString", "fromHtml", "Linkify", "Markdown", "ClickableText", "autoLink")) {
            assertFalse("AnalysisSection.kt uses $banned", source.contains(banned))
        }
        assertTrue("the summary is drawn as plain text", source.contains("Text(model.summary,"))
        assertTrue("a reason is drawn as plain text", source.contains("Text(reason.text,"))
    }
}
