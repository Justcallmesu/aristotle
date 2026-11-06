package io.github.justcallmesu.aristotle

import io.github.justcallmesu.aristotle.data.stocks.StockOpen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** TBA2: a stock notification's tap opens a stock, the brief or the alerts list, only through the non-exported alias. */
class OpenStockTest {
    private fun read(target: String?, code: String? = null, action: String = OpenStock.ACTION, component: String = OpenSession.ALIAS, flags: Int = 0) =
        OpenStock.read(action, component, target, code, flags)

    @Test
    fun `the alias opens each target`() {
        assertEquals(StockOpen.Stock("BBCA"), read(OpenStock.TARGET_STOCK, "BBCA"))
        assertEquals(StockOpen.Brief, read(OpenStock.TARGET_BRIEF))
        assertEquals(StockOpen.Alerts, read(OpenStock.TARGET_ALERTS))
    }

    @Test
    fun `anything else opens nothing`() {
        assertNull("the exported launcher entry", read(OpenStock.TARGET_BRIEF, component = "io.github.justcallmesu.aristotle.MainActivity"))
        assertNull("a session's action", read(OpenStock.TARGET_BRIEF, action = OpenSession.ACTION))
        assertNull("a bad code", read(OpenStock.TARGET_STOCK, "bbca"))
        assertNull("no code", read(OpenStock.TARGET_STOCK))
        assertNull("an unknown target", read("chat"))
        assertNull("a recents relaunch", read(OpenStock.TARGET_BRIEF, flags = OpenSession.FLAG_LAUNCHED_FROM_HISTORY))
    }

    @Test
    fun `extras round-trip`() {
        for (open in listOf(StockOpen.Stock("TLKM"), StockOpen.Brief, StockOpen.Alerts)) {
            val (target, code) = OpenStock.extras(open)
            assertEquals(open, read(target, code))
        }
    }
}
