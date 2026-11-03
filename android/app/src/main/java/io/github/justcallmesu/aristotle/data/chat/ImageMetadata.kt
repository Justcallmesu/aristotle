package io.github.justcallmesu.aristotle.data.chat

/**
 * Where a picked image keeps metadata that can locate the user, and how a JPEG loses it without re-encoding (T16).
 * Pure: works on the bytes only.
 *
 * Location lives in the Exif GPS IFD (TIFF tag 0x8825) of a JPEG's APP1 `Exif` segment, a PNG's `eXIf` chunk or a
 * WebP's `EXIF` chunk, and may be repeated in XMP (a JPEG APP1 segment, plain or extended, a PNG `iTXt` chunk named
 * `XML:com.adobe.xmp`, a WebP `XMP ` chunk, a GIF `XMP Data` application extension). A JPEG may also hold more JPEG
 * images after the first one's EOI (MPF / Ultra-HDR secondary images), each with its own Exif and XMP.
 */
object ImageMetadata {
    /** [location]: an Exif GPS IFD with at least one entry. [xmp]: an XMP packet (may name the location too). */
    data class Found(val location: Boolean, val xmp: Boolean) {
        val any: Boolean get() = location || xmp
    }

    fun scan(bytes: ByteArray, kind: ImageKind): Found = when (kind) {
        ImageKind.Jpeg -> jpegImages(bytes).flatMap { it.segments }.fold(Found(false, false)) { found, s ->
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
        ImageKind.Gif -> Found(location = false, xmp = gif(bytes)?.xmp?.isNotEmpty() ?: false)
        else -> Found(false, false)
    }

    /**
     * [jpeg] with its location and XMP made unreadable everywhere, or null when it cannot be done safely (a structure
     * that does not parse, an Exif block whose GPS IFD cannot be followed, bytes after the last image that are not
     * another JPEG): the caller then re-encodes the pixels as a plain JPEG, which carries no metadata.
     *
     * Every Exif GPS IFD is blanked as in [stripLocation], in the first image and in every further JPEG after its EOI
     * (MPF / Ultra-HDR secondary images). XMP segments, plain and extended, are removed; when further images follow
     * (their offsets are kept by an MPF segment) the segment's payload is zeroed in place instead so no offset moves.
     */
    fun sanitizeJpeg(jpeg: ByteArray): ByteArray? {
        val images = mutableListOf<Jpeg>()
        var at = 0
        while (at < jpeg.size) {
            if (images.isNotEmpty() && (at until jpeg.size).all { jpeg[it].toInt() == 0 }) break // padding
            val image = parseJpeg(jpeg, at)
            if (image.end < 0) return null
            images += image
            at = image.end
        }
        if (images.isEmpty()) return null
        val segments = images.flatMap { it.segments }
        val out = jpeg.copyOf()
        for (s in segments.filter { it.isExif(jpeg) }) {
            if (!Tiff(jpeg, s.data + EXIF_HEADER.size, s.end).gpsSafe()) return null
            blankGps(out, s.data + EXIF_HEADER.size, s.end)
        }
        val xmp = segments.filter { it.isXmp(jpeg) }
        if (xmp.isEmpty()) return out
        if (images.size > 1 || at < jpeg.size) {
            xmp.forEach { out.fill(0, it.data, it.end) }
            return out
        }
        return without(out, xmp.map { it.start to it.end })
    }

    /**
     * [gif] without its XMP application extensions (animation and everything else stay), or null when the blocks do
     * not parse to a trailer. Bytes after the trailer are dropped.
     */
    fun stripGif(gif: ByteArray): ByteArray? {
        val parsed = gif(gif) ?: return null
        return without(gif.copyOf(parsed.end), parsed.xmp)
    }

    private fun without(bytes: ByteArray, ranges: List<Pair<Int, Int>>): ByteArray {
        val kept = java.io.ByteArrayOutputStream(bytes.size)
        var from = 0
        ranges.sortedBy { it.first }.forEach { (start, end) ->
            kept.write(bytes, from, start - from)
            from = end
        }
        kept.write(bytes, from, bytes.size - from)
        return kept.toByteArray()
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
        return if (xmp.isEmpty()) out else without(out, xmp.map { it.start to it.end })
    }

    // ── JPEG ──────────────────────────────────────────────────────────────────────────────────────

    /** A marker segment: [start] at its 0xFF, [data] after the length field, [end] exclusive. */
    private class Segment(val marker: Int, val start: Int, val data: Int, val end: Int) {
        fun isExif(bytes: ByteArray) = marker == APP1 && startsWith(bytes, data, EXIF_HEADER)
        fun isXmp(bytes: ByteArray) = marker == APP1 && (startsWith(bytes, data, XMP_HEADER) || startsWith(bytes, data, XMP_EXTENSION_HEADER))
    }

    /** The segments of the first image, as far as they parse (lenient: [stripLocation] blanks what it can find). */
    private fun jpegSegments(bytes: ByteArray): List<Segment> = parseJpeg(bytes, 0).segments

    /** Every JPEG image that parses, one after another (the first, then MPF / Ultra-HDR secondary images). */
    private fun jpegImages(bytes: ByteArray): List<Jpeg> {
        val images = mutableListOf<Jpeg>()
        var at = 0
        while (at + 4 <= bytes.size && images.size < MAX_IMAGES) {
            val image = parseJpeg(bytes, at)
            if (image.segments.isEmpty() && image.end < 0) break
            images += image
            if (image.end < 0) break
            at = image.end
        }
        return images
    }

    /** One JPEG image: its marker segments (all of them, past every scan) and [end] exclusive after its EOI, -1 when it does not parse. */
    private class Jpeg(val segments: List<Segment>, val end: Int)

    private fun parseJpeg(bytes: ByteArray, from: Int): Jpeg {
        val segments = mutableListOf<Segment>()
        if (from + 4 > bytes.size || u8(bytes, from) != 0xFF || u8(bytes, from + 1) != 0xD8) return Jpeg(segments, -1)
        var at = from + 2
        while (at + 2 <= bytes.size && u8(bytes, at) == 0xFF) {
            val marker = u8(bytes, at + 1)
            when {
                marker == 0xFF -> at++ // fill byte
                marker == EOI -> return Jpeg(segments, at + 2)
                marker == 0 || marker == 0xD8 -> return Jpeg(segments, -1)
                marker in 0xD0..0xD7 || marker == 0x01 -> at += 2 // no length
                else -> {
                    if (at + 4 > bytes.size) return Jpeg(segments, -1)
                    val length = u16be(bytes, at + 2)
                    val end = at + 2 + length
                    if (length < 2 || end > bytes.size) return Jpeg(segments, -1)
                    segments += Segment(marker, at, at + 4, end)
                    at = if (marker == SOS) skipScan(bytes, end) else end
                }
            }
        }
        return Jpeg(segments, -1)
    }

    /** The next marker after entropy-coded data starting at [from] (stuffed bytes and restart markers belong to the data). */
    private fun skipScan(bytes: ByteArray, from: Int): Int {
        var i = from
        while (i + 1 < bytes.size) {
            if (u8(bytes, i) == 0xFF) {
                val next = u8(bytes, i + 1)
                when {
                    next == 0 || next in 0xD0..0xD7 -> i += 2
                    next == 0xFF -> i++
                    else -> return i
                }
            } else {
                i++
            }
        }
        return bytes.size
    }

    // ── GIF ───────────────────────────────────────────────────────────────────────────────────────

    /** [xmp]: byte ranges of the `XMP Data` application extensions; [end] exclusive after the trailer. */
    private class Gif(val xmp: List<Pair<Int, Int>>, val end: Int)

    private fun gif(bytes: ByteArray): Gif? {
        if (bytes.size < 14 || !(startsWith(bytes, 0, "GIF87a".toByteArray()) || startsWith(bytes, 0, "GIF89a".toByteArray()))) return null
        var at = 13 + colorTable(u8(bytes, 10))
        val xmp = mutableListOf<Pair<Int, Int>>()
        while (at < bytes.size) {
            when (u8(bytes, at)) {
                0x3B -> return Gif(xmp, at + 1)
                0x21 -> {
                    val start = at
                    if (at + 2 > bytes.size) return null
                    val label = u8(bytes, at + 1)
                    val first = at + 2
                    at = subBlocks(bytes, first) ?: return null
                    if (label == 0xFF && first < bytes.size && u8(bytes, first) == 11 && startsWith(bytes, first + 1, GIF_XMP_ID)) xmp += start to at
                }
                0x2C -> {
                    if (at + 11 > bytes.size) return null
                    at += 10 + colorTable(u8(bytes, at + 9)) + 1 // descriptor, local table, LZW code size
                    at = subBlocks(bytes, at) ?: return null
                }
                else -> return null
            }
        }
        return null
    }

    private fun colorTable(packed: Int): Int = if (packed and 0x80 != 0) 3 * (1 shl ((packed and 7) + 1)) else 0

    /** The offset after the data sub-blocks starting at [from] (up to the zero-length block), null when they run off the end. */
    private fun subBlocks(bytes: ByteArray, from: Int): Int? {
        var at = from
        while (at < bytes.size) {
            val n = u8(bytes, at)
            at += 1 + n
            if (n == 0) return at
        }
        return null
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

        /** True when IFD0 and, if it names one, the GPS IFD with all its out-of-line data lie inside the block, so [blankGps] reaches all of it. */
        fun gpsSafe(): Boolean {
            if (!valid) return false
            val ifd0 = u32(4)
            if (!inside(ifd0, 2)) return false
            val count = u16(ifd0.toInt())
            if (!inside(ifd0 + 2, count * 12L)) return false
            for (i in 0 until count) {
                val entry = ifd0.toInt() + 2 + i * 12
                if (u16(entry) != GPS_TAG) continue
                val gps = u32(entry + 8)
                if (!inside(gps, 2)) return false
                val n = u16(gps.toInt())
                if (!inside(gps + 2, n * 12L)) return false
                for (j in 0 until n) {
                    val e = gps.toInt() + 2 + j * 12
                    val size = (TYPE_SIZES[u16(e + 2)] ?: 0).toLong() * u32(e + 4)
                    if (size > 4 && !inside(u32(e + 8), size)) return false
                }
            }
            return true
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
    private val XMP_EXTENSION_HEADER = "http://ns.adobe.com/xmp/extension/\u0000".toByteArray(Charsets.ISO_8859_1)
    private val GIF_XMP_ID = "XMP DataXMP".toByteArray(Charsets.ISO_8859_1)
    private const val MAX_IMAGES = 8
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
