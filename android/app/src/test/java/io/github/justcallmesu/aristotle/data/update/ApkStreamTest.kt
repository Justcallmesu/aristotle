package io.github.justcallmesu.aristotle.data.update

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** UP2: the download is hashed as it streams and never grows past the announced size. */
class ApkStreamTest {
    private val bytes = ByteArray(200_000) { (it % 251).toByte() }

    @Test
    fun `copies every byte and returns its sha256`() {
        val out = ByteArrayOutputStream()
        val progress = mutableListOf<Long>()
        val sha = ApkStream.copy(ByteArrayInputStream(bytes), out, bytes.size.toLong(), progress::add)
        assertArrayEquals(bytes, out.toByteArray())
        assertEquals(hex(MessageDigest.getInstance("SHA-256").digest(bytes)), sha)
        assertEquals(bytes.size.toLong(), progress.last())
        assertEquals(progress.sorted(), progress)
    }

    @Test
    fun `more bytes than announced stop the copy`() {
        val read = CountingStream(ByteArrayInputStream(bytes + ByteArray(1_000_000)))
        val out = ByteArrayOutputStream()
        expect(DownloadProblem.TooLarge) { ApkStream.copy(read, out, bytes.size.toLong(), {}) }
        assertTrue("stopped near the limit, read ${read.count}", read.count <= bytes.size + ApkStream.BUFFER)
        assertTrue("never wrote past the limit", out.size() <= bytes.size)
    }

    @Test
    fun `fewer bytes than announced is a truncated download`() {
        expect(DownloadProblem.Truncated) { ApkStream.copy(ByteArrayInputStream(bytes), ByteArrayOutputStream(), bytes.size + 1L, {}) }
    }

    @Test
    fun `a write that fails is a storage problem, not the network's`() {
        val full = object : OutputStream() {
            override fun write(b: Int) = throw IOException("ENOSPC")
            override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("ENOSPC")
        }
        expect(DownloadProblem.Storage) { ApkStream.copy(ByteArrayInputStream(bytes), full, bytes.size.toLong(), {}) }
    }

    @Test
    fun `a cancel check runs between chunks`() {
        var checks = 0
        try {
            ApkStream.copy(ByteArrayInputStream(bytes), ByteArrayOutputStream(), bytes.size.toLong(), {}) { if (++checks == 2) throw IllegalStateException("cancelled") }
            fail("not cancelled")
        } catch (e: IllegalStateException) {
            assertEquals("cancelled", e.message)
        }
    }

    private fun expect(problem: DownloadProblem, block: () -> Unit) {
        try {
            block()
            fail("expected $problem")
        } catch (e: ApkStream.Refused) {
            assertEquals(problem, e.problem)
        }
    }

    private class CountingStream(private val inner: InputStream) : InputStream() {
        var count = 0L
        override fun read(): Int = inner.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) count += it }
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
