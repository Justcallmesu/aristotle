package io.github.justcallmesu.aristotle.data.stocks

/** Where a stock notification (or a row of the alerts list) opens. */
sealed interface StockOpen {
    data class Stock(val code: String) : StockOpen
    data object Brief : StockOpen
    data object Alerts : StockOpen

    companion object {
        /** A stock's alert opens its page; the brief its page; a summary or a code-less alert the list. */
        fun of(alert: StockAlert): StockOpen = when {
            alert.alertKind == AlertKind.Brief -> Brief
            alert.alertKind == AlertKind.Summary -> Alerts
            else -> alert.code?.takeIf { StockCodes.valid(it) }?.let(::Stock) ?: Alerts
        }
    }
}

/** IDX codes as the server writes them (`^[A-Z]{4}$`, protocol §20). */
object StockCodes {
    private val CODE = Regex("^[A-Z]{4}$")

    fun valid(code: String?): Boolean = code != null && CODE.matches(code)
}
