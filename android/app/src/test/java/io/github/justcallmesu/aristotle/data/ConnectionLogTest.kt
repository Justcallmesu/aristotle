package io.github.justcallmesu.aristotle.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Q73: the on-phone connection history: newest first, 7 days and 200 lines at most, kept across restarts. */
class ConnectionLogTest {
    private val lan = ServerEndpoint("192.168.1.61", 8760, Network.Lan)
    private val tailnet = ServerEndpoint("100.74.61.96", 8760, Network.Tailscale)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val storage = MemoryStore()

    @Volatile
    private var now = 10 * DAY

    @After
    fun stop() = scope.cancel()

    private fun log(maxEntries: Int = ConnectionLog.MAX_ENTRIES) =
        ConnectionLog(storage, scope, clock = { now }, io = Dispatchers.Default, maxEntries = maxEntries).also { it.start() }

    private fun ConnectionLog.awaitSize(size: Int) = runBlocking {
        withTimeout(5_000) { while (entries.value.size != size) delay(5) }
    }

    @Test
    fun newestFirstAndKeptAcrossARestart() {
        val log = log()
        log.record(ConnectionEvent.Connected(lan))
        now += 1_000
        log.record(ConnectionEvent.Switched(lan, tailnet))
        now += 1_000
        log.record(ConnectionEvent.Offline(OfflineReason.HermesNotRunning))
        log.awaitSize(3)
        val expected = listOf(
            LogEntry(10 * DAY + 2_000, LogKind.Offline, reason = OfflineReason.HermesNotRunning),
            LogEntry(10 * DAY + 1_000, LogKind.Switched, "100.74.61.96:8760", Network.Tailscale, from = "192.168.1.61:8760"),
            LogEntry(10 * DAY, LogKind.Connected, "192.168.1.61:8760", Network.Lan),
        )
        assertEquals(expected, log.entries.value)
        val restarted = log()
        restarted.awaitSize(3)
        assertEquals(expected, restarted.entries.value)
    }

    @Test
    fun linesDatedInTheFutureAreDroppedAfterTheClockMovedBack() {
        val log = log()
        log.record(ConnectionEvent.Connected(lan))
        log.awaitSize(1)
        now -= 3 * DAY // the clock was moved back: the line is now 3 days ahead
        log.record(ConnectionEvent.Offline(OfflineReason.ConnectionLost))
        runBlocking { withTimeout(5_000) { while (log.entries.value.firstOrNull()?.kind != LogKind.Offline) delay(5) } }
        assertEquals(listOf(LogKind.Offline), log.entries.value.map { it.kind })
        val restarted = log()
        runBlocking { delay(200) }
        assertEquals(listOf(LogKind.Offline), restarted.entries.value.map { it.kind })
    }

    @Test
    fun nothingIsWrittenOnceNoPcIsPaired() {
        val paired = java.util.concurrent.atomic.AtomicBoolean(true)
        val log = ConnectionLog(storage, scope, clock = { now }, io = Dispatchers.Default, paired = { paired.get() }).also { it.start() }
        log.record(ConnectionEvent.Connected(lan))
        log.awaitSize(1)
        runBlocking { log.clear() }
        paired.set(false) // Unpair wiped the pairing; a late event of the old one arrives
        log.record(ConnectionEvent.Offline(OfflineReason.Revoked))
        runBlocking { delay(200) }
        assertTrue(log.entries.value.isEmpty())
        assertEquals(null, storage.get("entries"))
    }

    @Test
    fun boundedTo200LinesAndSevenDays() {
        val log = log()
        repeat(ConnectionLog.MAX_ENTRIES + 5) { i ->
            now = 10 * DAY + i
            log.record(ConnectionEvent.Found(lan, auto = i % 2 == 0))
            // One at a time: a full queue drops its oldest event by design, which is not what this test is about.
            runBlocking { withTimeout(5_000) { while (log.entries.value.firstOrNull()?.at != now) delay(1) } }
        }
        assertEquals(ConnectionLog.MAX_ENTRIES, log.entries.value.size)
        assertEquals(now, log.entries.value.first().at)

        now += 7 * DAY - 100 // the oldest lines are older than 7 days now
        log.record(ConnectionEvent.NotFound)
        runBlocking { withTimeout(5_000) { while (log.entries.value.first().kind != LogKind.NotFound) delay(5) } }
        assertTrue(log.entries.value.all { now - it.at < 7 * DAY })
        assertTrue(log.entries.value.size < ConnectionLog.MAX_ENTRIES)

        now += 8 * DAY // a restart much later: nothing older than 7 days is shown
        val later = ConnectionLog(storage, scope, clock = { now }, io = Dispatchers.Default).also { it.start() }
        runBlocking { delay(200) }
        assertTrue(later.entries.value.isEmpty())
    }

    @Test
    fun clearEmptiesTheFileAndDropsWhatWasQueued() = runBlocking {
        val log = log()
        log.record(ConnectionEvent.CantScan)
        log.awaitSize(1)
        log.record(ConnectionEvent.Deleted(lan)) // queued before the clear: must not come back after it
        log.clear()
        delay(200)
        assertTrue(log.entries.value.isEmpty())
        assertFalse(storage.map.values.any { it.isNotBlank() })
        log.record(ConnectionEvent.Saved(lan, AddressSource.FoundOnWifi))
        log.awaitSize(1)
        assertEquals(LogKind.Saved, log.entries.value.single().kind)
        assertEquals(AddressSource.FoundOnWifi, log.entries.value.single().source)
    }

    @Test
    fun aLineThisBuildCannotReadIsSkipped() {
        storage.map["entries"] = "{not json\n" + """{"at":${10 * DAY},"kind":"CantScan"}"""
        val log = log()
        log.awaitSize(1)
        assertEquals(LogKind.CantScan, log.entries.value.single().kind)
    }

    @Test
    fun recordingNeverWaits() {
        val log = ConnectionLog(storage, scope, clock = { now }) // never started: nothing drains the queue
        val started = System.nanoTime()
        repeat(10_000) { log.record(ConnectionEvent.Connected(lan)) }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)
    }

    @Test
    fun nothingSecretCanBeStored() {
        // Only addresses, kinds and enums: the event types hold no token, fingerprint, session id or text.
        val line = LogEntry.of(ConnectionEvent.Offline(OfflineReason.Revoked), 1)
        assertEquals(LogEntry(1, LogKind.Offline, reason = OfflineReason.Revoked), line)
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
    }
}
