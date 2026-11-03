package io.github.justcallmesu.aristotle.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuoteMotionTest {
    @Test
    fun onlyAnimatorScaleZeroIsReduced() {
        assertTrue(QuoteMotion.reduced(0f))
        assertFalse(QuoteMotion.reduced(1f))
        assertFalse(QuoteMotion.reduced(0.5f))
    }

    @Test
    fun linesAreStaggeredAndFadeFourHundredMs() {
        assertEquals(0, QuoteMotion.delayMillis(0, false))
        assertEquals(QuoteMotion.STAGGER_MILLIS * 2, QuoteMotion.delayMillis(2, false))
        assertEquals(400, QuoteMotion.durationMillis(false))
    }

    @Test
    fun reducedMotionShowsAllAtOnce() {
        assertEquals(0, QuoteMotion.delayMillis(2, true))
        assertEquals(0, QuoteMotion.durationMillis(true))
    }
}
