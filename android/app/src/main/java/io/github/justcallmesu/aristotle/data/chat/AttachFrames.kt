package io.github.justcallmesu.aristotle.data.chat

import io.github.justcallmesu.aristotle.data.RpcFrames
import java.util.Base64
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The calls that stage a picked item on Hermes before the prompt that carries it (T16), each with only the keys the
 * owner accepts, and the prompt text the Desktop builds around them. Pure.
 */
object AttachFrames {
    /**
     * An image: `image.attach_bytes {session_id, content_base64, filename}` (the Desktop's remote-gateway call,
     * `apps/desktop/src/app/session/hooks/use-prompt-actions/index.ts:183-188`; params closed to `session_id`, `profile`,
     * `content_base64`, `data`, `filename`, `ext`, `gateway/session_images.py:9-12`). Hermes writes the bytes into its own
     * `cache/images` and answers `{attached, path, mime, …}` (`:19-45`).
     */
    fun image(sessionId: String, prepared: PreparedAttachment, bytes: ByteArray) = ComposerCall(
        "image.attach_bytes",
        buildJsonObject {
            put("session_id", sessionId)
            put("content_base64", Base64.getEncoder().encodeToString(bytes))
            put("filename", prepared.name)
        },
    )

    /**
     * A file: `file.attach {session_id, name, data_url}` with no `path` (a path names a file on the PC, and the
     * phone's never does; the Desktop sends `data_url` when the file is not on the gateway, `index.ts:209-217`).
     * Params closed to `session_id`, `profile`, `path`, `data_url`, `name`, `staged_upload`
     * (`gateway/session_ancillary.py:146-150`). Hermes copies the bytes into the profile's `attachments/` and answers
     * `{attached, name, path, ref_path, ref_text}` (`:165-180`).
     */
    fun file(sessionId: String, prepared: PreparedAttachment, bytes: ByteArray) = ComposerCall(
        "file.attach",
        buildJsonObject {
            put("session_id", sessionId)
            put("name", prepared.name)
            put("data_url", "data:${AttachmentRules.safeMime(prepared.mime)};base64,${Base64.getEncoder().encodeToString(bytes)}")
        },
    )

    fun stage(sessionId: String, prepared: PreparedAttachment, bytes: ByteArray): ComposerCall =
        if (prepared.image) image(sessionId, prepared, bytes) else file(sessionId, prepared, bytes)

    /**
     * Hermes' answer to a staging call: an image needs `path` and an admissible `mime`, a file its `ref_text` (the
     * Desktop refuses either without them, `index.ts:194-196`, `:219-221`). Null: not staged.
     */
    fun staged(result: JsonElement?, image: Boolean): Staged? {
        val answer = result.asObject() ?: return null
        if (answer.flag("attached") != true) return null
        return if (image) {
            val path = answer.text("path")?.takeIf { it.isNotBlank() } ?: return null
            val mime = answer.text("mime")?.takeIf { it in HERMES_IMAGE_MIMES } ?: return null
            Staged.Image(path, mime)
        } else {
            answer.text("ref_text")?.takeIf { it.isNotBlank() }?.let(Staged::File)
        }
    }

    /** Hermes' own words when it did not stage (`message`), if any. */
    fun refusal(result: JsonElement?): String? = result.asObject()?.text("message")?.takeIf { it.isNotBlank() }

    /**
     * The prompt text: the files' `@file:` lines, a blank line, then the typed text; only images and no text ->
     * "What do you see in this image?" (the Desktop's `buildContextText`, `submit.ts:289-303`).
     */
    fun promptText(text: String, staged: List<Staged>): String {
        val refs = staged.filterIsInstance<Staged.File>().joinToString("\n") { it.refText }
        return listOf(refs, text).filter { it.isNotEmpty() }.joinToString("\n\n")
            .ifEmpty { if (staged.any { it is Staged.Image }) NO_TEXT_IMAGE_PROMPT else "" }
    }

    /** The JSON-RPC frame [call] makes, in bytes (the relay counts bytes, protocol §12); the largest id counted. */
    fun frameBytes(call: ComposerCall): Int = RpcFrames.request(Long.MAX_VALUE, call.method, call.params).toByteArray(Charsets.UTF_8).size

    /** The Desktop's text for an image sent without words (`submit.ts:301-302`). */
    const val NO_TEXT_IMAGE_PROMPT = "What do you see in this image?"

    /** What `prompt.submit attachments[].mime` may be (`gateway/session_ingress_media.py:122`). */
    val HERMES_IMAGE_MIMES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")
}
