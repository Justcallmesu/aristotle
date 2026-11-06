package io.github.justcallmesu.aristotle.ui

import io.github.justcallmesu.aristotle.data.stocks.StockOpen

/**
 * A page on the stocks back stack: Holdings, News, the Screener, its settings, a stock's page with its [code], or
 * Settings › Stocks (a root the Screener settings can be opened from, TSC3).
 */
internal data class StockPlace(val page: PairedPage, val code: String? = null) {
    /** One string for the saved state: `StockDetail:BBCA`, `StocksNews`. */
    fun encode(): String = if (code == null) page.name else "${page.name}:$code"

    companion object {
        /** [encode] read back; null for anything that is not a stocks page (a renamed page in an old saved state). */
        fun decode(text: String): StockPlace? {
            val name = text.substringBefore(':')
            val page = PairedPage.entries.firstOrNull { it.name == name }?.takeIf { it in StocksBackStack.PLACES } ?: return null
            val code = text.substringAfter(':', "").ifEmpty { null }
            if (page == PairedPage.StockDetail && code == null) return null
            return StockPlace(page, code)
        }
    }
}

/**
 * TM3a2: the stocks pages (Holdings, News, a stock's page) as a real back stack, Home under all of them. [page] and
 * [code] are shown; [below] are the stocks pages under it, oldest first. A step forward from a stocks page pushes that
 * page, a step from anywhere else starts a new stack; [back] pops one. So Back unwinds each screen once and always ends
 * on Home. (TM3a kept two remembered origins, the stock's and the News page's, that could point at each other:
 * Home → News → stock → All news → Back looped stock ↔ News for ever.) System back and the top-bar arrow both use [back].
 * TSC3: the Screener and its settings are stocks pages too; Settings › Stocks is a [ROOTS] page: a step from it starts a
 * stack that goes back to it (Settings › Stocks › Screener → Back is Settings › Stocks), whatever was under it before.
 * Every way onto a stock's page (a row, a chip, a candidate, a later deep link) must go through [openStock] (TM3a2
 * review MINOR-B): a plain `page = StockDetail` would keep a stale stack.
 */
internal data class StocksBackStack(val page: PairedPage, val code: String? = null, val below: List<StockPlace> = emptyList()) {
    /** A stock's page; a stock opened on a stock's page replaces it (its back stays where the first one went). */
    fun openStock(code: String): StocksBackStack = if (page == PairedPage.StockDetail) copy(code = code) else go(PairedPage.StockDetail, code)

    /** The News page (from Home's Stocks view, or a stock's "All news"). */
    fun openNews(): StocksBackStack = go(PairedPage.StocksNews, null)

    /** The Holdings page. */
    fun openHoldings(): StocksBackStack = go(PairedPage.StocksHoldings, null)

    /** The Screener page (Stocks home's section). */
    fun openScreener(): StocksBackStack = go(PairedPage.StocksScreener, null)

    /** Settings › Stocks › Screener (the page's gear, its "Change in settings", or Settings › Stocks). */
    fun openScreenerSettings(): StocksBackStack = go(PairedPage.StocksScreenerSettings, null)

    /** TBA2: the 08:30 brief's page (Stocks home's card, a brief notification). */
    fun openBrief(): StocksBackStack = go(PairedPage.StocksBrief, null)

    /** TR2: the AI track record page (Stocks home's ⋮, the verdict card's record line). */
    fun openTrackRecord(): StocksBackStack = go(PairedPage.StocksTrackRecord, null)

    /** TBA2: the stock alerts list (Stocks home's row and ⋮, a summary notification). */
    fun openAlerts(): StocksBackStack = go(PairedPage.StocksAlerts, null)

    /**
     * A notification's or a row's target: the stock, the brief or the alerts list. A target already shown (the same
     * Brief or Alerts page, the same stock) changes nothing, so a repeat tap pushes no second copy.
     */
    fun open(target: StockOpen): StocksBackStack = when (target) {
        is StockOpen.Stock -> openStock(target.code)
        StockOpen.Brief -> if (page == PairedPage.StocksBrief) this else openBrief()
        StockOpen.Alerts -> if (page == PairedPage.StocksAlerts) this else openAlerts()
    }

    /** One step back: the stocks page under this one, or Home when there is none. */
    fun back(): StocksBackStack {
        val under = below.lastOrNull() ?: return StocksBackStack(PairedPage.Sessions)
        return StocksBackStack(under.page, under.code, below.dropLast(1))
    }

    private fun go(to: PairedPage, toCode: String?): StocksBackStack {
        val here = StockPlace(page, code.takeIf { page == PairedPage.StockDetail })
        val stack = when (page) {
            in PAGES -> (below + here).takeLast(MAX_DEPTH)
            in ROOTS -> listOf(here)
            else -> emptyList()
        }
        return StocksBackStack(to, toCode, stack)
    }

    companion object {
        /** The pages this stack covers. */
        val PAGES = setOf(
            PairedPage.StocksHoldings, PairedPage.StocksNews, PairedPage.StockDetail, PairedPage.StocksScreener, PairedPage.StocksScreenerSettings,
            PairedPage.StocksBrief, PairedPage.StocksAlerts, PairedPage.StocksTrackRecord,
        )

        /** Pages outside the stack that a stocks page can be opened from and goes back to (they keep their own back). */
        val ROOTS = setOf(PairedPage.SettingsStocks)

        /** Everything that can sit under a stocks page. */
        val PLACES = PAGES + ROOTS

        /** Stack depth kept (News → stock → All news → stock … can go on): the oldest steps then go straight to Home. */
        const val MAX_DEPTH = 16
    }
}
