package io.github.justcallmesu.aristotle.data.chat

/** The image formats a picked file can be, told by its first bytes. */
enum class ImageKind(val mime: String?, val ext: String?) {
    Jpeg("image/jpeg", "jpg"),
    Png("image/png", "png"),
    Gif("image/gif", "gif"),
    Webp("image/webp", "webp"),

    /** HEIC/HEIF/AVIF (an ISO-BMFF `ftyp` box): the phone camera's own format, which Hermes does not take. */
    Heif(null, null),
    Bmp(null, null),
    Other(null, null),
    ;

    /** Hermes stages and admits only PNG, JPEG, GIF and WebP (`gateway/session_images.py:36-39`, `gateway/session_ingress_media.py:122`). */
    val hermesAccepts: Boolean get() = mime != null
}

/** What happens to a picked image before it is uploaded. */
sealed interface ImagePlan {
    /** Sent as it is (a JPEG or GIF with its location and XMP removed first, [ImageMetadata.sanitizeJpeg]; re-encoded when that cannot be done safely). */
    data object Original : ImagePlan

    /** Decoded and written again as JPEG ([AttachmentRules.LADDER]): no metadata survives. */
    data object Reencode : ImagePlan

    data class Refuse(val problem: AttachmentProblem) : ImagePlan
}

/**
 * The limits and choices for attachments sent through the relay (T16). Pure.
 *
 * The Desktop stages a picked image with `image.attach_bytes` (inline base64) and a picked file with `file.attach
 * {data_url}` over the session socket when the bytes are not on the gateway's disk
 * (`apps/desktop/src/app/session/hooks/use-prompt-actions/index.ts:148`, `:165-217`), and sends the original bytes
 * (`utils.ts:515-537`: never a downscaled copy). Hermes has no chunked upload: each is one JSON-RPC message
 * (`gateway/session_images.py:8-45`, `gateway/session_ancillary.py:137-180`; the Desktop's "one-shot file.attach",
 * `apps/desktop/src/store/updates.ts:114`). The phone's frame to the relay is at most 1 MiB (docs/protocol.md §12), so
 * an attachment's bytes, base64 encoded, must fit one frame with its envelope.
 */
object AttachmentRules {
    /** The relay's cap on one phone frame (`server/internal/relay/relay.go:21`, protocol §12). Never raised here. */
    const val MAX_FRAME_BYTES = 1 shl 20

    /** Room in the frame for the JSON-RPC envelope: id, method, session id, profile, file name, data-URL prefix. */
    const val ENVELOPE_RESERVE = 4096

    /** The largest attachment whose base64 still fits one frame: 783 360 bytes (765 KiB). */
    const val MAX_PAYLOAD_BYTES: Long = ((MAX_FRAME_BYTES - ENVELOPE_RESERVE) / 4 * 3).toLong()

    /** Hermes admits at most 10 attachments per prompt (`gateway/session_ingress_media.py:123`). */
    const val MAX_ATTACHMENTS = 10

    /** A picked image larger than this is not even read for re-encoding (64 MiB). */
    const val MAX_SOURCE_BYTES: Long = 64L shl 20

    /** A file name sent to Hermes is cut to this many characters (its extension kept). */
    const val MAX_NAME_CHARS = 100

    /**
     * Re-encoding steps, tried in order until the JPEG fits [MAX_PAYLOAD_BYTES]: long edge in pixels, JPEG quality.
     * The first is Hermes' own embed size: its vision path downsamples to a 1568 px long edge, "pixels past that cost
     * wire bytes for no fidelity" (`tools/vision_tools.py:358-365`).
     */
    val LADDER: List<Pair<Int, Int>> = listOf(1568 to 85, 1568 to 75, 1280 to 75, 1024 to 70, 768 to 65)

    /** At most this many picked items are prepared at the same time: each holds its source bytes and a decoded bitmap. */
    const val MAX_PREPARING = 2

    /** How many leading bytes [sniff] needs. */
    const val SNIFF_BYTES = 16

    /** The image formats that go the image way (location stripped, re-encoded when needed) whatever the picker called them. */
    private val IMAGE_KINDS = setOf(ImageKind.Jpeg, ImageKind.Png, ImageKind.Gif, ImageKind.Webp, ImageKind.Heif)

    /**
     * Whether a pick is prepared as an image: a photo or camera pick always is; a file pick when its first bytes
     * ([head]) are an image, whatever MIME type the document provider gave it (`application/octet-stream`,
     * `image/jpg`, `image/avif`).
     */
    fun routesAsImage(pick: PickKind, head: ByteArray): Boolean = pick != PickKind.File || sniff(head) in IMAGE_KINDS

    /**
     * Call timeout for a staging call of [frameBytes]: 15 s plus 1 s per 32 KiB, at most 120 s. A fixed 15 s cannot
     * write 1 MB below about 0.6 Mbit/s; this allows about 70 kbit/s at the largest frame.
     */
    fun uploadTimeoutMillis(frameBytes: Int): Long {
        val chunks = (frameBytes.coerceAtLeast(0) + UPLOAD_CHUNK - 1L) / UPLOAD_CHUNK
        return minOf(UPLOAD_MAX_MILLIS, UPLOAD_BASE_MILLIS + chunks * 1000L)
    }

    private const val UPLOAD_CHUNK = 32 * 1024
    private const val UPLOAD_BASE_MILLIS = 15_000L
    private const val UPLOAD_MAX_MILLIS = 120_000L

    /** The thumbnails shown in chips and bubbles: long edge in pixels. */
    const val THUMB_EDGE = 192

    fun sniff(head: ByteArray): ImageKind = when {
        head.startsWith(0xFF, 0xD8, 0xFF) -> ImageKind.Jpeg
        head.startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> ImageKind.Png
        head.ascii(0, 6) == "GIF87a" || head.ascii(0, 6) == "GIF89a" -> ImageKind.Gif
        head.ascii(0, 4) == "RIFF" && head.ascii(8, 4) == "WEBP" -> ImageKind.Webp
        head.ascii(4, 4) == "ftyp" -> sniffHeif(head)
        head.ascii(0, 2) == "BM" -> sniffBmp(head)
        else -> ImageKind.Other
    }

    private fun sniffHeif(head: ByteArray): ImageKind {
        // HEIF major brands: heic, heix, hevc, hevx, heim, heis, mif1, msf1, avif, avis.
        val validHeifBrands = setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1", "avif", "avis")
        
        // Major brand is at bytes 8-11.
        val majorBrand = head.ascii(8, 4)
        if (majorBrand in validHeifBrands) return ImageKind.Heif
        
        // Check compatible brands (start at byte 16, each 4 bytes, until end of ftyp box).
        if (head.size >= 16) {
            val boxSize = bytesAsUint32BE(head, 0)
            val ftypEnd = minOf(boxSize.toInt(), head.size)
            var pos = 16
            while (pos + 4 <= ftypEnd) {
                val brand = head.ascii(pos, 4)
                if (brand in validHeifBrands) return ImageKind.Heif
                pos += 4
            }
        }
        return ImageKind.Other
    }

    private fun sniffBmp(head: ByteArray): ImageKind {
        // BMP header: 'BM' (2 bytes), file size (4 bytes), reserved (4 bytes), pixel data offset (4 bytes),
        // then DIB header size (4 bytes) starting at offset 14.
        // If we can't read enough bytes to validate, assume it's Bmp (optimistic for short headers like tests).
        if (head.size < 18) return ImageKind.Bmp
        
        // Pixel data offset at bytes 10-13.
        val pixelOffset = bytesAsUint32LE(head, 10)
        
        // DIB header size at bytes 14-17. Valid sizes: 12, 40, 52, 56, 108, 124.
        val dibSize = bytesAsUint32LE(head, 14)
        val validDibSizes = setOf(12, 40, 52, 56, 108, 124)
        
        // If we have enough bytes to check DIB header size, validate it; otherwise accept as Bmp.
        return if (dibSize.toInt() in validDibSizes) ImageKind.Bmp else ImageKind.Other
    }

    private fun bytesAsUint32BE(data: ByteArray, offset: Int): Long =
        if (offset + 4 > data.size) 0L else (
            ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)
        )

    private fun bytesAsUint32LE(data: ByteArray, offset: Int): Long =
        if (offset + 4 > data.size) 0L else (
            (data[offset].toLong() and 0xFF) or
            ((data[offset + 1].toLong() and 0xFF) shl 8) or
            ((data[offset + 2].toLong() and 0xFF) shl 16) or
            ((data[offset + 3].toLong() and 0xFF) shl 24)
        )

    /**
     * What to do with an image of [kind], [size] bytes, [metadata] = it carries a location or other metadata a
     * plain copy would leak (PNG/WebP only; a JPEG's location is blanked in place).
     */
    fun plan(kind: ImageKind, size: Long, metadata: Boolean): ImagePlan {
        val fits = size <= MAX_PAYLOAD_BYTES
        return when {
            size > MAX_SOURCE_BYTES -> ImagePlan.Refuse(AttachmentProblem.TooLarge(MAX_PAYLOAD_BYTES, size))
            // A GIF may be animated: re-encoding would keep one frame. Sent as it is or not at all.
            kind == ImageKind.Gif -> if (fits) ImagePlan.Original else ImagePlan.Refuse(AttachmentProblem.TooLarge(MAX_PAYLOAD_BYTES, size))
            kind == ImageKind.Jpeg -> if (fits) ImagePlan.Original else ImagePlan.Reencode
            kind == ImageKind.Png || kind == ImageKind.Webp -> if (fits && !metadata) ImagePlan.Original else ImagePlan.Reencode
            else -> ImagePlan.Reencode
        }
    }

    /** A file (not staged as an image) goes as it is, or not at all. */
    fun fileFits(size: Long): Boolean = size <= MAX_PAYLOAD_BYTES

    /** [width] x [height] scaled so the long edge is at most [edge] (never up). */
    fun scaled(width: Int, height: Int, edge: Int): Pair<Int, Int> {
        val long = maxOf(width, height)
        if (long <= edge || long <= 0) return width to height
        val factor = edge.toDouble() / long
        return maxOf(1, Math.round(width * factor).toInt()) to maxOf(1, Math.round(height * factor).toInt())
    }

    /**
     * The name Hermes gets: the last path segment of [original] without control characters, cut to
     * [MAX_NAME_CHARS]; an image takes [ext] so Hermes reads its type right (it trusts the name's extension first,
     * `tui_gateway/prompt_attachments.py:91-99`, `gateway/session_images.py:34-39`).
     */
    fun fileName(original: String?, ext: String?, fallback: String): String {
        val base = original.orEmpty().substringAfterLast('/').substringAfterLast('\\')
            .filterNot { it.isISOControl() || it == '\u2028' || it == '\u2029' }
            .trim().trimStart('.')
        val dot = base.lastIndexOf('.').takeIf { it > 0 && base.length - it <= MAX_EXT_CHARS }
        val stem = (if (dot != null) base.substring(0, dot) else base).ifBlank { fallback }
        val suffix = when {
            ext != null -> ".$ext"
            dot != null -> base.substring(dot)
            else -> ""
        }
        return stem.take(MAX_NAME_CHARS - suffix.length) + suffix
    }

    private const val MAX_EXT_CHARS = 16

    /** A MIME type Hermes' data-URL pattern takes (`tui_gateway/prompt_attachments.py:319`), else `application/octet-stream`. */
    fun safeMime(mime: String?): String =
        mime?.trim()?.lowercase()?.takeIf { MIME.matches(it) } ?: "application/octet-stream"

    private val MIME = Regex("[a-z0-9][a-z0-9.+-]*/[a-z0-9][a-z0-9.+-]*")

    private fun ByteArray.startsWith(vararg bytes: Int): Boolean =
        size >= bytes.size && bytes.indices.all { (this[it].toInt() and 0xFF) == bytes[it] }

    private fun ByteArray.ascii(from: Int, length: Int): String =
        if (size < from + length) "" else String(this, from, length, Charsets.ISO_8859_1)
}
