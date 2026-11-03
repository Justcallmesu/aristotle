package io.github.justcallmesu.aristotle.data

import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
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
    private val client = ServerClient(testClients().pinnedTo(pinOf(held)), endpoint.baseUrl)

    @After
    fun stop() = server.close()

    private fun respond(code: Int, body: String = "") = server.enqueue(MockResponse.Builder().code(code).body(body).build())

    @Test
    fun pairSendsTheNormalisedCodeInTheBodyAndReturnsTheToken() = runBlocking {
        respond(201, """{"device_id":"dev_0123456789ab","token":"t0k3n-t0k3n"}""")
        val result = client.pair("k7q2-m9xd", " Poco ")
        assertEquals(PairResult.Paired("dev_0123456789ab", DeviceToken("t0k3n-t0k3n")), result)
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

    @Test
    fun meSendsTheBearerToken() = runBlocking {
        respond(200, """{"device_id":"dev_1","name":"poco"}""")
        assertEquals(MeResult.Ok("dev_1", "poco"), client.me(DeviceToken("secret-token")))
        val request = server.takeRequest()
        assertEquals("/v1/me", request.url.encodedPath)
        assertEquals("Bearer secret-token", request.headers["Authorization"])
    }

    @Test
    fun meUnauthorizedAndForbidden() = runBlocking {
        respond(401, """{"error":"unauthorized"}""")
        assertEquals(MeResult.Unauthorized, client.me(DeviceToken("revoked")))
        respond(403, "forbidden")
        assertEquals(MeResult.Forbidden, client.me(DeviceToken("t")))
    }

    @Test
    fun unreachableServer() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val gone = ServerEndpoint("127.0.0.1", port, Network.Lan)
        val offline = ServerClient(testClients().pinnedTo(pinOf(held)), gone.baseUrl)
        assertEquals(PairResult.Failed(ConnectionProblem.Unreachable), offline.pair("K7Q2M9XD", ""))
        assertEquals(MeResult.Failed(ConnectionProblem.Unreachable), offline.me(DeviceToken("t")))
    }
}
