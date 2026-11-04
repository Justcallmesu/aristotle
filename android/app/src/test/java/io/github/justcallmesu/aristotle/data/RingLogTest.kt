package io.github.justcallmesu.aristotle.data

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TL2 (Q80b): the debug log's ring files and the queue in front of them. */
class RingLogTest {
    private val dir: File = Files.createTempDirectory("ringlog").toFile()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun rotatesIntoTheSecondFileAndNeverPassesTheBound() {
        val ring = RingLog(dir, maxFileBytes = 1000, files = 2)
        repeat(500) { i -> ring.append(listOf("line %04d %s".format(i, "x".repeat(40)))) }
        assertTrue("ring holds ${ring.size()} bytes", ring.size() <= 2000)
        assertTrue(File(dir, "app.log").isFile)
        assertTrue(File(dir, "app.1.log").isFile)
        assertFalse(File(dir, "app.2.log").exists())
        val lines = ring.lines()
        // Oldest first, contiguous, ending with the newest line.
        assertTrue(lines.last().startsWith("line 0499"))
        val numbers = lines.map { it.substring(5, 9).toInt() }
        assertEquals((numbers.first()..499).toList(), numbers)
        assertTrue(numbers.first() > 0)
    }

    @Test
    fun theDefaultBoundIsHalfAMegabyte() {
        assertEquals(512L * 1024, RingLog.MAX_FILE_BYTES * RingLog.FILES)
        val ring = RingLog(dir)
        val line = "e".repeat(900)
        repeat(1500) { ring.append(listOf(line)) }
        assertTrue(ring.size() <= 512L * 1024)
        assertTrue(ring.size() > 256L * 1024)
    }

    @Test
    fun tailAndClear() {
        val ring = RingLog(dir, maxFileBytes = 100, files = 2)
        ring.append((1..30).map { "l$it" })
        assertEquals(listOf("l29", "l30"), ring.tail(2))
        ring.clear()
        assertEquals(emptyList<String>(), ring.lines())
        assertEquals(0L, ring.size())
    }

    @Test
    fun thePipelineRedactsAtTheDoorAndKeepsOnlyTheErrorType() {
        val ring = RingLog(dir)
        val logcat = mutableListOf<String>()
        val pipeline = LogPipeline(ring, clock = { 0L }, logcat = { _, line -> logcat += line })
        pipeline.log(LogLevel.Info, "dial https://h:1/v1/relay?token=abc", null)
        pipeline.log(LogLevel.Warn, "link: uncaught", IllegalStateException("token=leaked secret text"))
        val lines = pipeline.lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0], lines[0].endsWith(" I dial https://h:1/v1/relay?[redacted]"))
        assertTrue(lines[1], lines[1].endsWith(" W link: uncaught error=java.lang.IllegalStateException"))
        assertFalse(lines.joinToString().contains("leaked"))
        assertEquals(listOf("dial https://h:1/v1/relay?[redacted]", "link: uncaught error=java.lang.IllegalStateException"), logcat)
    }

    @Test
    fun clearDropsTheQueueAndTheFiles() {
        val ring = RingLog(dir)
        val pipeline = LogPipeline(ring)
        pipeline.log(LogLevel.Info, "before", null)
        pipeline.flush()
        pipeline.log(LogLevel.Info, "queued", null)
        pipeline.clear()
        pipeline.log(LogLevel.Info, "after", null)
        assertEquals(1, pipeline.lines().size)
        assertTrue(pipeline.lines().single().endsWith("after"))
    }

    @Test
    fun aFullQueueDropsTheOldestLine() {
        val pipeline = LogPipeline(RingLog(dir), queueSize = 3)
        (1..5).forEach { pipeline.log(LogLevel.Debug, "e$it", null) }
        assertEquals(listOf("e3", "e4", "e5"), pipeline.lines().map { it.substringAfterLast(' ') })
    }

    @Test
    fun theWriterThreadWritesWithoutAFlush() {
        val pipeline = LogPipeline(RingLog(dir))
        pipeline.start()
        pipeline.log(LogLevel.Info, "async", null)
        val deadline = System.currentTimeMillis() + 5000
        while (!File(dir, "app.log").let { it.isFile && it.readText().contains("async") } && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(File(dir, "app.log").readText().contains("async"))
    }

    @Test
    fun appLogGoesNowhereUntilInstalledThenToTheSink() {
        val seen = mutableListOf<String>()
        AppLog.install(null)
        AppLog.i("dropped")
        AppLog.install { level, event, error -> seen += "${level.letter} $event ${error?.javaClass?.simpleName}" }
        try {
            AppLog.d("a")
            AppLog.w("b", IllegalArgumentException())
            AppLog.e("c")
        } finally {
            AppLog.install(null)
        }
        assertEquals(listOf("D a null", "W b IllegalArgumentException", "E c null"), seen)
    }
}
