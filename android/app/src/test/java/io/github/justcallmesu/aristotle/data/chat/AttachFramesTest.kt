package io.github.justcallmesu.aristotle.data.chat

import io.github.justcallmesu.aristotle.data.HermesJson
import java.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T16 frame builders against the params Hermes' handlers accept (cited per test). */
class AttachFramesTest {
    private fun json(text: String) = HermesJson.parseToJsonElement(text)

    private val png = PreparedAttachment("h1", "shot.png", "image/png", 3, "a".repeat(64), image = true)
    private val pdf = PreparedAttachment("h2", "report.pdf", "application/pdf", 3, "b".repeat(64), image = false)
    private val bytes = byteArrayOf(1, 2, 3)

    @Test
    fun anImageIsImageAttachBytesWithOnlyTheKeysTheOwnerTakes() {
        val call = AttachFrames.image("s1", png, bytes)
        assertEquals("image.attach_bytes", call.method)
        assertEquals(json("""{"session_id":"s1","content_base64":"AQID","filename":"shot.png"}"""), call.params)
        // gateway/session_images.py:9-12: anything outside this set is invalid_params.
        assertTrue(setOf("session_id", "profile", "content_base64", "data", "filename", "ext").containsAll(call.params.keys))
    }

    @Test
    fun aFileIsFileAttachWithADataUrlAndNeverAPath() {
        val call = AttachFrames.file("s1", pdf, bytes)
        assertEquals("file.attach", call.method)
        assertEquals(json("""{"session_id":"s1","name":"report.pdf","data_url":"data:application/pdf;base64,AQID"}"""), call.params)
        // gateway/session_ancillary.py:147: the accepted keys; `path` would name a file on the PC.
        assertTrue(setOf("session_id", "profile", "path", "data_url", "name", "staged_upload").containsAll(call.params.keys))
        assertTrue("path" !in call.params)
        // tui_gateway/prompt_attachments.py:319: the pattern Hermes decodes the data URL with.
        val dataUrl = (call.params["data_url"] as JsonPrimitive).content
        val match = Regex("^data:[^;,]*(?:;[^;,=]+=[^;,]+)*;base64,(.*)$", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(dataUrl)
        assertEquals(bytes.toList(), Base64.getDecoder().decode(match!!.groupValues[1]).toList())
    }

    @Test
    fun stageChoosesByKind() {
        assertEquals("image.attach_bytes", AttachFrames.stage("s", png, bytes).method)
        assertEquals("file.attach", AttachFrames.stage("s", pdf, bytes).method)
    }

    @Test
    fun hermesAnswersAreReadAsTheDesktopReadsThem() {
        // gateway/session_images.py:44-45
        assertEquals(
            Staged.Image("/h/cache/images/upload_x.png", "image/png"),
            AttachFrames.staged(json("""{"attached":true,"path":"/h/cache/images/upload_x.png","mime":"image/png","name":"upload_x.png","count":1,"bytes":3}"""), image = true),
        )
        // gateway/session_ancillary.py:179-180
        assertEquals(
            Staged.File("@file:/h/attachments/report.pdf"),
            AttachFrames.staged(json("""{"attached":true,"name":"report.pdf","path":"/h/attachments/report.pdf","ref_path":"/h/attachments/report.pdf","ref_text":"@file:/h/attachments/report.pdf","uploaded":true}"""), image = false),
        )
        assertNull(AttachFrames.staged(json("""{"attached":false,"message":"nope"}"""), image = true))
        assertEquals("nope", AttachFrames.refusal(json("""{"attached":false,"message":"nope"}""")))
        assertNull(AttachFrames.staged(json("""{"attached":true,"path":"/x.bmp","mime":"image/bmp"}"""), image = true))
        assertNull(AttachFrames.staged(json("""{"attached":true,"path":"/x"}"""), image = false))
        assertNull(AttachFrames.staged(json("[]"), image = false))
    }

    @Test
    fun promptTextIsTheDesktopsBuildContextText() {
        val file = Staged.File("@file:a.pdf")
        val image = Staged.Image("/i.png", "image/png")
        assertEquals("hello", AttachFrames.promptText("hello", emptyList()))
        assertEquals("@file:a.pdf\n@file:b.txt\n\nhello", AttachFrames.promptText("hello", listOf(file, Staged.File("@file:b.txt"), image)))
        assertEquals("@file:a.pdf", AttachFrames.promptText("", listOf(file)))
        assertEquals("What do you see in this image?", AttachFrames.promptText("", listOf(image)))
        assertEquals("look", AttachFrames.promptText("look", listOf(image)))
    }

    @Test
    fun submitCarriesStagedImagesAsPathAndMimeOnly() {
        val call = ComposerFrames.submit("s1", "hi", "sub-1", listOf(Staged.Image("/h/cache/images/u.png", "image/png")))
        assertEquals(
            json("""{"session_id":"s1","text":"hi","submission_id":"sub-1","attachments":[{"path":"/h/cache/images/u.png","mime":"image/png"}]}"""),
            call.params,
        )
        // gateway/session_ingress_media.py:147-150: each entry exactly {path, mime}.
        val entry = (call.params["attachments"] as kotlinx.serialization.json.JsonArray).single() as JsonObject
        assertEquals(setOf("path", "mime"), entry.keys)
        assertTrue("attachments" !in ComposerFrames.submit("s1", "hi", "sub-1").params)
    }
}
