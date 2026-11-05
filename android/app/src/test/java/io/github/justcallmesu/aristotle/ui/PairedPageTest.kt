package io.github.justcallmesu.aristotle.ui

import io.github.justcallmesu.aristotle.ui.settings.SettingsPage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TS3 (Q72): back from a Settings sub-page, and the page a category opens; TM3a2: the stocks pages' back stack. */
class PairedPageTest {
    @Test
    fun `every category has its own page and goes back to the list`() {
        SettingsPage.entries.forEach { category ->
            val page = PairedPage.of(category)
            assertEquals(category, page.settingsPage)
            assertEquals(PairedPage.Settings, page.back)
        }
        assertEquals(PairedPage.Sessions, PairedPage.Settings.back)
    }

    @Test
    fun `battery guide, pair again and what's new go back to their sub-page`() {
        assertEquals(PairedPage.SettingsNotifications, PairedPage.SettingsGuide.back)
        assertEquals(PairedPage.SettingsConnection, PairedPage.Repair.back)
        assertEquals(PairedPage.SettingsAbout, PairedPage.SettingsWhatsNew.back)
        assertEquals(PairedPage.SettingsConnection, PairedPage.SettingsHistory.back) // Q73
    }

    @Test
    fun `other pages still go back to the session list`() {
        assertEquals(PairedPage.Sessions, PairedPage.Chat.back)
        assertEquals(PairedPage.Sessions, PairedPage.WhatsNew.back)
        assertEquals(PairedPage.Sessions, PairedPage.Terminal.back)
    }

    @Test
    fun `Home, News, a stock, All news, then Back three times is Home (TM3a2, no loop)`() {
        val home = StocksBackStack(PairedPage.Sessions)
        val news = home.openNews()
        val stock = news.openStock("BBCA")
        val allNews = stock.openNews()
        assertEquals(PairedPage.StocksNews, allNews.page)
        val first = allNews.back()
        assertEquals(StocksBackStack(PairedPage.StockDetail, "BBCA", listOf(StockPlace(PairedPage.StocksNews))), first)
        val second = first.back()
        assertEquals(StocksBackStack(PairedPage.StocksNews), second)
        assertEquals(StocksBackStack(PairedPage.Sessions), second.back())
    }

    @Test
    fun `a stock, All news, Back is the stock, then Home`() {
        val stock = StocksBackStack(PairedPage.Sessions).openStock("TLKM")
        val back = stock.openNews().back()
        assertEquals(StocksBackStack(PairedPage.StockDetail, "TLKM"), back)
        assertEquals(PairedPage.Sessions, back.back().page)
    }

    @Test
    fun `every step back unwinds one screen and a long walk still ends on Home`() {
        // Home → News → A → All news → B → All news → C: Back goes C, News, B, News, A, News, Home, one each.
        var nav = StocksBackStack(PairedPage.Sessions).openNews().openStock("A").openNews().openStock("B").openNews().openStock("C")
        val seen = mutableListOf<String>()
        while (nav.page != PairedPage.Sessions) {
            seen += nav.code ?: nav.page.name
            nav = nav.back()
        }
        assertEquals(listOf("C", "StocksNews", "B", "StocksNews", "A", "StocksNews"), seen)
        // A walk deeper than the kept stack is cut at its oldest steps: still a bounded number of Backs to Home.
        var deep = StocksBackStack(PairedPage.Sessions).openNews()
        repeat(40) { deep = deep.openStock("S$it").openNews() }
        var backs = 0
        while (deep.page != PairedPage.Sessions) {
            deep = deep.back()
            backs++
        }
        assertEquals(StocksBackStack.MAX_DEPTH + 1, backs)
    }

    @Test
    fun `a stocks page opened from Home starts a fresh stack`() {
        val stale = StocksBackStack(PairedPage.Sessions, below = listOf(StockPlace(PairedPage.StocksNews), StockPlace(PairedPage.StockDetail, "X")))
        assertEquals(StocksBackStack(PairedPage.StockDetail, "BBRI"), stale.openStock("BBRI"))
        assertEquals(StocksBackStack(PairedPage.StocksHoldings), stale.openHoldings())
        assertEquals(PairedPage.Sessions, StocksBackStack(PairedPage.Sessions).openHoldings().back().page)
        // A stock opened on a stock's page replaces it: its back is where the first one went.
        val replaced = StocksBackStack(PairedPage.Sessions).openNews().openStock("A").openStock("B")
        assertEquals(StocksBackStack(PairedPage.StockDetail, "B", listOf(StockPlace(PairedPage.StocksNews))), replaced)
    }

    @Test
    fun `the stack survives the saved state`() {
        val places = listOf(StockPlace(PairedPage.StocksNews), StockPlace(PairedPage.StockDetail, "BBCA"), StockPlace(PairedPage.StocksHoldings))
        assertEquals(places, places.map(StockPlace::encode).mapNotNull(StockPlace::decode))
        assertNull(StockPlace.decode("Chat"))
        assertNull(StockPlace.decode("StockDetail"))
        assertNull(StockPlace.decode("Gone:X"))
    }

    /**
     * System back and the top-bar arrows take the same step: one `goBack` in AristotleApp, used by the BackHandler and
     * by the stocks pages' and Running's `onBack` (a guard on the source: the wiring is inside a composable).
     */
    @Test
    fun `system back and the top-bar arrow share one step`() {
        val path = "src/main/java/io/github/justcallmesu/aristotle/ui/AristotleApp.kt"
        val text = listOf(File(path), File("app/$path")).first { it.exists() }.readText()
        assertTrue(text.contains("BackHandler(enabled = page != PairedPage.Sessions) { goBack() }"))
        listOf("HoldingsScreen(", "NewsScreen(").forEach { screen ->
            val line = text.lines().single { it.contains("-> $screen") }
            assertTrue(line, line.contains("onBack = goBack"))
        }
        assertEquals("StockRoute and Running use it too", 4, Regex("onBack = goBack\\b").findAll(text).count())
        assertFalse("no remembered origins left to loop", text.contains("stockFrom") || text.contains("newsFrom"))
    }

    @Test
    fun `other pages still use their own back`() {
        assertEquals(PairedPage.Sessions, PairedPage.StocksNews.backFrom(PairedPage.Sessions))
        assertEquals(PairedPage.Sessions, PairedPage.StocksHoldings.backFrom(PairedPage.Chat))
    }

    @Test
    fun `Running opened from a chat goes back to that chat, otherwise to Home`() {
        assertEquals(PairedPage.Chat, PairedPage.Running.backFrom(PairedPage.Chat))
        assertEquals(PairedPage.Sessions, PairedPage.Running.backFrom(PairedPage.Sessions))
        assertEquals(PairedPage.Sessions, PairedPage.Chat.backFrom(PairedPage.Chat)) // only Running is redirected
        assertEquals(PairedPage.Settings, PairedPage.SettingsAbout.backFrom(PairedPage.Chat))
    }
}
