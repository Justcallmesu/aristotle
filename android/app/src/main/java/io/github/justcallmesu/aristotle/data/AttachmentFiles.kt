package io.github.justcallmesu.aristotle.data

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.net.toUri
import io.github.justcallmesu.aristotle.data.chat.AttachmentProblem
import io.github.justcallmesu.aristotle.data.chat.AttachmentRules
import io.github.justcallmesu.aristotle.data.chat.AttachmentSource
import io.github.justcallmesu.aristotle.data.chat.ImageKind
import io.github.justcallmesu.aristotle.data.chat.ImageMetadata
import io.github.justcallmesu.aristotle.data.chat.ImagePlan
import io.github.justcallmesu.aristotle.data.chat.PickKind
import io.github.justcallmesu.aristotle.data.chat.Preparation
import io.github.justcallmesu.aristotle.data.chat.PreparedAttachment
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Picked photos and files on the phone (T16): read through the [ContentResolver] (photo picker, camera via this app's
 * FileProvider, document picker: no storage or camera permission), prepared by [AttachmentRules] and
 * [ImageMetadata], and kept in the app's private cache until sent. Nothing here ever names a path on the PC.
 */
class AttachmentFiles(context: Context, private val io: CoroutineDispatcher = Dispatchers.IO) : AttachmentSource {
    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private val authority = "${app.packageName}.files"
    private val prepared = File(app.cacheDir, PREPARED_DIR)
    private val camera = File(app.cacheDir, CAMERA_DIR)
    private val thumbs = File(app.cacheDir, THUMBS_DIR)

    override suspend fun prepare(uri: String, kind: PickKind): Preparation = withContext(io) {
        prune()
        val parsed = uri.toUri()
        val meta = runCatching { describe(parsed) }.getOrElse { Meta(null, null, null) }
        val fallback = if (kind == PickKind.File) "file" else "image"
        try {
            // By content, not by the picker's MIME type: a JPEG labelled application/octet-stream still loses its GPS.
            val asImage = kind != PickKind.File || AttachmentRules.routesAsImage(kind, open(parsed).use { it.readHead(AttachmentRules.SNIFF_BYTES) })
            if (asImage) prepareImage(parsed, meta, fallback) else prepareFile(parsed, meta, fallback)
        } catch (e: OutOfMemoryError) {
            Preparation.Refused(meta.name ?: fallback, AttachmentProblem.OutOfMemory)
        } catch (e: IOException) {
            Preparation.Refused(meta.name ?: fallback, AttachmentProblem.Unreadable)
        } catch (e: SecurityException) {
            Preparation.Refused(meta.name ?: fallback, AttachmentProblem.Unreadable)
        } catch (e: IllegalArgumentException) {
            Preparation.Refused(meta.name ?: fallback, AttachmentProblem.Unreadable)
        } finally {
            if (kind == PickKind.Camera) cameraFile(parsed)?.delete()
        }
    }

    override suspend fun read(handle: String): ByteArray? = withContext(io) {
        preparedFile(handle)?.takeIf { it.isFile }?.readBytes()
    }

    override fun discard(handle: String) {
        preparedFile(handle)?.delete()
    }

    override fun discardCamera(target: String) {
        cameraFile(target.toUri())?.delete()
    }

    override fun cameraTarget(): String? = runCatching {
        camera.mkdirs()
        val file = File(camera, "${UUID.randomUUID()}.jpg")
        file.createNewFile()
        FileProvider.getUriForFile(app, authority, file).toString()
    }.getOrNull()

    // ── preparing ─────────────────────────────────────────────────────────────────────────────────

    private class Meta(val name: String?, val size: Long?, val type: String?)

    private fun describe(uri: Uri): Meta {
        var name: String? = null
        var size: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getString)
                size = cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong)
            }
        }
        return Meta(name, size, resolver.getType(uri))
    }

    /** A file goes byte for byte, or is refused when it would not fit one relay frame. */
    private fun prepareFile(uri: Uri, meta: Meta, fallback: String): Preparation {
        val name = AttachmentRules.fileName(meta.name, null, fallback)
        val limit = AttachmentRules.MAX_PAYLOAD_BYTES
        meta.size?.takeIf { !AttachmentRules.fileFits(it) }?.let { return Preparation.Refused(name, AttachmentProblem.TooLarge(limit, it)) }
        val bytes = open(uri).use { it.readAtMost(limit, meta.size) } ?: return Preparation.Refused(name, AttachmentProblem.TooLarge(limit, meta.size))
        return Preparation.Ready(store(bytes, name, AttachmentRules.safeMime(meta.type), image = false))
    }

    /**
     * An image: sent as it is (location and XMP removed) when it fits and Hermes takes its type, else re-encoded as
     * JPEG. A JPEG or GIF whose metadata cannot be removed safely ([ImageMetadata.sanitizeJpeg], [ImageMetadata.stripGif]
     * return null) is re-encoded too.
     */
    private fun prepareImage(uri: Uri, meta: Meta, fallback: String): Preparation {
        val limit = AttachmentRules.MAX_PAYLOAD_BYTES
        val source = open(uri).use { it.readAtMost(AttachmentRules.MAX_SOURCE_BYTES, meta.size) }
            ?: return Preparation.Refused(meta.name ?: fallback, AttachmentProblem.TooLarge(limit, meta.size))
        val kind = AttachmentRules.sniff(source.copyOf(minOf(source.size, AttachmentRules.SNIFF_BYTES)))
        val found = ImageMetadata.scan(source, kind)
        val plan = AttachmentRules.plan(kind, source.size.toLong(), found.any)
        if (plan is ImagePlan.Refuse) return Preparation.Refused(meta.name ?: fallback, plan.problem)
        var out = kind
        var bytes: ByteArray? = if (plan == ImagePlan.Original) {
            when (kind) {
                ImageKind.Jpeg -> ImageMetadata.sanitizeJpeg(source)
                ImageKind.Gif -> ImageMetadata.stripGif(source)
                else -> source
            }
        } else {
            null
        }
        if (bytes == null) {
            bytes = reencode(source)?.let(ImageMetadata::stripLocation)
                ?: return Preparation.Refused(meta.name ?: fallback, AttachmentProblem.TooLarge(limit, source.size.toLong()))
            out = ImageKind.Jpeg
        }
        val name = AttachmentRules.fileName(meta.name, out.ext, fallback)
        val prepared = store(bytes, name, out.mime ?: "image/jpeg", image = true)
        runCatching { thumbnail(bytes, prepared.sha256) }
        return Preparation.Ready(prepared)
    }

    /**
     * Decoded once at the first step's size (ImageDecoder applies the Exif orientation and reads HEIC), then written
     * as JPEG down [AttachmentRules.LADDER] until it fits. A JPEG written by [Bitmap.compress] carries no Exif (the
     * caller still blanks any location it finds). Null: even the last step is too large.
     */
    private fun reencode(source: ByteArray): ByteArray? {
        val first = AttachmentRules.LADDER.first().first
        val decoded = decode(source, first)
        val opaque = if (decoded.hasAlpha()) onWhite(decoded).also { decoded.recycle() } else decoded
        for ((edge, quality) in AttachmentRules.LADDER) {
            val (w, h) = AttachmentRules.scaled(opaque.width, opaque.height, edge)
            val step = if (w == opaque.width && h == opaque.height) opaque else opaque.scale(w, h)
            val out = ByteArrayOutputStream()
            step.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (step !== opaque) step.recycle()
            if (out.size() <= AttachmentRules.MAX_PAYLOAD_BYTES) return out.toByteArray()
        }
        return null
    }

    /** Decoded at the target size (the decoder samples while it reads), so a full-resolution photo never exists as a full-size bitmap. */
    private fun decode(bytes: ByteArray, edge: Int): Bitmap = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            val (w, h) = AttachmentRules.scaled(info.size.width, info.size.height, edge)
            decoder.setTargetSize(w, h)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } catch (e: ImageDecoder.DecodeException) {
        throw IOException("not a decodable image", e)
    }

    /** Transparent parts become white: JPEG has no alpha and would turn them black. */
    private fun onWhite(bitmap: Bitmap): Bitmap {
        val out = createBitmap(bitmap.width, bitmap.height)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, 0f, 0f, null)
        }
        return out
    }

    private fun thumbnail(bytes: ByteArray, sha256: String) {
        val file = thumbFile(app.cacheDir, sha256)
        if (file.isFile) return
        thumbs.mkdirs()
        val small = decode(bytes, AttachmentRules.THUMB_EDGE)
        val image = if (small.hasAlpha()) onWhite(small) else small
        file.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, it) }
    }

    private fun store(bytes: ByteArray, name: String, mime: String, image: Boolean): PreparedAttachment {
        prepared.mkdirs()
        val handle = UUID.randomUUID().toString()
        File(prepared, handle).writeBytes(bytes)
        return PreparedAttachment(handle, name, mime, bytes.size.toLong(), sha256(bytes), image)
    }

    // ── files ─────────────────────────────────────────────────────────────────────────────────────

    private fun open(uri: Uri): InputStream = resolver.openInputStream(uri) ?: throw IOException("no stream")

    /** A handle is a UUID this class made; anything else names no file. */
    private fun preparedFile(handle: String): File? = File(prepared, handle).takeIf { HANDLE.matches(handle) }

    /** The camera's file behind [uri] when it is one of this app's FileProvider targets. */
    private fun cameraFile(uri: Uri): File? {
        if (uri.authority != authority) return null
        val name = uri.lastPathSegment?.takeIf { CAMERA_NAME.matches(it) } ?: return null
        return File(camera, name)
    }

    /** Leftovers (a message sent long ago, a camera shot cancelled) go after a day; thumbnails after a month. */
    private fun prune() {
        val now = System.currentTimeMillis()
        listOf(prepared to TimeUnit.DAYS.toMillis(1), camera to TimeUnit.DAYS.toMillis(1), thumbs to TimeUnit.DAYS.toMillis(THUMB_DAYS))
            .forEach { (dir, age) -> dir.listFiles()?.filter { it.isFile && now - it.lastModified() > age }?.forEach { it.delete() } }
    }

    companion object {
        private const val PREPARED_DIR = "attachments/prepared"
        private const val CAMERA_DIR = "camera"
        private const val THUMBS_DIR = "attachments/thumbs"
        private const val THUMB_QUALITY = 80
        private const val THUMB_DAYS = 30L
        private val HANDLE = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val CAMERA_NAME = Regex("[0-9a-f-]{36}\\.jpg")

        /** Where the thumbnail of the image whose bytes hash to [sha256] is kept (the bubbles read it too). */
        fun thumbFile(cacheDir: File, sha256: String): File = File(File(cacheDir, THUMBS_DIR), "$sha256.jpg")

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /**
         * All of the stream, or null when it is longer than [limit] bytes (nothing past [limit] + 1 is read). Read
         * straight into an array of [sizeHint] bytes when the provider told the size, so no second copy is made.
         */
        private fun InputStream.readAtMost(limit: Long, sizeHint: Long? = null): ByteArray? {
            var buf = ByteArray(sizeHint?.takeIf { it in 1..limit }?.toInt() ?: BUFFER)
            var n = 0
            while (true) {
                if (n == buf.size) {
                    val next = read()
                    if (next < 0) return buf
                    if (n + 1L > limit) return null
                    buf = buf.copyOf(minOf(limit + 1, maxOf(buf.size * 2L, n + 1L)).toInt())
                    buf[n++] = next.toByte()
                    continue
                }
                val r = read(buf, n, buf.size - n)
                if (r < 0) return if (n == buf.size) buf else buf.copyOf(n)
                n += r
                if (n > limit) return null
            }
        }

        /** Up to [count] leading bytes (fewer for a shorter stream). */
        private fun InputStream.readHead(count: Int): ByteArray {
            val head = ByteArray(count)
            var n = 0
            while (n < count) {
                val r = read(head, n, count - n)
                if (r < 0) break
                n += r
            }
            return head.copyOf(n)
        }

        private const val BUFFER = 64 * 1024
    }
}
