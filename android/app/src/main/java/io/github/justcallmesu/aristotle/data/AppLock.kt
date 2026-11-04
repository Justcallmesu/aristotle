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
 *   or abandoned check does not suppress locking, a passed one does not lock again right after itself.
 *
 * Main thread only, like the activity callbacks that drive it.
 */
class AppLock(
    private val enabled: () -> Boolean,
    private val timeout: () -> LockTimeout,
    private val now: () -> Long,
) {
    private val state = MutableStateFlow(enabled())
    val locked: StateFlow<Boolean> = state.asStateFlow()

    private var started = 0
    private var awaySince: Long? = null
    private var authenticating = 0

    /** A return that happened while a check was still running: (stop time, return time), judged when it ends. */
    private var returnedDuringCheck: Pair<Long, Long>? = null

    fun onStarted() {
        started++
        if (started != 1) return
        val since = awaySince ?: return
        awaySince = null
        if (authenticating > 0) {
            returnedDuringCheck = since to now()
            return
        }
        lockIfAway(since, now())
    }

    private fun lockIfAway(since: Long, until: Long) {
        if (enabled() && until - since >= timeout().millis) state.value = true
    }

    fun onStopped(changingConfigurations: Boolean) {
        started = (started - 1).coerceAtLeast(0)
        if (started > 0 || changingConfigurations) return
        awaySince = now()
    }

    fun authStarted() {
        authenticating++
    }

    /**
     * The check ended. [passed]: what happened while it ran is forgiven. Otherwise the time away stands, measured
     * from the stop, and a return that came during the check is judged now.
     */
    fun authEnded(passed: Boolean) {
        authenticating = (authenticating - 1).coerceAtLeast(0)
        if (authenticating > 0) return
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
}
