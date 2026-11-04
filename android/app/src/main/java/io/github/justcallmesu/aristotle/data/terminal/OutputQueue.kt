package io.github.justcallmesu.aristotle.data.terminal

/**
 * The bounded hand-off between OkHttp's reading thread and the emulator (protocol §14 phone obligations; T19 red-team
 * M5). [offer] never blocks and never grows past [capacityBytes]: when the emulator falls behind (`yes`,
 * `cat /dev/urandom`), the oldest frames are dropped and counted, and the next [drain] reports how many bytes were
 * skipped so the screen can say so. Thread-safe; the bytes are never logged.
 */
class OutputQueue(private val capacityBytes: Int = DEFAULT_CAPACITY) {
    private val frames = ArrayDeque<ByteArray>()
    private var queuedBytes = 0
    private var skippedBytes = 0L

    init {
        require(capacityBytes > 0)
    }

    /** What [drain] hands over: [skippedBytes] were dropped before [frames] (0 when nothing was). */
    class Batch(val skippedBytes: Long, val frames: List<ByteArray>) {
        val isEmpty: Boolean get() = skippedBytes == 0L && frames.isEmpty()
    }

    /** Called on the reading thread with each binary frame. A frame larger than the whole queue keeps only its tail. */
    @Synchronized
    fun offer(frame: ByteArray) {
        if (frame.isEmpty()) return
        val kept = if (frame.size > capacityBytes) {
            skippedBytes += frame.size - capacityBytes
            frame.copyOfRange(frame.size - capacityBytes, frame.size)
        } else {
            frame
        }
        while (queuedBytes + kept.size > capacityBytes) {
            val oldest = frames.removeFirst()
            queuedBytes -= oldest.size
            skippedBytes += oldest.size
        }
        frames.addLast(kept)
        queuedBytes += kept.size
    }

    /** Takes whole frames up to about [maxBytes] (at least one frame when any is queued), with the skipped count. */
    @Synchronized
    fun drain(maxBytes: Int): Batch {
        val out = mutableListOf<ByteArray>()
        var taken = 0
        while (frames.isNotEmpty() && (out.isEmpty() || taken + frames.first().size <= maxBytes)) {
            val frame = frames.removeFirst()
            queuedBytes -= frame.size
            taken += frame.size
            out += frame
        }
        val skipped = skippedBytes
        skippedBytes = 0
        return Batch(skipped, out)
    }

    @get:Synchronized
    val size: Int get() = queuedBytes

    @Synchronized
    fun clear() {
        frames.clear()
        queuedBytes = 0
        skippedBytes = 0
    }

    companion object {
        /** 512 KiB: many screens of output, small next to the phone's heap. */
        const val DEFAULT_CAPACITY = 512 * 1024
    }
}
