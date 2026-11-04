package io.github.justcallmesu.aristotle.data

/** When the quote inside a loading chat shows (Q59). Pure; the composable only feeds it clock readings. */
object ChatQuoteTiming {
    /** A history that arrives sooner than this gets no quote, only the quiet line. */
    const val DELAY_MILLIS = 300L

    /** Once a quote showed it stays at least this long, so it never flashes. */
    const val MIN_SHOW_MILLIS = 1200L

    /** Wait before the quote appears, given how long the chat has been loading. */
    fun delayBeforeShow(loadingMillis: Long): Long = (DELAY_MILLIS - loadingMillis).coerceAtLeast(0)

    /** Wait before the quote may fade out, given how long it has been visible. */
    fun holdRemaining(shownMillis: Long): Long = (MIN_SHOW_MILLIS - shownMillis).coerceAtLeast(0)
}
