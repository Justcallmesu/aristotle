package io.github.justcallmesu.aristotle.data

import java.io.File
import java.io.FileOutputStream

/**
 * The debug log on disk (Q80b, TL2): a ring of [files] text files in [dir], `app.log` the newest. When the next line
 * would take `app.log` past [maxFileBytes] it becomes `app.1.log` (the oldest file is deleted), so the log never holds
 * more than [files] × [maxFileBytes]. Lines arrive already redacted ([LogRedactor]). Not thread-safe: [AppLog]'s one
 * writer thread owns it, and the crash handler takes the same lock.
 */
class RingLog(
    private val dir: File,
    private val maxFileBytes: Long = MAX_FILE_BYTES,
    private val files: Int = FILES,
) {
    init {
        require(files >= 1 && maxFileBytes > 0)
    }

    private fun file(index: Int): File = File(dir, if (index == 0) "app.log" else "app.$index.log")

    /** Appends [lines] (one line each, no newlines inside), rotating as it goes. */
    fun append(lines: List<String>) {
        if (lines.isEmpty()) return
        dir.mkdirs()
        var size = file(0).length()
        var out = FileOutputStream(file(0), true)
        try {
            for (line in lines) {
                val bytes = (line.take(LogRedactor.MAX_LINE + 1) + "\n").toByteArray(Charsets.UTF_8)
                if (size > 0 && size + bytes.size > maxFileBytes) {
                    out.close()
                    rotate()
                    size = 0
                    out = FileOutputStream(file(0), true)
                }
                out.write(bytes)
                size += bytes.size
            }
        } finally {
            out.close()
        }
    }

    private fun rotate() {
        file(files - 1).delete()
        for (index in files - 2 downTo 0) {
            val from = file(index)
            if (from.exists()) from.renameTo(file(index + 1))
        }
    }

    /** Every stored line, oldest first. */
    fun lines(): List<String> = (files - 1 downTo 0).map(::file).filter { it.isFile }.flatMap { it.readLines(Charsets.UTF_8) }

    /** The newest [count] lines, oldest first. */
    fun tail(count: Int): List<String> = lines().takeLast(count)

    /** What the ring holds on disk now, in bytes. */
    fun size(): Long = (0 until files).sumOf { file(it).length() }

    /** Deletes every file of the ring (Unpair). */
    fun clear() {
        (0 until files).forEach { file(it).delete() }
    }

    companion object {
        const val MAX_FILE_BYTES = 256L * 1024
        const val FILES = 2
    }
}
