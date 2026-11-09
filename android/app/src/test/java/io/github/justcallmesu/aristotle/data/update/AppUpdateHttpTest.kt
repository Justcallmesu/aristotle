package io.github.justcallmesu.aristotle.data.update

import io.github.justcallmesu.aristotle.data.ConnectionProblem
import io.github.justcallmesu.aristotle.data.DeviceToken
import io.github.justcallmesu.aristotle.data.Network
import io.github.justcallmesu.aristotle.data.lanCertificate
import io.github.justcallmesu.aristotle.data.localEndpoint
import io.github.justcallmesu.aristotle.data.pinOf
import io.github.justcallmesu.aristotle.data.startServer
import io.github.justcallmesu.aristotle.data.testClients
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** UP2 (P17): `/v1/app/update` and `/v1/app/apk` over the PC's pinned client with the device token. */
class AppUpdateHttpTest {
    private val held = lanCertificate()
    private val server: MockWebServer = startServer(held)
    private val base = localEndpoint(server).baseUrl
    private val client = testClients().pinnedTo(pinOf(held), Network.Lan)
    private val token = DeviceToken("t0k3n")
    private val dir: File = Files.createTempDirectory("apk").toFile()
    private val target = File(dir, "next.apk")
    private val apk = ByteArray(300_000) { (it * 7).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
    private val update = AppUpdate("2.1.1", 20101, sha, apk.size.toLong())

    @After
    fun stop() {
        server.close()
        dir.deleteRecursively()
    }

    @Test
    fun `the check sends the token and reads the offer`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("""{"version":"2.1.1","version_code":20101,"sha256":"$sha","size":${apk.size}}""").build())
        assertEquals(UpdateCheck.Available(update), client.appUpdate(base, token, ownVersionCode = 20100))
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/app/update", request.url.encodedPath)
        assertEquals("Bearer t0k3n", request.headers["Authorization"])
    }

    @Test
    fun `no content and an old server`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(204).build())
        server.enqueue(MockResponse.Builder().code(404).body("""{"error":"not found"}""").build())
        assertEquals(UpdateCheck.UpToDate, client.appUpdate(base, token, 20100))
        assertEquals(UpdateCheck.Failed, client.appUpdate(base, token, 20100))
    }

    @Test
    fun `no server is a failed check`() = runBlocking {
        server.close()
        assertEquals(UpdateCheck.Failed, client.appUpdate(base, token, 20100))
    }

    @Test
    fun `the download streams into the file and returns its checksum`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).setHeader("ETag", "\"$sha\"").body(Buffer().write(apk)).build())
        val progress = mutableListOf<Long>()
        assertEquals(DownloadResult.Done(sha), client.downloadApk(base, token, update, target, progress::add))
        assertArrayEquals(apk, target.readBytes())
        assertEquals(apk.size.toLong(), progress.last())
        val request = server.takeRequest()
        assertEquals("/v1/app/apk", request.url.encodedPath)
        assertEquals("Bearer t0k3n", request.headers["Authorization"])
    }

    @Test
    fun `a declared length above the announced size is refused before reading`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(apk + ByteArray(10))).build())
        assertEquals(DownloadResult.Failed(DownloadProblem.TooLarge), client.downloadApk(base, token, update, target) {})
    }

    @Test
    fun `an unannounced length that runs past the size is cut off`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).chunkedBody(Buffer().write(apk + ByteArray(100_000)), 8_192).build())
        assertEquals(DownloadResult.Failed(DownloadProblem.TooLarge), client.downloadApk(base, token, update, target) {})
    }

    @Test
    fun `another status is refused`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(404).body("""{"error":"no apk"}""").build())
        assertEquals(DownloadResult.Failed(DownloadProblem.BadStatus), client.downloadApk(base, token, update, target) {})
    }

    @Test
    fun `no server is unreachable`() = runBlocking {
        server.close()
        assertEquals(DownloadResult.Failed(DownloadProblem.of(ConnectionProblem.Unreachable)), client.downloadApk(base, token, update, target) {})
    }
}
