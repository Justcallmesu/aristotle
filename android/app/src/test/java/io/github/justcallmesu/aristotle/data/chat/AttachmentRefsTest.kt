package io.github.justcallmesu.aristotle.data.chat

import io.github.justcallmesu.aristotle.data.HermesJson
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Test

/** T16 bubbles: a user row's attachments read the way the Desktop's hydration reads them. */
class AttachmentRefsTest {
    private val sha = "0123456789abcdef".repeat(4)
    private val native = "/home/u/.hermes/native-inputs/$sha/upload_ab12.jpg"

    @Test
    fun aNativeVisionRowLosesItsHintsAndScreenshotsAndKeepsTheCaption() {
        // agent/image_routing.py:561-563 + agent/session_persistence.py:122-141: caption, blank line, hints, [screenshot]s.
        val split = AttachmentRefs.split("look at this\n\n[Image attached at: $native]\n[Image attached at: /x/y/two.png]\n[screenshot]\n[screenshot]")
        assertEquals("look at this", split.text)
        assertEquals(
            listOf(
                UserAttachment(UserAttachment.Kind.Image, "upload_ab12.jpg", native, sha),
                UserAttachment(UserAttachment.Kind.Image, "two.png", "/x/y/two.png"),
            ),
            split.attachments,
        )
    }

    @Test
    fun aCountMismatchIsNotTakenForTheNativeSuffix() {
        val text = "a\n\n[Image attached at: /p.png]\n[screenshot]\n[screenshot]"
        assertEquals(AttachmentRefs.Split(text, emptyList()), AttachmentRefs.split(text))
    }

    @Test
    fun imageDirectivesAnywhereAreLiftedAndScreenshotsDroppedWithThem() {
        // tui_gateway/session_history.py:51-60 (caption first, directives last) and lib/embedded-images.ts:183-197.
        val split = AttachmentRefs.split("hi\n@image:/tmp/a.png\n@image:\"/tmp/with space.png\"\n[screenshot]")
        assertEquals("hi", split.text)
        assertEquals(listOf("a.png", "with space.png"), split.attachments.map { it.label })
        assertEquals(listOf("/tmp/a.png", "/tmp/with space.png"), split.attachments.map { it.value })
    }

    @Test
    fun theLeadingFileBlockBecomesChipsAndARefInProseStays() {
        // reference-kinds.ts:175-197: only the leading run of @file:/@folder: lines moves.
        val split = AttachmentRefs.split("@file:attachments/report.pdf\n@file:`my notes.txt`\n@folder:src/\n\nsee @file:x.txt please")
        assertEquals("see @file:x.txt please", split.text)
        assertEquals(
            listOf(
                UserAttachment(UserAttachment.Kind.File, "report.pdf", "attachments/report.pdf"),
                UserAttachment(UserAttachment.Kind.File, "my notes.txt", "my notes.txt"),
                UserAttachment(UserAttachment.Kind.Folder, "src", "src/"),
            ),
            split.attachments,
        )
    }

    @Test
    fun filesAndImagesTogetherKeepTheDesktopsOrder() {
        val split = AttachmentRefs.split("@file:/h/attachments/a.pdf\n\nWhat do you see in this image?\n\n[Image attached at: /i/b.png]\n[screenshot]")
        assertEquals("What do you see in this image?", split.text)
        assertEquals(listOf(UserAttachment.Kind.File, UserAttachment.Kind.Image), split.attachments.map { it.kind })
    }

    @Test
    fun plainTextIsLeftExactlyAsIs() {
        listOf("  hello  ", "[screenshot]", "@url:https://x", "x @image:/a.png", "").forEach {
            assertEquals(AttachmentRefs.Split(it, emptyList()), AttachmentRefs.split(it))
        }
    }

    @Test
    fun historyAndPendingUserRowsCarryTheirAttachments() {
        val rows = HermesJson.parseToJsonElement(
            """[{"role":"user","content":"@file:/h/attachments/a.pdf\n\nsum it\n\n[Image attached at: $native]\n[screenshot]","row_id":7}]""",
        ) as JsonArray
        val user = HistoryMapper.items(rows).single() as ChatItem.User
        assertEquals("sum it", user.text)
        assertEquals(listOf("a.pdf", "upload_ab12.jpg"), user.attachments.map { it.label })
        assertEquals(sha, user.attachments.last().sha256)
    }
}
