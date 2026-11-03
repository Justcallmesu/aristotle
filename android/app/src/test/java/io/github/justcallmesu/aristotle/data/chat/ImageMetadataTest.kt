package io.github.justcallmesu.aristotle.data.chat

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
