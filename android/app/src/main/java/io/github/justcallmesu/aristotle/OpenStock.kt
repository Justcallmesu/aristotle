package io.github.justcallmesu.aristotle

import io.github.justcallmesu.aristotle.data.stocks.StockCodes
import io.github.justcallmesu.aristotle.data.stocks.StockOpen

/**
 * How a stock notification opens its page (TBA2): like [OpenSession], only through the non-exported alias, with its own
 * action. [read] turns the intent's parts into the page to show; anything else (another component, a bad code, a recents
 * relaunch) opens nothing. Pure.
 */
object OpenStock {
    const val ACTION = "io.github.justcallmesu.aristotle.OPEN_STOCK"
    const val EXTRA_TARGET = "target"
    const val EXTRA_CODE = "code"

    const val TARGET_STOCK = "stock"
    const val TARGET_BRIEF = "brief"
    const val TARGET_ALERTS = "alerts"

    fun read(action: String?, component: String?, target: String?, code: String?, flags: Int = 0): StockOpen? {
        if (action != ACTION || component != OpenSession.ALIAS) return null
        if (flags and OpenSession.FLAG_LAUNCHED_FROM_HISTORY != 0) return null
        return when (target) {
            TARGET_STOCK -> code?.takeIf { StockCodes.valid(it) }?.let(StockOpen::Stock)
            TARGET_BRIEF -> StockOpen.Brief
            TARGET_ALERTS -> StockOpen.Alerts
            else -> null
        }
    }

    /** The extras for [open]: its target and, for a stock, the code. */
    fun extras(open: StockOpen): Pair<String, String?> = when (open) {
        is StockOpen.Stock -> TARGET_STOCK to open.code
        StockOpen.Brief -> TARGET_BRIEF to null
        StockOpen.Alerts -> TARGET_ALERTS to null
    }
}
