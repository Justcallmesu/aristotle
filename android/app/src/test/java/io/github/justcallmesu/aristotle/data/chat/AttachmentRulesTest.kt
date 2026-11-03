package io.github.justcallmesu.aristotle.data.chat

import io.github.justcallmesu.aristotle.data.ProfileLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The size and type rules of T16 against the relay's frame cap and Hermes' accepted types. */
class AttachmentRulesTest {
    @Test
    fun theLargestPayloadIsWhatOneMebibyteFrameHoldsAfterItsEnvelope() {
        assertEquals(1_048_576, AttachmentRules.MAX_FRAME_BYTES)
        assertEquals(783_360L, AttachmentRules.MAX_PAYLOAD_BYTES)
        // Base64 of the cap is 4/3 of it, and still leaves the reserve for the envelope.
        assertEquals(1_044_480L, AttachmentRules.MAX_PAYLOAD_BYTES / 3 * 4)
    }

    @Test
    fun aMaximalImageOrFileFrameWithLongIdsNamesAndProfileStaysUnderTheRelayCap() {
        val bytes = ByteArray(AttachmentRules.MAX_PAYLOAD_BYTES.toInt()) { (it * 31).toByte() }
        val session = "local-" + "f".repeat(200)
        val name = AttachmentRules.fileName("é".repeat(400) + ".pdf", null, "file")
        val image = PreparedAttachment("h", AttachmentRules.fileName("ü".repeat(400) + ".png", "png", "image"), "image/png", bytes.size.toLong(), "0".repeat(64), image = true)
        val file = image.copy(name = name, mime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document", image = false)
        listOf(AttachFrames.image(session, image, bytes), AttachFrames.file(session, file, bytes)).forEach { call ->
            val routed = call.copy(params = ProfileLink.withProfile(call.params, "p".repeat(64)))
            val size = AttachFrames.frameBytes(routed)
            assertTrue("$size", size <= AttachmentRules.MAX_FRAME_BYTES)
        }
    }

    @Test
    fun aPayloadAReserveLargerWouldNotFit() {
        val bytes = ByteArray((AttachmentRules.MAX_PAYLOAD_BYTES + AttachmentRules.ENVELOPE_RESERVE).toInt())
        val prepared = PreparedAttachment("h", "a.bin", "application/octet-stream", bytes.size.toLong(), "0".repeat(64), image = false)
        assertTrue(AttachFrames.frameBytes(AttachFrames.file("s", prepared, bytes)) > AttachmentRules.MAX_FRAME_BYTES)
    }

    @Test
    fun sniffTellsFormatsByTheirFirstBytes() {
        fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
        assertEquals(ImageKind.Jpeg, AttachmentRules.sniff(bytes(0xFF, 0xD8, 0xFF, 0xE1)))
        assertEquals(ImageKind.Png, AttachmentRules.sniff(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
        assertEquals(ImageKind.Gif, AttachmentRules.sniff("GIF89a....".toByteArray()))
        assertEquals(ImageKind.Webp, AttachmentRules.sniff("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)))
        assertEquals(ImageKind.Heif, AttachmentRules.sniff("\u0000\u0000\u0000\u0018ftypheic".toByteArray(Charsets.ISO_8859_1)))
        assertEquals(ImageKind.Bmp, AttachmentRules.sniff("BM......".toByteArray()))
        assertEquals(ImageKind.Other, AttachmentRules.sniff(ByteArray(0)))
    }

    @Test
    fun hermesAcceptsOnlyPngJpegGifAndWebp() {
        // gateway/session_ingress_media.py:122 and gateway/session_images.py:36-39.
        assertEquals(setOf(ImageKind.Jpeg, ImageKind.Png, ImageKind.Gif, ImageKind.Webp), ImageKind.entries.filter { it.hermesAccepts }.toSet())
        assertEquals(AttachFrames.HERMES_IMAGE_MIMES, ImageKind.entries.mapNotNull { it.mime }.toSet())
    }

    @Test
    fun planSendsOriginalsThatFitAndReencodesTheRest() {
        val fits = AttachmentRules.MAX_PAYLOAD_BYTES
        val over = fits + 1
        assertEquals(ImagePlan.Original, AttachmentRules.plan(ImageKind.Jpeg, fits, metadata = true))
        assertEquals(ImagePlan.Reencode, AttachmentRules.plan(ImageKind.Jpeg, over, metadata = false))
        assertEquals(ImagePlan.Original, AttachmentRules.plan(ImageKind.Png, fits, metadata = false))
        assertEquals(ImagePlan.Reencode, AttachmentRules.plan(ImageKind.Png, 10, metadata = true))
        assertEquals(ImagePlan.Reencode, AttachmentRules.plan(ImageKind.Webp, over, metadata = false))
        // HEIC (the camera's own format) is not taken by Hermes: always JPEG.
        assertEquals(ImagePlan.Reencode, AttachmentRules.plan(ImageKind.Heif, 10, metadata = false))
        assertEquals(ImagePlan.Reencode, AttachmentRules.plan(ImageKind.Bmp, 10, metadata = false))
        assertEquals(ImagePlan.Original, AttachmentRules.plan(ImageKind.Gif, fits, metadata = false))
        assertEquals(ImagePlan.Refuse(AttachmentProblem.TooLarge(fits, over)), AttachmentRules.plan(ImageKind.Gif, over, metadata = false))
        val huge = AttachmentRules.MAX_SOURCE_BYTES + 1
        assertEquals(ImagePlan.Refuse(AttachmentProblem.TooLarge(fits, huge)), AttachmentRules.plan(ImageKind.Jpeg, huge, metadata = false))
    }

    @Test
    fun filesFitUpToTheCapOnly() {
        assertTrue(AttachmentRules.fileFits(AttachmentRules.MAX_PAYLOAD_BYTES))
        assertTrue(!AttachmentRules.fileFits(AttachmentRules.MAX_PAYLOAD_BYTES + 1))
    }

    @Test
    fun theLadderStartsAtHermesEmbedSizeAndOnlyShrinks() {
        assertEquals(1568 to 85, AttachmentRules.LADDER.first())
        AttachmentRules.LADDER.zipWithNext().forEach { (a, b) -> assertTrue(b.first <= a.first && b.second <= a.second) }
        assertEquals(1568 to 1176, AttachmentRules.scaled(4000, 3000, 1568))
        assertEquals(1176 to 1568, AttachmentRules.scaled(3000, 4000, 1568))
        assertEquals(800 to 600, AttachmentRules.scaled(800, 600, 1568))
    }

    @Test
    fun namesAreBaseNamesWithoutControlCharactersAndAnImageTakesItsNewExtension() {
        assertEquals("IMG_1234.jpg", AttachmentRules.fileName("IMG_1234.HEIC", "jpg", "image"))
        assertEquals("photo.png", AttachmentRules.fileName("/storage/../photo.png", "png", "image"))
        assertEquals("evil.pdf", AttachmentRules.fileName("..\\..\\evil.pdf", null, "file"))
        assertEquals("ab.txt", AttachmentRules.fileName("a\u0000\nb.txt", null, "file"))
        assertEquals("image.jpg", AttachmentRules.fileName(null, "jpg", "image"))
        assertEquals("file", AttachmentRules.fileName("   ", null, "file"))
        assertEquals("env", AttachmentRules.fileName(".env", null, "file"))
        val long = AttachmentRules.fileName("x".repeat(300) + ".pdf", null, "file")
        assertEquals(AttachmentRules.MAX_NAME_CHARS, long.length)
        assertTrue(long.endsWith(".pdf"))
    }

    @Test
    fun aMimeTypeHermesDataUrlPatternWouldMisreadBecomesOctetStream() {
        assertEquals("application/pdf", AttachmentRules.safeMime("Application/PDF"))
        assertEquals("application/octet-stream", AttachmentRules.safeMime("text/plain; charset=utf-8"))
        assertEquals("application/octet-stream", AttachmentRules.safeMime("a,b/c"))
        assertEquals("application/octet-stream", AttachmentRules.safeMime(null))
    }
}
