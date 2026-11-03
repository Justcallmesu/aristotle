package io.github.justcallmesu.aristotle.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reconnect schedule: 1 s doubling to a 30 s cap; jitter keeps each delay in [step/2, step]. */
class BackoffTest {
    @Test
    fun stepsDoubleFromOneSecondToTheCap() {
        val steps = (1..8).map { Backoff().stepMillis(it) }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L), steps)
        assertEquals(30_000L, Backoff().stepMillis(1_000)) // no overflow for long outages
        assertEquals(1_000L, Backoff().stepMillis(0))
    }

    @Test
    fun jitterStaysWithinHalfToFullStep() {
        assertEquals(listOf(500L, 1_000L, 2_000L, 15_000L), listOf(1, 2, 3, 7).map { Backoff(random = { 0.0 }).delayMillis(it) })
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 30_000L), listOf(1, 2, 3, 7).map { Backoff(random = { 1.0 }).delayMillis(it) })
        val random = Backoff()
        repeat(200) {
            val attempt = it % 10 + 1
            val delay = random.delayMillis(attempt)
            val step = random.stepMillis(attempt)
            assertTrue("$delay outside [${step / 2}, $step]", delay in step / 2..step)
        }
    }
}
