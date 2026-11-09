package io.github.justcallmesu.aristotle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** UP2: the update notification opens Settings › About, only through the non-exported alias. */
class OpenUpdateTest {
    @Test
    fun `the alias with the update action opens About`() {
        assertTrue(OpenUpdate.read(OpenUpdate.ACTION, OpenSession.ALIAS))
    }

    @Test
    fun `anything else opens nothing`() {
        assertFalse("the exported launcher entry", OpenUpdate.read(OpenUpdate.ACTION, "io.github.justcallmesu.aristotle.MainActivity"))
        assertFalse("a stock's action", OpenUpdate.read(OpenStock.ACTION, OpenSession.ALIAS))
        assertFalse("no action", OpenUpdate.read(null, OpenSession.ALIAS))
        assertFalse("a recents relaunch", OpenUpdate.read(OpenUpdate.ACTION, OpenSession.ALIAS, OpenSession.FLAG_LAUNCHED_FROM_HISTORY))
    }
}
