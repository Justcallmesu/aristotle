package io.github.justcallmesu.aristotle.ui

import io.github.justcallmesu.aristotle.data.stocks.StockOpen
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
        assertEquals("StockRoute, Running, the two Screener pages, the brief, the alerts list and the track record use it too", 9, Regex("onBack = goBack\\b").findAll(text).count())
        assertFalse("no remembered origins left to loop", text.contains("stockFrom") || text.contains("newsFrom"))
    }

    /** TSC3: Home → Screener → a candidate → Back is the Screener, then Home; the gear's settings go back to the page. */
    @Test
    fun `the Screener, its settings and a candidate unwind one each`() {
        val screener = StocksBackStack(PairedPage.Sessions).openScreener()
        assertEquals(StocksBackStack(PairedPage.StocksScreener), screener)
        val stock = screener.openStock("BBCA")
        assertEquals(StocksBackStack(PairedPage.StockDetail, "BBCA", listOf(StockPlace(PairedPage.StocksScreener))), stock)
        assertEquals(screener, stock.back())
        val settings = screener.openScreenerSettings()
        assertEquals(StocksBackStack(PairedPage.StocksScreenerSettings, below = listOf(StockPlace(PairedPage.StocksScreener))), settings)
        assertEquals(screener, settings.back())
        assertEquals(PairedPage.Sessions, screener.back().page)
    }

    /** TSC3: Settings › Stocks › Screener goes back to Settings › Stocks, whatever stale stack was kept. */
    @Test
    fun `screener settings opened from Settings › Stocks go back there`() {
        val stale = listOf(StockPlace(PairedPage.StocksNews), StockPlace(PairedPage.StockDetail, "X"))
        val settings = StocksBackStack(PairedPage.SettingsStocks, below = stale).openScreenerSettings()
        assertEquals(StocksBackStack(PairedPage.StocksScreenerSettings, below = listOf(StockPlace(PairedPage.SettingsStocks))), settings)
        val back = settings.back()
        assertEquals(StocksBackStack(PairedPage.SettingsStocks), back)
        // Settings › Stocks keeps its own back (the Settings list), it is not a stocks page.
        assertFalse(PairedPage.SettingsStocks in StocksBackStack.PAGES)
        assertEquals(PairedPage.Settings, PairedPage.SettingsStocks.back)
        // The root survives the saved state; another settings page does not.
        assertEquals(StockPlace(PairedPage.SettingsStocks), StockPlace.decode(StockPlace(PairedPage.SettingsStocks).encode()))
        assertNull(StockPlace.decode("SettingsAbout"))
    }

    /**
     * TM3a2 review MINOR-B: every way onto a stock's page goes through `show(... openStock(code))`; a plain
     * `page = PairedPage.StockDetail` would keep a stale back stack. A guard on the source (the wiring is in a composable).
     */
    @Test
    fun `a stock page is only ever opened through show and the stack`() {
        val path = "src/main/java/io/github/justcallmesu/aristotle/ui/AristotleApp.kt"
        val text = listOf(File(path), File("app/$path")).first { it.exists() }.readText()
        assertFalse("no direct jump to a stock page", Regex("page\\s*=\\s*PairedPage\\.StockDetail").containsMatchIn(text))
        assertTrue(text.contains("val openStock: (String) -> Unit = { code -> show(stocksStack().openStock(code)) }"))
        // The screens that open stocks get that one lambda: News, Home's stocks nav, the Screener's candidates and the
        // brief's focus rows (TBA2); the alerts list and a notification's tap go through the stack too (openStockTarget).
        assertTrue(text.contains("onOpenStock = openStock"))
        assertEquals(3, Regex("onOpenStock = openStock\\b").findAll(text).count())
        assertTrue(text.contains("show(from.open(request))"))
        assertTrue(text.contains("show(stocksStack().open(target))"))
        assertFalse(Regex("page\\s*=\\s*PairedPage\\.Stocks(Brief|Alerts)").containsMatchIn(text))
        assertTrue(text.contains("openStock = openStock,"))
        // The screener pages are entered through the stack too.
        assertFalse(Regex("page\\s*=\\s*PairedPage\\.StocksScreener").containsMatchIn(text))
    }

    /** TBA2: Home → brief → a focus stock unwinds one each; a notification's tap from elsewhere starts a stack over Home. */
    @Test
    fun `the brief and the alerts list are stocks pages`() {
        val brief = StocksBackStack(PairedPage.Sessions).openBrief()
        assertEquals(StocksBackStack(PairedPage.StocksBrief), brief)
        val stock = brief.openStock("BBCA")
        assertEquals(StocksBackStack(PairedPage.StockDetail, "BBCA", listOf(StockPlace(PairedPage.StocksBrief))), stock)
        assertEquals(brief, stock.back())
        assertEquals(PairedPage.Sessions, brief.back().page)
        val alerts = StocksBackStack(PairedPage.StockDetail, "TLKM").openAlerts()
        assertEquals(StocksBackStack(PairedPage.StocksAlerts, below = listOf(StockPlace(PairedPage.StockDetail, "TLKM"))), alerts)
        assertEquals(StocksBackStack(PairedPage.StockDetail, "TLKM"), alerts.back())
        // From a chat (not a stocks page) a tap starts fresh: Back is Home.
        assertEquals(StocksBackStack(PairedPage.StockDetail, "BBCA"), StocksBackStack(PairedPage.Chat).openStock("BBCA"))
        val saved = listOf(StockPlace(PairedPage.StocksBrief), StockPlace(PairedPage.StocksAlerts))
        assertEquals(saved, saved.map(StockPlace::encode).mapNotNull(StockPlace::decode))
    }

    /** TBA2b NIT: one mapping from a target to the stack, and a repeat tap pushes nothing. */
    @Test
    fun `open maps a target to its page and a repeat tap changes nothing`() {
        val home = StocksBackStack(PairedPage.Sessions)
        assertEquals(home.openStock("BBCA"), home.open(StockOpen.Stock("BBCA")))
        assertEquals(home.openBrief(), home.open(StockOpen.Brief))
        assertEquals(home.openAlerts(), home.open(StockOpen.Alerts))
        val brief = home.open(StockOpen.Brief)
        assertEquals(brief, brief.open(StockOpen.Brief))
        val alerts = brief.open(StockOpen.Alerts)
        assertEquals(alerts, alerts.open(StockOpen.Alerts))
        assertEquals(brief, alerts.back())
        val stock = home.open(StockOpen.Stock("BBCA"))
        assertEquals(StocksBackStack(PairedPage.StockDetail, "TLKM"), stock.open(StockOpen.Stock("TLKM")))
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

    @Test
    fun `the track record page opens from Home or a stock and goes back to where it came from (TR2)`() {
        val fromHome = StocksBackStack(PairedPage.Sessions).openTrackRecord()
        assertEquals(StocksBackStack(PairedPage.StocksTrackRecord), fromHome)
        assertEquals(PairedPage.Sessions, fromHome.back().page)
        val fromStock = StocksBackStack(PairedPage.Sessions).openStock("BBCA").openTrackRecord()
        assertEquals(StocksBackStack(PairedPage.StocksTrackRecord, null, listOf(StockPlace(PairedPage.StockDetail, "BBCA"))), fromStock)
        assertEquals(StocksBackStack(PairedPage.StockDetail, "BBCA"), fromStock.back())
        assertEquals(StockPlace(PairedPage.StocksTrackRecord), StockPlace.decode(StockPlace(PairedPage.StocksTrackRecord).encode()))
    }
}
