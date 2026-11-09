package io.github.justcallmesu.aristotle.data.update

import io.github.justcallmesu.aristotle.data.ConnectionProblem
import io.github.justcallmesu.aristotle.data.DeviceToken
import io.github.justcallmesu.aristotle.data.PcCredentials
import io.github.justcallmesu.aristotle.data.ServerEndpoint
import io.github.justcallmesu.aristotle.data.await
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Why a download stopped (UP2). */
enum class DownloadProblem {
    Unreachable, CertificateChanged, BadStatus, TooLarge, Truncated, Storage;

    companion object {
        fun of(problem: ConnectionProblem): DownloadProblem = when (problem) {
            ConnectionProblem.Unreachable -> Unreachable
            ConnectionProblem.CertificateChanged -> CertificateChanged
        }
    }
}

sealed interface DownloadResult {
    /** Every announced byte arrived; [sha256]: the file's checksum, lowercase hex. */
    data class Done(val sha256: String) : DownloadResult
    data class Failed(val problem: DownloadProblem) : DownloadResult
}

/** The updater's two calls to the PC (protocol §11, P17); [io.github.justcallmesu.aristotle.data.HttpLinkDialer] in the app. */
interface AppUpdateSource {
    suspend fun latestApp(pc: PcCredentials, endpoint: ServerEndpoint, ownVersionCode: Int): UpdateCheck

    /** Downloads the APK into [target]. The caller deletes [target] on any result but a verified [DownloadResult.Done]. */
    suspend fun downloadApp(pc: PcCredentials, endpoint: ServerEndpoint, update: AppUpdate, target: File, onProgress: (Long) -> Unit): DownloadResult
}

/** Copies the APK while hashing it, never past the announced size (UP2: the size is the cap). Pure JVM. */
object ApkStream {
    const val BUFFER = 64 * 1024

    class Refused(val problem: DownloadProblem) : IOException(problem.name)

    /**
     * Copies [input] to [output] and returns the SHA-256 (lowercase hex) of what was copied. More than [size] bytes is
     * [DownloadProblem.TooLarge] (nothing past [size] is written), fewer is [DownloadProblem.Truncated], a failed write is
     * [DownloadProblem.Storage]. [checkCancelled] runs before each chunk and may throw.
     */
    fun copy(input: InputStream, output: OutputStream, size: Long, onProgress: (Long) -> Unit, checkCancelled: () -> Unit = {}): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER)
        var total = 0L
        while (true) {
            checkCancelled()
            val read = input.read(buffer)
            if (read < 0) break
            if (total + read > size) throw Refused(DownloadProblem.TooLarge)
            try {
                output.write(buffer, 0, read)
            } catch (_: IOException) {
                throw Refused(DownloadProblem.Storage)
            }
            digest.update(buffer, 0, read)
            total += read
            onProgress(total)
        }
        if (total != size) throw Refused(DownloadProblem.Truncated)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** `GET /v1/app/update` (P17) with the device token over this pinned client; any failure below HTTP is [UpdateCheck.Failed]. */
internal suspend fun OkHttpClient.appUpdate(baseUrl: String, token: DeviceToken, ownVersionCode: Int): UpdateCheck {
    val request = authorized(baseUrl, "v1/app/update", token)
    return await(request, { UpdateCheck.Failed }) { response ->
        AppUpdateRules.check(response.code, if (response.code == 200) response.body.string() else null, ownVersionCode)
    }
}

/**
 * `GET /v1/app/apk` (P17) into [target], streamed and hashed ([ApkStream]). A declared length above [AppUpdate.size] is
 * refused before a byte is read. Cancelling the caller cancels the call, which ends the stream at once ([await]).
 */
internal suspend fun OkHttpClient.downloadApk(baseUrl: String, token: DeviceToken, update: AppUpdate, target: File, onProgress: (Long) -> Unit): DownloadResult {
    val request = authorized(baseUrl, "v1/app/apk", token)
    return await(request, { DownloadResult.Failed(DownloadProblem.of(it)) }) { response ->
        when {
            response.code != 200 -> DownloadResult.Failed(DownloadProblem.BadStatus)
            response.body.contentLength() > update.size -> DownloadResult.Failed(DownloadProblem.TooLarge)
            else -> try {
                val sha = openTarget(target).use { out -> ApkStream.copy(response.body.byteStream(), out, update.size, onProgress) }
                DownloadResult.Done(sha)
            } catch (e: ApkStream.Refused) {
                DownloadResult.Failed(e.problem)
            }
        }
    }
}

private fun openTarget(target: File): OutputStream = try {
    target.outputStream()
} catch (_: IOException) {
    throw ApkStream.Refused(DownloadProblem.Storage)
}

private fun authorized(baseUrl: String, path: String, token: DeviceToken): Request =
    Request.Builder().url(baseUrl.toHttpUrl().newBuilder().addPathSegments(path).build()).header("Authorization", "Bearer ${token.value}").build()
