package io.github.justcallmesu.aristotle.data.terminal

import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bounded output queue (protocol §14 phone obligations, T19 red-team M5): drop-oldest, counted, never unbounded. */
class OutputQueueTest {
    private fun frame(size: Int, value: Int) = ByteArray(size) { value.toByte() }

    @Test
    fun framesComeOutInOrderWithNothingSkipped() {
        val queue = OutputQueue(capacityBytes = 100)
        queue.offer(frame(10, 1))
        queue.offer(frame(20, 2))
        val batch = queue.drain(1_000)
        assertEquals(0L, batch.skippedBytes)
        assertEquals(listOf(10, 20), batch.frames.map { it.size })
        assertTrue(queue.drain(1_000).isEmpty)
    }

    @Test
    fun overflowDropsTheOldestAndCountsIt() {
        val queue = OutputQueue(capacityBytes = 100)
        queue.offer(frame(40, 1))
        queue.offer(frame(40, 2))
        queue.offer(frame(40, 3)) // 120 > 100: frame 1 goes
        assertEquals(80, queue.size)
        val batch = queue.drain(1_000)
        assertEquals(40L, batch.skippedBytes)
        assertEquals(listOf(2, 3), batch.frames.map { it[0].toInt() })
        // The count is reported once.
        queue.offer(frame(1, 4))
        assertEquals(0L, queue.drain(1_000).skippedBytes)
    }

    @Test
    fun aFrameLargerThanTheQueueKeepsItsTail() {
        val queue = OutputQueue(capacityBytes = 10)
        queue.offer(frame(5, 1))
        queue.offer(ByteArray(25) { it.toByte() })
        val batch = queue.drain(1_000)
        assertEquals(15L + 5L, batch.skippedBytes)
        assertArrayEquals(ByteArray(10) { (it + 15).toByte() }, batch.frames.single())
    }

    @Test
    fun drainTakesWholeFramesUpToTheStepButAlwaysOne() {
        val queue = OutputQueue(capacityBytes = 1_000)
        repeat(5) { queue.offer(frame(30, it)) }
        assertEquals(2, queue.drain(64).frames.size)
        assertEquals(1, queue.drain(10).frames.size) // one frame even when larger than the step
        assertEquals(2, queue.drain(1_000).frames.size)
    }

    @Test
    fun aFastProducerNeverGrowsItPastItsCapacity() {
        val capacity = 64 * 1024
        val queue = OutputQueue(capacity)
        val produced = AtomicInteger()
        val producer = thread {
            repeat(20_000) {
                queue.offer(frame(4_096, it))
                produced.addAndGet(4_096)
            }
        }
        var taken = 0L
        var skipped = 0L
        while (producer.isAlive || queue.size > 0) {
            assertTrue(queue.size <= capacity)
            val batch = queue.drain(8 * 1024)
            taken += batch.frames.sumOf { it.size }
            skipped += batch.skippedBytes
        }
        producer.join()
        val rest = queue.drain(Int.MAX_VALUE)
        taken += rest.frames.sumOf { it.size }
        skipped += rest.skippedBytes
        assertEquals(produced.get().toLong(), taken + skipped)
    }
}
