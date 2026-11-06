package io.github.justcallmesu.aristotle.ui.stocks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/** The wall clock, ticking on every minute while the screen is shown (STARTED), so "09:00 passed" shows on an idle screen. */
@Composable
internal fun rememberMinuteClock(): State<Long> {
    val owner = LocalLifecycleOwner.current
    return produceState(System.currentTimeMillis(), owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = System.currentTimeMillis()
                delay(MinuteTicks.untilNextMinute(value))
            }
        }
    }
}

internal object MinuteTicks {
    /** Milliseconds from [nowMillis] to the next full minute (never 0). */
    fun untilNextMinute(nowMillis: Long): Long = MINUTE - Math.floorMod(nowMillis, MINUTE)

    private const val MINUTE = 60_000L
}
