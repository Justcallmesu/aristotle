package io.github.justcallmesu.aristotle.data

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerClientTest {
    private val held = lanCertificate()
    private val server: MockWebServer = startServer(held)
    private val endpoint = localEndpoint(server)
    private val client = ServerClient(testClients().pinnedTo(pinOf(held), Network.Lan), endpoint.baseUrl)

    @After
    fun stop() = server.close()

    private fun respond(code: Int, body: String = "") = server.enqueue(MockResponse.Builder().code(code).body(body).build())

    @Test
    fun pairSendsTheNormalisedCodeInTheBodyAndReturnsTheToken() = runBlocking {
        respond(201, """{"device_id":"dev_0123456789ab","token":"t0k3n-t0k3n"}""")
        val result = client.pair("k7q2-m9xd", " Poco ")
        assertEquals(PairResult.Paired("dev_0123456789ab", DeviceToken("t0k3n-t0k3n"), emptyList()), result)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/pair", request.url.encodedPath)
        assertNull(request.url.query)
        assertNull(request.headers["Authorization"])
        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals("K7Q2M9XD", body["code"]!!.jsonPrimitive.content)
        assertEquals("Poco", body["device_name"]!!.jsonPrimitive.content)
    }

    @Test
    fun blankDeviceNameIsLeftOut() = runBlocking {
        respond(201, """{"device_id":"dev_1","token":"x"}""")
        client.pair("K7Q2M9XD", "  ")
        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertTrue("device_name" !in body)
    }

    @Test
    fun pairErrorStatusesMapToTheirStates() = runBlocking {
        respond(401, """{"error":"invalid or expired pairing code"}""")
        assertEquals(PairResult.InvalidCode, client.pair("K7Q2M9XD", ""))
        respond(403, "forbidden")
        assertEquals(PairResult.Forbidden, client.pair("K7Q2M9XD", ""))
        respond(500, """{"error":"could not store the device; run pair again"}""")
        assertEquals(PairResult.ServerError, client.pair("K7Q2M9XD", ""))
        respond(400, """{"error":"code is required"}""")
        assertEquals(PairResult.Rejected(400), client.pair("K7Q2M9XD", ""))
        respond(201, "not json")
        assertEquals(PairResult.Rejected(201), client.pair("K7Q2M9XD", ""))
    }

    // T09: the pair response lists every PC address; only HTTPS private-range ones of the named kind are kept.
    @Test
    fun pairReturnsThePcAddresses() = runBlocking {
        respond(
            201,
            """{"device_id":"dev_1","token":"x","addresses":[
                {"url":"https://192.168.1.61:8760","kind":"lan","iface":"wlan0"},
                {"url":"https://[fd7a:115c:a1e0::d628:3d61]:8760","kind":"tailscale","iface":"tailscale0"},
                {"url":"https://100.74.61.96:8760","kind":"tailscale","iface":"tailscale0"},
                {"url":"http://10.0.0.2:8760","kind":"lan","iface":"eth0"},
                {"url":"https://8.8.8.8:8760","kind":"lan","iface":"eth0"},
                {"url":"https://100.74.61.97:8760","kind":"lan","iface":"eth0"}]}""",
        )
        val paired = client.pair("K7Q2M9XD", "") as PairResult.Paired
        val expected = listOf("https://192.168.1.61:8760", "https://[fd7a:115c:a1e0::d628:3d61]:8760", "https://100.74.61.96:8760")
        assertEquals(expected.map { (ServerAddress.parse(it) as AddressCheck.Ok).endpoint }, paired.addresses)
        assertEquals(listOf(Network.Lan, Network.Tailscale, Network.Tailscale), paired.addresses.map { it.network })
    }

    @Test
    fun addressesSendsTheBearerTokenAndReadsTheList() = runBlocking {
        respond(200, """{"addresses":[{"url":"https://100.74.61.96:8760","kind":"tailscale","iface":"tailscale0"}]}""")
        val list = client.addresses(DeviceToken("secret-token"))
        assertEquals(listOf(ServerEndpoint("100.74.61.96", 8760, Network.Tailscale)), list)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/addresses", request.url.encodedPath)
        assertEquals("Bearer secret-token", request.headers["Authorization"])
    }

    @Test
    fun addressesIsNullWhenRefusedOrUnreadable() = runBlocking {
        respond(401, """{"error":"unauthorized"}""")
        assertNull(client.addresses(DeviceToken("revoked")))
        respond(200, "not json")
        assertNull(client.addresses(DeviceToken("t")))
    }

    @Test
    fun meRecordsTheServerBlock() = runBlocking {
        ServerVersions.clearAll()
        respond(200, """{"terminal":{"enabled":true,"granted":true},"server":{"version":"1.2.3","protocol":2,"min_phone_protocol":1}}""")
        assertEquals(true, client.terminalAllowed(DeviceToken("t"), "pc1"))
        assertEquals(ServerInfo("1.2.3", 2, 1), ServerVersions.of("pc1"))
    }

    @Test
    fun meWithoutAServerBlockIsAnOldServer() = runBlocking {
        ServerVersions.clearAll()
        respond(200, """{"device_id":"d","name":"n"}""")
        client.terminalAllowed(DeviceToken("t"), "pc1")
        assertEquals(ServerInfo.OLD, ServerVersions.of("pc1"))
        assertNull(ServerInfo.OLD.version)
    }

    // T09 review 2: cancelling the caller cancels the HTTP call at once; the PC sees the connection end.
    @Test
    fun cancellingTheCallerAbandonsTheRequestAtOnce() = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { silent ->
            val accepted = CompletableDeferred<Socket>()
            thread(isDaemon = true) { runCatching { accepted.complete(silent.accept()) } }
            val client = ServerClient(testClients().pinnedTo(pinOf(held), Network.Lan), "https://127.0.0.1:${silent.localPort}")
            val call = async(Dispatchers.Default) { client.addresses(DeviceToken("t")) }
            withTimeout(5_000) { accepted.await() }.use { peer ->
                awaitClientHello(peer)
                val started = System.nanoTime()
                call.cancelAndJoin()
                val millis = (System.nanoTime() - started) / 1_000_000
                assertTrue("cancel took $millis ms", millis < 1_000)
                assertTrue("the phone kept the connection open", endsWithin(peer, millis = 3_000))
            }
        }
    }

    @Test
    fun unreachableServer() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val gone = ServerEndpoint("127.0.0.1", port, Network.Lan)
        val offline = ServerClient(testClients().pinnedTo(pinOf(held), Network.Lan), gone.baseUrl)
        assertEquals(PairResult.Failed(ConnectionProblem.Unreachable), offline.pair("K7Q2M9XD", ""))
        assertNull(offline.addresses(DeviceToken("t")))
    }
}
