package io.github.justcallmesu.aristotle.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How long Aristotle may be in the background before the app lock asks again (Q61). */
enum class LockTimeout(val key: String, val millis: Long) {
    Immediately("0", 0),
    OneMinute("1", 60_000),
    FiveMinutes("5", 5 * 60_000),
    FifteenMinutes("15", 15 * 60_000),
    ;

    companion object {
        val Default = OneMinute
    }
}

/**
 * When the app lock screen covers the app (Q61, TS2). Pure: [now] is a monotonic clock that keeps counting in deep
 * sleep (`elapsedRealtime` in the app), the activities' start/stop arrive through [onStarted]/[onStopped], and the
 * setting is read through [enabled] and [timeout] each time.
 *
 * - A new process starts locked while the lock is on: process death counts as locked.
 * - Back after at least [LockTimeout.millis] in the background locks it; time on screen never does. A rotation is not
 *   time away.
 * - While the app itself asks for a fingerprint or PIN ([authStarted]..[authEnded]) the system's PIN screen may stop
 *   the activity. The time away is still measured from that stop, and it counts unless the check PASSED: a cancelled
 *   or abandoned check does not suppress locking, a passed one does not lock again right after itself. A check that
 *   never calls back is capped at [CHECK_CAP_MILLIS]: past it, it counts as failed. That is judged when the cap
 *   expires ([schedule], so also while the app stays in front), and again on the next start or stop.
 *
 * Main thread only, like the activity callbacks that drive it.
 */
class AppLock(
    private val enabled: () -> Boolean,
    private val timeout: () -> LockTimeout,
    private val now: () -> Long,
    /** Runs the action on the main thread after the delay (a Handler in the app); the cap's timer. */
    private val schedule: (delayMillis: Long, action: () -> Unit) -> Unit = { _, _ -> },
) {
    private val state = MutableStateFlow(enabled())
    val locked: StateFlow<Boolean> = state.asStateFlow()

    private var started = 0
    private var awaySince: Long? = null
    /** The checks running now, by the id [authStarted] gave them; one given up at the cap is dropped from it. */
    private val running = HashSet<Long>()
    private var lastCheck = 0L
    private var authStartedAt = 0L

    /** A return that happened while a check was still running: (stop time, return time), judged when it ends. */
    private var returnedDuringCheck: Pair<Long, Long>? = null

    fun onStarted() {
        started++
        if (started != 1) return
        val since = awaySince ?: return
        awaySince = null
        val at = now()
        expireCheck()
        if (running.isNotEmpty()) {
            returnedDuringCheck = since to at
            return
        }
        lockIfAway(since, at)
    }

    private fun lockIfAway(since: Long, until: Long) {
        if (enabled() && until - since >= timeout().millis) state.value = true
    }

    fun onStopped(changingConfigurations: Boolean) {
        expireCheck()
        started = (started - 1).coerceAtLeast(0)
        if (started > 0 || changingConfigurations) return
        awaySince = now()
    }

    /** A check starts; pass the id it returns to [authEnded]. */
    fun authStarted(): Long {
        if (running.isEmpty()) {
            authStartedAt = now()
            schedule(CHECK_CAP_MILLIS, ::expireCheck)
        }
        return (++lastCheck).also { running += it }
    }

    /**
     * Whether a check is still running; one past [CHECK_CAP_MILLIS] is given up first (failed), so a check that never
     * called back cannot block the next one for good ([AuthBroker]).
     */
    fun checkRunning(): Boolean {
        expireCheck()
        return running.isNotEmpty()
    }

    /** The running check is past the cap: it failed. A timer of an earlier check that already ended finds none. */
    private fun expireCheck() {
        if (running.isNotEmpty() && now() - authStartedAt >= CHECK_CAP_MILLIS) abandonCheck()
    }

    /** The running check never called back within the cap: it failed. What it was holding back is judged now. */
    private fun abandonCheck() {
        running.clear()
        returnedDuringCheck?.let { lockIfAway(it.first, it.second) }
        returnedDuringCheck = null
    }

    /**
     * The check [check] ended. [passed]: what happened while it ran is forgiven. Otherwise the time away stands,
     * measured from the stop, and a return that came during the check is judged now. The late end of a check given
     * up at the cap changes nothing: it already counted as failed, and a newer check may be running.
     */
    fun authEnded(check: Long, passed: Boolean) {
        if (!running.remove(check)) return
        if (running.isNotEmpty()) return
        val returned = returnedDuringCheck
        returnedDuringCheck = null
        if (passed) {
            awaySince = null
            return
        }
        if (returned != null) lockIfAway(returned.first, returned.second)
    }

    /** A check passed, or the lock was turned off. */
    fun unlocked() {
        state.value = false
    }

    companion object {
        /** An auth check that has not ended after this long is treated as failed. */
        const val CHECK_CAP_MILLIS = 2 * 60_000L
    }
}
