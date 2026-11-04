package io.github.justcallmesu.aristotle.data

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** TL2 (Q80b): the crash file's life (write, offer once, send or dismiss deletes) and the shared files. */
class CrashReportsTest {
    private val root: File = Files.createTempDirectory("crash").toFile()
    private val info = AppInfo("1.0.0", 12, "14", 34, "Xiaomi", "22101320G")

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun diagnostics() = Diagnostics(File(root, "log"), File(root, "crash"), File(root, "share"), info, clock = { 1_000L })

    @Test
    fun theStoreOffersACrashOnceAndDismissDeletesIt() {
        val store = CrashStore(File(root, "crash"))
        assertFalse(store.takeOffer())
        store.write("report 1")
        assertTrue(store.takeOffer())
        assertEquals("report 1", store.offered())
        // The next launch never offers the same report again, even if nothing was chosen.
        assertFalse(store.takeOffer())
        assertNull(store.offered())
        store.write("report 2")
        assertTrue(store.takeOffer())
        store.dismiss()
        assertNull(store.offered())
        assertEquals(emptyList<String>(), File(root, "crash").list()!!.toList())
    }

    @Test
    fun theHandlerWritesTheReportThenChainsToThePreviousHandler() {
        val d = diagnostics()
        d.pipeline.log(LogLevel.Info, "link state=Online network=Lan", null)
        val chained = mutableListOf<Throwable>()
        val handler = d.crashHandler { _, e -> chained += e }
        val error = IllegalStateException("boom token=abc", RuntimeException("cause prompt=my words"))
        handler.uncaughtException(Thread.currentThread(), error)
        assertSame(error, chained.single())
        d.loadOffer()
        assertTrue(d.crashOffered.value)
        val file = d.crashReportFile()
        assertNotNull(file)
        val text = file!!.readText()
        assertTrue(text, text.startsWith("Aristotle crash report\n"))
        assertTrue(text.contains("App: Aristotle 1.0.0 (12)"))
        assertTrue(text.contains("Android: 14 (SDK 34)"))
        assertTrue(text.contains("Device: Xiaomi 22101320G"))
        assertTrue(text.contains("java.lang.IllegalStateException: boom token=[redacted]"))
        assertTrue(text.contains("Caused by: java.lang.RuntimeException: cause prompt= [redacted]"))
        assertTrue(text.contains("    at io.github.justcallmesu.aristotle.data.CrashReportsTest"))
        assertTrue(text.contains("link state=Online network=Lan"))
        assertFalse(text.contains("abc") || text.contains("my words"))
        // Sent: offered once, then gone.
        assertFalse(d.crashOffered.value)
        assertNull(d.crashReportFile())
        assertEquals(emptyList<String>(), File(root, "crash").list()!!.toList())
        assertEquals(File(File(root, Diagnostics.SHARE_DIR), Diagnostics.CRASH_FILE), file)
    }

    @Test
    fun dismissDeletesTheCrashFile() {
        val d = diagnostics()
        assertTrue(d.record("main", IllegalArgumentException()))
        d.loadOffer()
        assertTrue(d.crashOffered.value)
        d.dismissCrash()
        assertFalse(d.crashOffered.value)
        assertEquals(emptyList<String>(), File(root, "crash").list()!!.toList())
        d.loadOffer()
        assertFalse(d.crashOffered.value)
    }

    @Test
    fun deepCauseChainsAreCut() {
        val d = diagnostics()
        val deep = generateSequence<Throwable>(RuntimeException("a")) { if (it.message!!.length < 20) RuntimeException(it.message + "a", it) else null }.last()
        assertTrue(d.record("worker", deep))
        d.loadOffer()
        val text = d.crashReportFile()!!.readText()
        assertTrue(text.contains("Thread: worker"))
        assertTrue(text.lines().count { it.startsWith("Caused by: ") } <= CrashText.MAX_CAUSES)
    }

    @Test
    fun longExceptionMessagesAreCut() {
        val message = CrashText.describe(IllegalStateException("w ".repeat(400)))
        assertTrue(message.length <= "java.lang.IllegalStateException: ".length + CrashText.MAX_MESSAGE + 1)
    }

    @Test
    fun theDebugLogFileSaysWhatItHoldsAndNeverHolds() {
        val d = diagnostics()
        d.pipeline.log(LogLevel.Info, "lan scan: probed=12 verified=1", null)
        val file = d.debugLogFile()
        assertEquals(File(File(root, Diagnostics.SHARE_DIR), Diagnostics.LOG_FILE), file)
        val text = file.readText()
        assertTrue(text.startsWith("Aristotle debug log\n"))
        assertTrue(text.contains(Diagnostics.LOG_NOTE))
        assertTrue(text.contains("lan scan: probed=12 verified=1"))
        // One file at a time in the share folder.
        d.record("main", IllegalStateException())
        d.loadOffer()
        d.crashReportFile()
        assertEquals(listOf(Diagnostics.CRASH_FILE), File(root, Diagnostics.SHARE_DIR).list()!!.toList())
    }

    @Test
    fun unpairClearsTheLogTheCrashAndTheShares() {
        val d = diagnostics()
        d.pipeline.log(LogLevel.Info, "old pairing event", null)
        d.debugLogFile()
        d.record("main", IllegalStateException())
        d.loadOffer()
        d.clear()
        assertFalse(d.crashOffered.value)
        assertEquals(emptyList<String>(), d.pipeline.lines())
        assertEquals(emptyList<String>(), File(root, "crash").list()!!.toList())
        assertEquals(emptyList<String>(), File(root, Diagnostics.SHARE_DIR).list()!!.toList())
    }

    @Test
    fun oldShareFilesAreSweptAtLaunch() {
        val share = File(root, Diagnostics.SHARE_DIR).apply { mkdirs() }
        val old = File(share, "old.txt").apply { writeText("x"); setLastModified(0L) }
        Diagnostics(File(root, "log"), File(root, "crash"), share, info, clock = { Diagnostics.SHARE_MAX_AGE_MILLIS + 10_000L }).loadOffer()
        assertFalse(old.exists())
    }
}
