package io.github.justcallmesu.aristotle.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** TR7: the meter fills of the PC card are clamped to 0..1 and null-safe. */
class PcMeterFractionTest {
    @Test
    fun batteryFraction() {
        assertEquals(0.58f, PcStatusFormat.batteryFraction(PcBattery(58, false))!!, 1e-6f)
        assertEquals(0f, PcStatusFormat.batteryFraction(PcBattery(0, true))!!, 0f)
        assertEquals(1f, PcStatusFormat.batteryFraction(PcBattery(250, false))!!, 0f)
        assertNull(PcStatusFormat.batteryFraction(null))
    }

    @Test
    fun cpuFraction() {
        assertEquals(0.27f, PcStatusFormat.cpuFraction(27.0)!!, 1e-6f)
        assertEquals(1f, PcStatusFormat.cpuFraction(140.0)!!, 0f)
        assertEquals(0f, PcStatusFormat.cpuFraction(-3.0)!!, 0f)
        assertNull(PcStatusFormat.cpuFraction(null))
        assertNull(PcStatusFormat.cpuFraction(Double.NaN))
    }

    @Test
    fun ramFraction() {
        assertEquals(0.5f, PcStatusFormat.ramFraction(PcMemory(5, 10))!!, 1e-6f)
        assertEquals(1f, PcStatusFormat.ramFraction(PcMemory(20, 10))!!, 0f)
        assertNull(PcStatusFormat.ramFraction(PcMemory(1, 0)))
        assertNull(PcStatusFormat.ramFraction(null))
    }

    @Test
    fun diskUsedFraction() {
        assertEquals(0.815f, PcStatusFormat.diskUsedFraction(PcDisk(34_000_000_000, 184_000_000_000))!!, 0.001f)
        assertEquals(0f, PcStatusFormat.diskUsedFraction(PcDisk(500, 100))!!, 0f)
        assertNull(PcStatusFormat.diskUsedFraction(PcDisk(1, 0)))
        assertNull(PcStatusFormat.diskUsedFraction(null))
    }
}
