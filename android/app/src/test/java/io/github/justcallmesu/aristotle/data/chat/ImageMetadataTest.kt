package io.github.justcallmesu.aristotle.data.chat

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T16: location metadata found in JPEG, PNG and WebP, and blanked in a JPEG without touching anything else. */
class ImageMetadataTest {
    /** Latitude 6°12'34.56" as three rationals: a pattern that must not survive stripping. */
    private val latitude = intArrayOf(6, 1, 12, 1, 3456, 100)

    /**
     * A TIFF block: IFD0 = Orientation (6) + GPS pointer; GPS IFD = LatitudeRef "S" (inline) + Latitude (3 rationals,
     * out of line). [gps] false: no GPS pointer at all.
     */
    private fun tiff(order: ByteOrder, gps: Boolean = true): ByteArray {
        val b = ByteBuffer.allocate(128).order(order)
        b.put(if (order == ByteOrder.LITTLE_ENDIAN) "II".toByteArray() else "MM".toByteArray())
        b.putShort(42).putInt(8)
        // IFD0 at 8
        b.putShort(if (gps) 2 else 1)
        b.putShort(0x0112).putShort(3).putInt(1).putShort(6).putShort(0) // Orientation = 6
        if (gps) b.putShort(0x8825.toShort()).putShort(4).putInt(1).putInt(38) // GPS IFD at 38
        b.putInt(0)
        if (gps) {
            check(b.position() == 38)
            b.putShort(2)
            b.putShort(0x0001).putShort(2).putInt(2).put('S'.code.toByte()).put(0).putShort(0)
            b.putShort(0x0002).putShort(5).putInt(3).putInt(68) // rationals at 68
            b.putInt(0)
            check(b.position() == 68)
            latitude.forEach { b.putInt(it) }
        }
        return b.array().copyOf(b.position())
    }

    private fun segment(marker: Int, data: ByteArray) = byteArrayOf(0xFF.toByte(), marker.toByte(), ((data.size + 2) shr 8).toByte(), (data.size + 2).toByte()) + data

    private val exifHeader = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1)
    private val xmp = "http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta exif:GPSLatitude=\"6,12.5S\"/>".toByteArray(Charsets.ISO_8859_1)
    private val scan = byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 4, 1, 2) + ByteArray(32) { 0x55 } + byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    private fun jpeg(vararg segments: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        segments.forEach { out.write(it) }
        out.write(scan)
        return out.toByteArray()
    }

    private fun ByteArray.contains(pattern: ByteArray): Boolean = (0..size - pattern.size).any { i -> pattern.indices.all { this[i + it] == pattern[it] } }

    private fun latitudeBytes(order: ByteOrder) = ByteBuffer.allocate(24).order(order).apply { latitude.forEach { putInt(it) } }.array()

    @Test
    fun aJpegWithGpsIsFoundAndBlankedInBothByteOrders() {
        for (order in listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            val app0 = segment(0xE0, "JFIF\u0000\u0001\u0001".toByteArray(Charsets.ISO_8859_1))
            val original = jpeg(app0, segment(0xE1, exifHeader + tiff(order)))
            assertTrue(original.contains(latitudeBytes(order)))
            assertEquals(ImageMetadata.Found(location = true, xmp = false), ImageMetadata.scan(original, ImageKind.Jpeg))

            val stripped = ImageMetadata.stripLocation(original)
            assertEquals(original.size, stripped.size)
            assertEquals(ImageMetadata.Found(location = false, xmp = false), ImageMetadata.scan(stripped, ImageKind.Jpeg))
            assertFalse("latitude still there ($order)", stripped.contains(latitudeBytes(order)))
            // Everything but the GPS IFD is byte for byte the same: JFIF, IFD0 (orientation), the image data.
            val tiffStart = 2 + app0.size + 4 + exifHeader.size
            assertArrayEquals(original.copyOfRange(0, tiffStart + 38), stripped.copyOfRange(0, tiffStart + 38))
            assertArrayEquals(scan, stripped.copyOfRange(stripped.size - scan.size, stripped.size))
        }
    }

    @Test
    fun xmpSegmentsAreRemovedAndTheRestKept() {
        val original = jpeg(segment(0xE1, exifHeader + tiff(ByteOrder.BIG_ENDIAN, gps = false)), segment(0xE1, xmp))
        assertEquals(ImageMetadata.Found(location = false, xmp = true), ImageMetadata.scan(original, ImageKind.Jpeg))
        val stripped = ImageMetadata.stripLocation(original)
        assertEquals(original.size - (xmp.size + 4), stripped.size)
        assertEquals(ImageMetadata.Found(location = false, xmp = false), ImageMetadata.scan(stripped, ImageKind.Jpeg))
        assertTrue(stripped.contains(scan))
    }

    @Test
    fun aJpegWithoutMetadataIsUnchanged() {
        val plain = jpeg(segment(0xE1, exifHeader + tiff(ByteOrder.LITTLE_ENDIAN, gps = false)))
        assertArrayEquals(plain, ImageMetadata.stripLocation(plain))
        assertFalse(ImageMetadata.scan(plain, ImageKind.Jpeg).any)
    }

    @Test
    fun brokenOrForeignBytesNeverThrow() {
        val bad = listOf(
            ByteArray(0),
            byteArrayOf(0xFF.toByte(), 0xD8.toByte()),
            // APP1 claiming more bytes than there are.
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), 0x7F, 0x7F) + exifHeader,
            // GPS pointer past the end of the TIFF block.
            jpeg(segment(0xE1, exifHeader + tiff(ByteOrder.LITTLE_ENDIAN).copyOf(40))),
            "not an image at all".toByteArray(),
        )
        bad.forEach { bytes ->
            ImageKind.entries.forEach { ImageMetadata.scan(bytes, it) }
            ImageMetadata.stripLocation(bytes)
        }
        assertArrayEquals("x".toByteArray(), ImageMetadata.stripLocation("x".toByteArray()))
    }

    private fun pngChunk(type: String, data: ByteArray): ByteArray {
        val head = ByteBuffer.allocate(8).putInt(data.size).put(type.toByteArray(Charsets.ISO_8859_1)).array()
        val crc = CRC32().apply { update(type.toByteArray(Charsets.ISO_8859_1)); update(data) }.value
        return head + data + ByteBuffer.allocate(4).putInt(crc.toInt()).array()
    }

    private val pngSignature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    @Test
    fun aPngWithAnExifLocationOrXmpIsFound() {
        val ihdr = pngChunk("IHDR", ByteArray(13))
        val end = pngChunk("IEND", ByteArray(0))
        val withGps = pngSignature + ihdr + pngChunk("eXIf", tiff(ByteOrder.BIG_ENDIAN)) + end
        val withXmp = pngSignature + ihdr + pngChunk("iTXt", "XML:com.adobe.xmp\u0000\u0000\u0000\u0000\u0000<x/>".toByteArray(Charsets.ISO_8859_1)) + end
        val plain = pngSignature + ihdr + pngChunk("tEXt", "Software\u0000x".toByteArray(Charsets.ISO_8859_1)) + end
        assertTrue(ImageMetadata.scan(withGps, ImageKind.Png).location)
        assertTrue(ImageMetadata.scan(withXmp, ImageKind.Png).xmp)
        assertFalse(ImageMetadata.scan(plain, ImageKind.Png).any)
        assertEquals(ImagePlan.Reencode, AttachmentRules.plan(ImageKind.Png, withGps.size.toLong(), ImageMetadata.scan(withGps, ImageKind.Png).any))
        assertEquals(ImagePlan.Original, AttachmentRules.plan(ImageKind.Png, plain.size.toLong(), ImageMetadata.scan(plain, ImageKind.Png).any))
    }

    private fun webpChunk(type: String, data: ByteArray): ByteArray {
        val head = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).put(type.toByteArray(Charsets.ISO_8859_1)).putInt(data.size).array()
        return head + data + (if (data.size % 2 == 1) byteArrayOf(0) else ByteArray(0))
    }

    private fun webp(vararg chunks: ByteArray): ByteArray {
        val body = chunks.fold("WEBP".toByteArray()) { acc, c -> acc + c }
        return "RIFF".toByteArray() + ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(body.size).array() + body
    }

    @Test
    fun aWebpWithAnExifLocationOrXmpIsFound() {
        val vp8x = webpChunk("VP8X", ByteArray(10))
        assertTrue(ImageMetadata.scan(webp(vp8x, webpChunk("EXIF", tiff(ByteOrder.LITTLE_ENDIAN))), ImageKind.Webp).location)
        assertTrue(ImageMetadata.scan(webp(vp8x, webpChunk("EXIF", exifHeader + tiff(ByteOrder.LITTLE_ENDIAN))), ImageKind.Webp).location)
        assertTrue(ImageMetadata.scan(webp(vp8x, webpChunk("XMP ", "<x/>".toByteArray())), ImageKind.Webp).xmp)
        assertFalse(ImageMetadata.scan(webp(webpChunk("VP8 ", ByteArray(11))), ImageKind.Webp).any)
    }

    // ── T16b ──────────────────────────────────────────────────────────────────────────────────────

    private val xmpExtension = "http://ns.adobe.com/xmp/extension/\u0000GUIDGUIDGUIDGUIDGUIDGUIDGUIDGUID<x exif:GPSLatitude=\"6,12.5S\"/>".toByteArray(Charsets.ISO_8859_1)
    private val mpf = segment(0xE2, "MPF\u0000".toByteArray(Charsets.ISO_8859_1) + ByteArray(16))

    @Test
    fun extendedXmpIsFoundAndRemovedLikeThePlainPacket() {
        val original = jpeg(segment(0xE1, xmp), segment(0xE1, xmpExtension))
        assertTrue(ImageMetadata.scan(jpeg(segment(0xE1, xmpExtension)), ImageKind.Jpeg).xmp)
        val stripped = ImageMetadata.stripLocation(original)
        assertFalse(stripped.contains("GPSLatitude".toByteArray()))
        assertFalse(ImageMetadata.scan(stripped, ImageKind.Jpeg).any)
    }

    @Test
    fun gpsInASecondaryJpegAfterTheFirstImageIsFoundAndBlankedAndItsXmpZeroedInPlace() {
        val primary = jpeg(segment(0xE1, exifHeader + tiff(ByteOrder.LITTLE_ENDIAN)), mpf)
        val secondary = jpeg(segment(0xE1, exifHeader + tiff(ByteOrder.BIG_ENDIAN)), segment(0xE1, xmp), segment(0xE1, xmpExtension))
        val file = primary + secondary
        assertTrue(file.contains(latitudeBytes(ByteOrder.BIG_ENDIAN)))
        assertFalse(ImageMetadata.scan(jpeg(mpf), ImageKind.Jpeg).any)
        val cleaned = ImageMetadata.sanitizeJpeg(file)!!
        // Same length: the MPF offsets still point at the secondary image.
        assertEquals(file.size, cleaned.size)
        assertFalse(cleaned.contains(latitudeBytes(ByteOrder.BIG_ENDIAN)))
        assertFalse(cleaned.contains(latitudeBytes(ByteOrder.LITTLE_ENDIAN)))
        assertFalse(cleaned.contains("GPSLatitude".toByteArray()))
        assertEquals(ImageMetadata.Found(location = false, xmp = false), ImageMetadata.scan(cleaned, ImageKind.Jpeg))
        assertArrayEquals(scan, cleaned.copyOfRange(cleaned.size - scan.size, cleaned.size))
    }

    @Test
    fun aSingleImageLosesItsXmpSegmentsOutright() {
        val original = jpeg(segment(0xE1, exifHeader + tiff(ByteOrder.LITTLE_ENDIAN)), segment(0xE1, xmp), segment(0xE1, xmpExtension))
        val cleaned = ImageMetadata.sanitizeJpeg(original)!!
        assertEquals(original.size - segment(0xE1, xmp).size - segment(0xE1, xmpExtension).size, cleaned.size)
        assertFalse(cleaned.contains(latitudeBytes(ByteOrder.LITTLE_ENDIAN)))
        assertEquals(ImageMetadata.Found(false, false), ImageMetadata.scan(cleaned, ImageKind.Jpeg))
    }

    @Test
    fun whatCannotBeParsedSafelyReturnsNullSoTheCallerReencodes() {
        val good = jpeg(segment(0xE1, exifHeader + tiff(ByteOrder.LITTLE_ENDIAN)))
        // A vendor trailer after the EOI (not a JPEG).
        assertNull(ImageMetadata.sanitizeJpeg(good + "SAMSUNGTRAILER-with-a-location".toByteArray()))
        // A truncated image (no EOI).
        assertNull(ImageMetadata.sanitizeJpeg(good.copyOf(good.size - 2)))
        // A second image that does not parse.
        assertNull(ImageMetadata.sanitizeJpeg(good + byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), 0x7F, 0x00)))
        // An Exif block whose GPS IFD points outside it.
        val broken = tiff(ByteOrder.LITTLE_ENDIAN).also { it[8 + 2 + 12 + 8] = 100 }
        assertNull(ImageMetadata.sanitizeJpeg(jpeg(segment(0xE1, exifHeader + broken))))
        // Not a JPEG at all.
        assertNull(ImageMetadata.sanitizeJpeg("hello".toByteArray()))
        // Padding after the last EOI is fine.
        assertEquals(good.size + 4, ImageMetadata.sanitizeJpeg(good + ByteArray(4))!!.size)
    }

    private fun gifBlock(vararg parts: ByteArray) = parts.fold(ByteArray(0)) { a, b -> a + b }

    private fun subBlocks(data: ByteArray): ByteArray = (data.size.takeIf { it in 1..255 }?.let { byteArrayOf(it.toByte()) + data } ?: ByteArray(0)) + byteArrayOf(0)

    private fun gif(vararg extensions: ByteArray): ByteArray {
        val header = "GIF89a".toByteArray() + byteArrayOf(1, 0, 1, 0, 0x80.toByte(), 0, 0) + ByteArray(6) // 2-colour table
        val frame = byteArrayOf(0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2) + subBlocks(byteArrayOf(0x44, 0x01))
        return gifBlock(header, *extensions, frame, byteArrayOf(0x3B))
    }

    private val gifXmp = byteArrayOf(0x21, 0xFF.toByte(), 11) + "XMP DataXMP".toByteArray() + subBlocks("<x exif:GPSLatitude=\"1\"/>".toByteArray())
    private val gifLoop = byteArrayOf(0x21, 0xFF.toByte(), 11) + "NETSCAPE2.0".toByteArray() + subBlocks(byteArrayOf(1, 0, 0))

    @Test
    fun aGifLosesItsXmpExtensionButKeepsItsAnimationBlocks() {
        val original = gif(gifLoop, gifXmp)
        assertTrue(ImageMetadata.scan(original, ImageKind.Gif).xmp)
        assertFalse(ImageMetadata.scan(gif(gifLoop), ImageKind.Gif).any)
        val cleaned = ImageMetadata.stripGif(original)!!
        assertFalse(cleaned.contains("GPSLatitude".toByteArray()))
        assertArrayEquals(gif(gifLoop), cleaned)
        assertNull(ImageMetadata.stripGif(original.copyOf(original.size - 1)))
        assertNull(ImageMetadata.stripGif("GIF89a".toByteArray()))
    }
}
