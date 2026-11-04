package io.github.justcallmesu.aristotle.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupedRowsTest {
    @Test fun positions() {
        assertEquals(GroupedRowPosition.Single, groupedRowPosition(0, 1))
        assertEquals(GroupedRowPosition.First, groupedRowPosition(0, 3))
        assertEquals(GroupedRowPosition.Middle, groupedRowPosition(1, 3))
        assertEquals(GroupedRowPosition.Last, groupedRowPosition(2, 3))
        assertEquals(GroupedRowPosition.First, groupedRowPosition(0, 2))
        assertEquals(GroupedRowPosition.Last, groupedRowPosition(1, 2))
    }

    @Test fun cornersOuterLargeInnerExtraSmall() {
        assertEquals(20 to 20, groupedCornerDp(GroupedRowPosition.Single))
        assertEquals(20 to 4, groupedCornerDp(GroupedRowPosition.First))
        assertEquals(4 to 4, groupedCornerDp(GroupedRowPosition.Middle))
        assertEquals(4 to 20, groupedCornerDp(GroupedRowPosition.Last))
    }

    @Test fun sliderPureLogic() {
        assertEquals(0, sliderIndex(-0.4f, 8))
        assertEquals(3, sliderIndex(2.6f, 8))
        assertEquals(7, sliderIndex(9f, 8))
        assertEquals(6, sliderSteps(8))
        assertEquals(5, sliderSteps(7))
        assertEquals(0, sliderSteps(2))
        assertTrue(shouldCommit(2, 4))
        assertFalse(shouldCommit(4, 4))
    }
}
