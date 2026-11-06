package io.github.justcallmesu.aristotle.data.stocks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TBA2 (Q104): the holding sheet's optional stop/target, checked as the server's `checkLevels` (§20). */
class HoldingLevelsTest {
    @Test
    fun `empty levels are none and valid`() {
        val levels = HoldingInput.levels("", "  ", 4800.0)
        assertTrue(levels.valid)
        assertNull(levels.stop.value)
        assertNull(levels.target.value)
    }

    @Test
    fun `a level is a whole price on its band's tick grid`() {
        assertEquals(LevelField(4600, null), HoldingInput.level("4.600"))
        assertEquals(LevelField(4600, null), HoldingInput.level("Rp 4600"))
        assertEquals(LevelField(9875, null), HoldingInput.level("9875"))
        assertEquals(LevelField(null, LevelProblem.OffTick, tick = 10, near = 4370L to 4380L), HoldingInput.level("4375"))
        assertEquals(LevelField(null, LevelProblem.OffTick, tick = 25, near = 9850L to 9875L), HoldingInput.level("9860"))
        assertEquals(LevelField(null, LevelProblem.OffTick, tick = 2, near = 200L to 202L), HoldingInput.level("201"))
        assertEquals(LevelField(199, null), HoldingInput.level("199"))
        assertEquals(LevelProblem.NotWhole, HoldingInput.level("4600,5").problem)
        assertEquals(LevelProblem.NotANumber, HoldingInput.level("abc").problem)
        assertEquals(LevelProblem.TooSmall, HoldingInput.level("0").problem)
        assertEquals(LevelProblem.TooLarge, HoldingInput.level("10.000.025").problem)
        assertEquals(LevelField(10_000_000, null), HoldingInput.level("10.000.000"))
    }

    @Test
    fun `the stop sits below the average and below the target`() {
        val ok = HoldingInput.levels("4600", "5100", 4812.51)
        assertTrue(ok.valid)
        assertEquals(4600L, ok.stop.value)
        assertEquals(5100L, ok.target.value)
        assertEquals(LevelProblem.NotBelowAverage, HoldingInput.levels("4820", "", 4812.51).stop.problem)
        assertEquals(LevelProblem.NotBelowAverage, HoldingInput.levels("4800", "", 4800.0).stop.problem)
        assertEquals(LevelProblem.NotBelowTarget, HoldingInput.levels("4600", "4600", 4800.0).stop.problem)
        assertFalse(HoldingInput.levels("4600", "4500", 4800.0).valid)
        // The server sets no rule between the target and the average: a target under the average is accepted.
        assertTrue(HoldingInput.levels("", "4500", 4800.0).valid)
        // Without a valid average only the grid and the stop/target order are checked.
        assertTrue(HoldingInput.levels("4600", "5100", null).valid)
    }

    @Test
    fun `stored levels go back into the fields as plain digits`() {
        assertEquals("4600", HoldingInput.levelText(4600))
        assertEquals("", HoldingInput.levelText(null))
    }
}
