package io.github.justcallmesu.aristotle.data.terminal

/**
 * Debounces size changes (layout, rotation, keyboard up/down, pinch zoom) into `resize` frames: a size is sent once it
 * has held for [quietMillis], and only when it differs from the last one sent. Pure; the caller supplies the clock.
 */
class ResizeGate(private val quietMillis: Long = DEFAULT_QUIET_MILLIS) {
    private var pending: TermSize? = null
    private var pendingSince = 0L
    private var sent: TermSize? = null

    /** The view measured [size] at [nowMillis]; restarts the quiet period. */
    fun request(size: TermSize, nowMillis: Long) {
        if (size == pending) return
        pending = size
        pendingSince = nowMillis
    }

    /** Milliseconds until [due] may return a size, or null when nothing waits. */
    fun waitMillis(nowMillis: Long): Long? {
        val size = pending ?: return null
        if (size == sent) return null
        return maxOf(0L, pendingSince + quietMillis - nowMillis)
    }

    /** The size to send now, if one has been quiet long enough and is new; marks it sent. */
    fun due(nowMillis: Long): TermSize? {
        val size = pending ?: return null
        if (size == sent || nowMillis - pendingSince < quietMillis) return null
        sent = size
        return size
    }

    /** Sends [size] at once (the first frame after the upgrade, protocol §14); later equal requests send nothing. */
    fun sentNow(size: TermSize) {
        sent = size
        pending = size
    }

    companion object {
        const val DEFAULT_QUIET_MILLIS = 150L
    }
}
