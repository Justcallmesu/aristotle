package io.github.justcallmesu.aristotle.data.chat

/**
 * Where a picked image keeps metadata that can locate the user, and how a JPEG loses it without re-encoding (T16).
 * Pure: works on the bytes only.
 *
 * Location lives in the Exif GPS IFD (TIFF tag 0x8825) of a JPEG's APP1 `Exif` segment, a PNG's `eXIf` chunk or a
 * WebP's `EXIF` chunk, and may be repeated in XMP (a JPEG APP1 segment, a PNG `iTXt` chunk named `XML:com.adobe.xmp`,
 * a WebP `XMP ` chunk).
 */
object ImageMetadata {
    /** [location]: an Exif GPS IFD with at least one entry. [xmp]: an XMP packet (may name the location too). */
    data class Found(val location: Boolean, val xmp: Boolean) {
        val any: Boolean get() = location || xmp
    }

    fun scan(bytes: ByteArray, kind: ImageKind): Found = when (kind) {
        ImageKind.Jpeg -> jpegSegments(bytes).fold(Found(false, false)) { found, s ->
            when {
                s.isExif(bytes) -> found.copy(location = found.location || gpsEntries(bytes, s.data + EXIF_HEADER.size, s.end) > 0)
                s.isXmp(bytes) -> found.copy(xmp = true)
                else -> found
            }
        }
        ImageKind.Png -> pngChunks(bytes).fold(Found(false, false)) { found, c ->
            when (c.type) {
                "eXIf" -> found.copy(location = found.location || gpsEntries(bytes, c.data, c.end) > 0)
                "iTXt" -> if (startsWith(bytes, c.data, PNG_XMP_KEY)) found.copy(xmp = true) else found
                else -> found
            }
        }
        ImageKind.Webp -> webpChunks(bytes).fold(Found(false, false)) { found, c ->
            when (c.type) {
                "EXIF" -> {
                    val tiff = if (startsWith(bytes, c.data, EXIF_HEADER)) c.data + EXIF_HEADER.size else c.data
                    found.copy(location = found.location || gpsEntries(bytes, tiff, c.end) > 0)
                }
                "XMP " -> found.copy(xmp = true)
                else -> found
            }
        }
        else -> Found(false, false)
    }

    /**
     * A copy of [jpeg] whose Exif GPS IFDs are blanked (every entry and the data it points to zeroed, entry count 0)
     * and whose XMP segments are removed. Pixels, orientation and the rest of Exif stay as they were. Bytes that
     * are not a JPEG come back unchanged.
     */
    fun stripLocation(jpeg: ByteArray): ByteArray {
        val segments = jpegSegments(jpeg)
        if (segments.isEmpty()) return jpeg
        val out = jpeg.copyOf()
        segments.filter { it.isExif(jpeg) }.forEach { blankGps(out, it.data + EXIF_HEADER.size, it.end) }
        val xmp = segments.filter { it.isXmp(jpeg) }
        if (xmp.isEmpty()) return out
        val kept = java.io.ByteArrayOutputStream(out.size)
        var from = 0
        xmp.forEach { s ->
            kept.write(out, from, s.start - from)
            from = s.end
        }
        kept.write(out, from, out.size - from)
        return kept.toByteArray()
    }

    // ── JPEG ──────────────────────────────────────────────────────────────────────────────────────

    /** A marker segment: [start] at its 0xFF, [data] after the length field, [end] exclusive. */
    private class Segment(val marker: Int, val start: Int, val data: Int, val end: Int) {
        fun isExif(bytes: ByteArray) = marker == APP1 && startsWith(bytes, data, EXIF_HEADER)
        fun isXmp(bytes: ByteArray) = marker == APP1 && startsWith(bytes, data, XMP_HEADER)
    }

    /** The segments before the image data (start of scan), or none when [bytes] is not a well-formed JPEG head. */
    private fun jpegSegments(bytes: ByteArray): List<Segment> {
        if (bytes.size < 4 || u8(bytes, 0) != 0xFF || u8(bytes, 1) != 0xD8) return emptyList()
        val segments = mutableListOf<Segment>()
        var at = 2
        while (at + 4 <= bytes.size && u8(bytes, at) == 0xFF) {
            val marker = u8(bytes, at + 1)
            if (marker == SOS || marker == EOI) break
            if (marker == 0xFF) {
                at++ // fill byte
                continue
            }
            val length = u16be(bytes, at + 2)
            val end = at + 2 + length
            if (length < 2 || end > bytes.size) break
            segments += Segment(marker, at, at + 4, end)
            at = end
        }
        return segments
    }

    // ── PNG and WebP ──────────────────────────────────────────────────────────────────────────────

    private class Chunk(val type: String, val data: Int, val end: Int)

    private fun pngChunks(bytes: ByteArray): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        var at = 8
        while (at + 12 <= bytes.size) {
            val length = u32be(bytes, at)
            val data = at + 8
            if (length < 0 || data + length + 4 > bytes.size) break
            val type = String(bytes, at + 4, 4, Charsets.ISO_8859_1)
            chunks += Chunk(type, data, data + length)
            if (type == "IEND") break
            at = data + length + 4
        }
        return chunks
    }

    private fun webpChunks(bytes: ByteArray): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        var at = 12
        while (at + 8 <= bytes.size) {
            val length = u32le(bytes, at + 4)
            val data = at + 8
            if (length < 0 || data + length > bytes.size) break
            chunks += Chunk(String(bytes, at, 4, Charsets.ISO_8859_1), data, data + length)
            at = data + length + (length and 1)
        }
        return chunks
    }

    // ── TIFF (Exif) ───────────────────────────────────────────────────────────────────────────────

    /** The TIFF structure at [start] (exclusive [end]); offsets inside it count from [start]. */
    private class Tiff(val bytes: ByteArray, val start: Int, val end: Int) {
        val little = end - start >= 8 && u8(bytes, start) == 'I'.code && u8(bytes, start + 1) == 'I'.code
        val valid = end - start >= 8 && (little || (u8(bytes, start) == 'M'.code && u8(bytes, start + 1) == 'M'.code)) && u16(2) == TIFF_MAGIC

        fun inside(offset: Long, length: Long) = offset >= 0 && length >= 0 && start + offset + length <= end

        fun u16(offset: Int): Int = if (little) {
            u8(bytes, start + offset) or (u8(bytes, start + offset + 1) shl 8)
        } else {
            u16be(bytes, start + offset)
        }

        fun u32(offset: Int): Long = if (little) {
            (u16(offset).toLong()) or (u16(offset + 2).toLong() shl 16)
        } else {
            (u16(offset).toLong() shl 16) or u16(offset + 2).toLong()
        }

        /** The GPS IFD offset named by IFD0, or null. */
        fun gpsIfd(): Int? {
            if (!valid) return null
            val ifd0 = u32(4)
            if (!inside(ifd0, 2)) return null
            val count = u16(ifd0.toInt())
            if (!inside(ifd0 + 2, count * 12L)) return null
            for (i in 0 until count) {
                val entry = ifd0.toInt() + 2 + i * 12
                if (u16(entry) == GPS_TAG) return u32(entry + 8).takeIf { inside(it, 2) }?.toInt()
            }
            return null
        }
    }

    /** How many entries the GPS IFD of the TIFF at [start] lists (0 when there is none). */
    private fun gpsEntries(bytes: ByteArray, start: Int, end: Int): Int {
        val tiff = Tiff(bytes, start, end)
        val gps = tiff.gpsIfd() ?: return 0
        return tiff.u16(gps)
    }

    /** Zeroes every GPS entry of the TIFF at [start] in [out] and what it points to; the IFD then lists none. */
    private fun blankGps(out: ByteArray, start: Int, end: Int) {
        val tiff = Tiff(out, start, end)
        val gps = tiff.gpsIfd() ?: return
        val count = tiff.u16(gps)
        val entries = (0 until count).map { gps + 2 + it * 12 }.filter { tiff.inside(it.toLong(), 12) }
        entries.forEach { entry ->
            val size = (TYPE_SIZES[tiff.u16(entry + 2)] ?: 0).toLong() * tiff.u32(entry + 4)
            val offset = tiff.u32(entry + 8)
            if (size > 4 && tiff.inside(offset, size)) out.fill(0, start + offset.toInt(), start + offset.toInt() + size.toInt())
        }
        entries.forEach { out.fill(0, start + it, start + it + 12) }
        out.fill(0, start + gps, start + gps + 2)
    }

    private const val APP1 = 0xE1
    private const val SOS = 0xDA
    private const val EOI = 0xD9
    private const val GPS_TAG = 0x8825
    private const val TIFF_MAGIC = 42
    private val EXIF_HEADER = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1)
    private val XMP_HEADER = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1)
    private val PNG_XMP_KEY = "XML:com.adobe.xmp\u0000".toByteArray(Charsets.ISO_8859_1)

    /** Bytes per value of each TIFF field type (TIFF 6.0 §2, plus IFD = 13). */
    private val TYPE_SIZES = mapOf(1 to 1, 2 to 1, 3 to 2, 4 to 4, 5 to 8, 6 to 1, 7 to 1, 8 to 2, 9 to 4, 10 to 8, 11 to 4, 12 to 8, 13 to 4)

    private fun u8(bytes: ByteArray, at: Int): Int = bytes[at].toInt() and 0xFF

    private fun u16be(bytes: ByteArray, at: Int): Int = (u8(bytes, at) shl 8) or u8(bytes, at + 1)

    private fun u32be(bytes: ByteArray, at: Int): Int = (u16be(bytes, at) shl 16) or u16be(bytes, at + 2)

    private fun u32le(bytes: ByteArray, at: Int): Int =
        u8(bytes, at) or (u8(bytes, at + 1) shl 8) or (u8(bytes, at + 2) shl 16) or (u8(bytes, at + 3) shl 24)

    private fun startsWith(bytes: ByteArray, at: Int, prefix: ByteArray): Boolean =
        at >= 0 && at + prefix.size <= bytes.size && prefix.indices.all { bytes[at + it] == prefix[it] }
}
