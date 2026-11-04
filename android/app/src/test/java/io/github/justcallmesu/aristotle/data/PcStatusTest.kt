package io.github.justcallmesu.aristotle.data

import java.util.Locale
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Protocol §16: the parser, the units and the call. */
class PcStatusTest {
    private val full = """
        {"at":"2026-11-04T17:40:00+07:00","battery":{"percent":82,"charging":true},"cpu_percent":14.2,
         "memory":{"used_bytes":9771728896,"total_bytes":16624144384},
         "disk":{"path":"/home/u","free_bytes":120000000000,"total_bytes":500000000000},
         "hermes_running":true,"tailscale_up":true,"uptime_seconds":86400}
    """

    @Test
    fun readsEveryField() {
        val s = PcStatusParser.parse(full)!!
        assertEquals(PcBattery(82, true), s.battery)
        assertEquals(14.2, s.cpuPercent!!, 0.0)
        assertEquals(PcMemory(9_771_728_896, 16_624_144_384), s.memory)
        assertEquals(PcDisk(120_000_000_000, 500_000_000_000), s.disk)
        assertEquals(true, s.hermesRunning)
        assertEquals(true, s.tailscaleUp)
        assertEquals(86_400L, s.uptimeSeconds)
        assertTrue(s.hasData)
    }

    @Test
    fun allNullIsAnEmptyReading() {
        val s = PcStatusParser.parse("""{"at":"2026-11-04T17:40:00+07:00","battery":null,"cpu_percent":null,"memory":null,"disk":null,"hermes_running":null,"tailscale_up":null,"uptime_seconds":null}""")!!
        assertEquals(PcStatus(), s)
        assertFalse(s.hasData)
        assertFalse(PcStatusView(s, 1).shown != null)
    }

    @Test
    fun missingFieldsAndNoBatteryAreNull() {
        val s = PcStatusParser.parse("""{"at":"x","cpu_percent":3,"battery":null}""")!!
        assertNull(s.battery)
        assertEquals(3.0, s.cpuPercent!!, 0.0)
        assertNull(s.memory)
        assertTrue(s.hasData)
    }

    @Test
    fun wrongTypesAndNonsenseBecomeNull() {
        val s = PcStatusParser.parse(
            """{"battery":{"percent":"high","charging":true},"cpu_percent":"x","memory":{"used_bytes":1,"total_bytes":0},"disk":{"free_bytes":-1,"total_bytes":5},"hermes_running":"yes","tailscale_up":1,"uptime_seconds":-5}""",
        )!!
        assertEquals(PcStatus(), s)
        assertNull(PcStatusParser.parse("""{"battery":{"percent":140,"charging":false}}""")!!.battery)
    }

    @Test
    fun batteryWithoutChargingFlagIsNotCharging() {
        assertEquals(PcBattery(40, false), PcStatusParser.parse("""{"battery":{"percent":40}}""")!!.battery)
    }

    @Test
    fun usedAboveTotalIsClamped() {
        assertEquals(PcMemory(100, 100), PcStatusParser.parse("""{"memory":{"used_bytes":150,"total_bytes":100}}""")!!.memory)
    }

    @Test
    fun notAnObjectIsNotAReading() {
        assertNull(PcStatusParser.parse(""))
        assertNull(PcStatusParser.parse("[]"))
        assertNull(PcStatusParser.parse("not json"))
    }

    @Test
    fun memoryInGbWithOneDecimal() {
        assertEquals("9.1", PcStatusFormat.memoryGb(9_771_728_896, Locale.ENGLISH))
        assertEquals("15.5", PcStatusFormat.memoryGb(16_624_144_384, Locale.ENGLISH))
        assertEquals("0.0", PcStatusFormat.memoryGb(0, Locale.ENGLISH))
        assertEquals("9,1", PcStatusFormat.memoryGb(9_771_728_896, Locale.forLanguageTag("id")))
    }

    @Test
    fun diskSizes() {
        assertEquals("120 GB", PcStatusFormat.diskSize(120_000_000_000, Locale.ENGLISH))
        assertEquals("500 GB", PcStatusFormat.diskSize(500_000_000_000, Locale.ENGLISH))
        assertEquals("1.5 TB", PcStatusFormat.diskSize(1_500_000_000_000, Locale.ENGLISH))
        assertEquals("2.5 GB", PcStatusFormat.diskSize(2_500_000_000, Locale.ENGLISH))
    }

    @Test
    fun percentRoundsAndClamps() {
        assertEquals(14, PcStatusFormat.percent(14.2))
        assertEquals(15, PcStatusFormat.percent(14.5))
        assertEquals(100, PcStatusFormat.percent(100.0))
    }

    @Test
    fun uptimeIsDaysHoursOrHoursMinutesOrMinutes() {
        assertEquals(Uptime(1, 4, 0), PcStatusFormat.uptime(86_400 + 4 * 3_600 + 59))
        assertEquals(Uptime.Kind.DaysHours, PcStatusFormat.uptime(100_000).kind)
        assertEquals(Uptime(0, 3, 25), PcStatusFormat.uptime(3 * 3_600 + 25 * 60))
        assertEquals(Uptime.Kind.HoursMinutes, PcStatusFormat.uptime(3_600).kind)
        assertEquals(Uptime(0, 0, 59), PcStatusFormat.uptime(59 * 60 + 59))
        assertEquals(Uptime.Kind.Minutes, PcStatusFormat.uptime(5).kind)
        assertEquals(Uptime(0, 0, 0), PcStatusFormat.uptime(-3))
    }
}

/** `GET /v1/pc-status` through the pinned client. */
class PcStatusCallTest {
    private val held = lanCertificate()
    private val server = startServer(held)

    @After
    fun stop() = server.close()

    private fun respond(code: Int, body: String) = server.enqueue(MockResponse.Builder().code(code).body(body).build())

    private fun client() = ServerClient(testClients().pinnedTo(pinOf(held), Network.Lan), localEndpoint(server).baseUrl)

    @Test
    fun sendsTheTokenAndParses() = runBlocking {
        respond(200, """{"at":"x","cpu_percent":14.2,"battery":null}""")
        val s = client().pcStatus(DeviceToken("tok"))!!
        assertEquals(14.2, s.cpuPercent!!, 0.0)
        val request = server.takeRequest()
        assertEquals("/v1/pc-status", request.url.encodedPath)
        assertEquals("Bearer tok", request.headers["Authorization"])
    }

    @Test
    fun anyFailureIsNull() = runBlocking {
        respond(401, """{"error":"unauthorized"}""")
        assertNull(client().pcStatus(DeviceToken("tok")))
        respond(200, "oops")
        assertNull(client().pcStatus(DeviceToken("tok")))
    }
}
