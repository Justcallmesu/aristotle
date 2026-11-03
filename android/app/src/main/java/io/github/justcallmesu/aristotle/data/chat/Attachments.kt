package io.github.justcallmesu.aristotle.data.chat

/** What the user picked with: the photo picker, the camera, or the document picker (T16). */
enum class PickKind { Photo, Camera, File }

/**
 * Why a picked item cannot go. [TooLarge]: over [AttachmentRules.MAX_PAYLOAD_BYTES] after preparing ([limit] bytes;
 * [size] what it would have been, null when unknown). [Unreadable]: the item could not be read or decoded.
 */
sealed interface AttachmentProblem {
    data class TooLarge(val limit: Long, val size: Long?) : AttachmentProblem

    data object Unreadable : AttachmentProblem
}

/**
 * A picked item ready to upload: [handle] names its prepared bytes in the [AttachmentSource] (never a path on the PC),
 * [name] the file name Hermes gets, [mime] the bytes' type, [size] their length, [sha256] their digest (lower-case
 * hex; Hermes names a stored image's folder by it, so a bubble can find its thumbnail), [image]: staged with
 * `image.attach_bytes`, else `file.attach`.
 */
data class PreparedAttachment(
    val handle: String,
    val name: String,
    val mime: String,
    val size: Long,
    val sha256: String,
    val image: Boolean,
)

/** The result of preparing one picked item. */
sealed interface Preparation {
    data class Ready(val prepared: PreparedAttachment) : Preparation

    data class Refused(val name: String, val problem: AttachmentProblem) : Preparation
}

/**
 * What Hermes handed back for a staged attachment: an image's gateway-side path and type (sent in `prompt.submit
 * attachments`), or a file's `@file:` reference (sent as a line of the prompt text), as the Desktop does
 * (`apps/desktop/src/app/session/hooks/use-prompt-actions/index.ts:181-230`, `submit.ts:289-303`, `:1080-1088`).
 */
sealed interface Staged {
    data class Image(val path: String, val mime: String) : Staged

    data class File(val refText: String) : Staged
}

/** An attachment's upload while its message is being sent. */
enum class Upload { Waiting, Sending, Sent, Failed }

/** Where a picked item stands in the composer. */
sealed interface AttachmentStatus {
    data object Preparing : AttachmentStatus

    data object Ready : AttachmentStatus

    data class Refused(val problem: AttachmentProblem) : AttachmentStatus
}

/**
 * One attachment of the composer or of a sent message. [staged] is kept across Retry so a retry never stages a
 * file again under a new name (that would change the prompt text and Hermes would refuse the same `submission_id`).
 */
data class Attachment(
    val id: String,
    val name: String,
    val kind: PickKind,
    val status: AttachmentStatus = AttachmentStatus.Preparing,
    val prepared: PreparedAttachment? = null,
    val staged: Staged? = null,
    val upload: Upload = Upload.Waiting,
) {
    val image: Boolean get() = prepared?.image ?: (kind != PickKind.File)
    val ready: Boolean get() = status == AttachmentStatus.Ready && prepared != null
}

/** Reads and prepares picked items on the phone (the Android side implements it; tests fake it). */
interface AttachmentSource {
    /** Prepares [uri] off the main thread: checks size, strips location, re-encodes when needed (T16). */
    suspend fun prepare(uri: String, kind: PickKind): Preparation

    /** The prepared bytes of [handle], null when they are gone. */
    suspend fun read(handle: String): ByteArray?

    /** Forgets [handle]'s bytes. */
    fun discard(handle: String)

    /** A fresh `content://` target for the camera to write into, null when there is none. */
    fun cameraTarget(): String?
}
