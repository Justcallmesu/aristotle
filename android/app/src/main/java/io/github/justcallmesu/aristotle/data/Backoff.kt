package io.github.justcallmesu.aristotle.data

import kotlin.math.min

/**
 * Reconnect delays: [baseMillis] doubling per failed round up to [capMillis], with jitter so many phones
 * (or a phone and a restarted server) do not retry in step. The delay for a step S lies in [S/2, S].
 */
class Backoff(
    private val baseMillis: Long = 1_000,
    private val capMillis: Long = 30_000,
    private val random: () -> Double = Math::random,
) {
    /** The delay before retry [attempt] (1 = the first retry after a success or a start). */
    fun delayMillis(attempt: Int): Long {
        val step = stepMillis(attempt)
        val half = step / 2
        return half + (half * random().coerceIn(0.0, 1.0)).toLong()
    }

    /** The capped exponential step before jitter. */
    fun stepMillis(attempt: Int): Long {
        val doublings = (attempt - 1).coerceIn(0, MAX_DOUBLINGS)
        return min(capMillis, baseMillis shl doublings)
    }

    private companion object {
        const val MAX_DOUBLINGS = 20
    }
}
