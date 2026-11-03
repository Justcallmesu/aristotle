package io.github.justcallmesu.aristotle.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceNameTest {
    @Test
    fun theUserVisibleNameWins() {
        assertEquals("POCO X5 Pro 5G", DeviceName.of(" POCO X5 Pro 5G ", "Xiaomi", "22101320G"))
    }

    @Test
    fun withoutItMakerAndModelArePrettified() {
        assertEquals("Xiaomi 22101320G", DeviceName.of(null, "xiaomi", "22101320G"))
        assertEquals("Xiaomi 22101320G", DeviceName.of("  ", "Xiaomi", "22101320G"))
        assertEquals("Google Pixel 8", DeviceName.of(null, "Google", "Pixel 8"))
        assertEquals("Samsung SM-S918B", DeviceName.of(null, "samsung", "SM-S918B"))
        assertEquals("OnePlus 12", DeviceName.of(null, "OnePlus", "OnePlus 12"))
        assertEquals("", DeviceName.of(null, null, null))
    }
}
