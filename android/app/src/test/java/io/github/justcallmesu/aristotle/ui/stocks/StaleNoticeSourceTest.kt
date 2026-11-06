package io.github.justcallmesu.aristotle.ui.stocks

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TSC3b review MINOR-6: the screener's stale notice ticks a countdown every second; only the notice's words are a TalkBack
 * live region, so the countdown is not re-read each tick.
 */
class StaleNoticeSourceTest {
    private val body: String by lazy {
        val path = "src/main/java/io/github/justcallmesu/aristotle/ui/stocks/ScreenerScreen.kt"
        val source = listOf(File(path), File("app/$path")).first { it.exists() }.readText()
        val start = source.indexOf("private fun StaleNotice(")
        source.substring(start, source.indexOf("\n}", start))
    }

    @Test
    fun `one live region, on the words, none around the countdown`() {
        assertEquals(1, Regex("liveRegion").findAll(body).count())
        val countdown = body.substring(body.indexOf("left?.let"))
        assertFalse(countdown.substring(0, countdown.indexOf("TextButton")).contains("liveRegion"))
        val surface = body.substring(body.indexOf("Surface("), body.indexOf(") {", body.indexOf("Surface(")))
        assertFalse("not on the whole notice", surface.contains("liveRegion"))
        assertTrue(body.contains("semantics { liveRegion = LiveRegionMode.Polite }"))
    }
}
