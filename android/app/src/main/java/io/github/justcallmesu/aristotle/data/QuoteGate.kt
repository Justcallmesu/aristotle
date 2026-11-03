package io.github.justcallmesu.aristotle.data

/** What the quote screen needs to know (spec Q44). */
data class GateInput(
    /** The stored pairing is still being read (app start). */
    val reading: Boolean,
    val paired: Boolean,
    /** The link is Idle or Connecting: no first state yet, or reconnecting after a network switch. */
    val linkPending: Boolean,
)

data class GateOutput(val visible: Boolean, val recheckAfterMillis: Long = 0)

/**
 * When the quote loading screen shows (Q44): while the pairing is read, and while a paired link is connecting
 * and no content has been shown yet in this process. Once shown it stays at least [minMillis]; not paired gives
 * way at once. Not thread safe: the main thread owns it. Time is passed in, so it is testable.
 */
class QuoteGate(private val minMillis: Long = MIN_VISIBLE_MILLIS) {
    private var shownAt: Long? = null
    private var contentShown = false

    fun update(input: GateInput, now: Long): GateOutput {
        if (!input.reading && !input.paired) {
            shownAt = null
            return GateOutput(visible = false)
        }
        val wanted = input.reading || (input.linkPending && !contentShown)
        val since = shownAt
        if (wanted) {
            if (since == null) shownAt = now
            return GateOutput(visible = true)
        }
        if (since != null) {
            val remaining = since + minMillis - now
            if (remaining > 0) return GateOutput(visible = true, recheckAfterMillis = remaining)
            shownAt = null
        }
        contentShown = true
        return GateOutput(visible = false)
    }

    companion object {
        const val MIN_VISIBLE_MILLIS = 1200L
    }
}

/** Motion of the quote lines (Q46, design.md §4 reduced motion). */
object QuoteMotion {
    const val FADE_MILLIS = 400
    const val STAGGER_MILLIS = 350
    const val STATIC_PROGRESS = 0.4f

    /** Animator scale 0 ("remove animations"): everything at once, the progress line static. */
    fun reduced(animatorScale: Float): Boolean = animatorScale == 0f

    fun delayMillis(line: Int, reduced: Boolean): Int = if (reduced) 0 else line * STAGGER_MILLIS

    fun durationMillis(reduced: Boolean): Int = if (reduced) 0 else FADE_MILLIS
}
