package io.github.justcallmesu.aristotle.data

import io.github.justcallmesu.aristotle.data.stocks.StocksReply
import io.github.justcallmesu.aristotle.data.stocks.StocksRequest
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `/v1/stocks/…` over the PC's pinned client: the device token, the path and query, and every status handed back. */
class StocksTransportTest {
    private val held = lanCertificate()
    private val server: MockWebServer = startServer(held)
    private val endpoint = localEndpoint(server)
    private val client = testClients().pinnedTo(pinOf(held), Network.Lan)
    private val token = DeviceToken("t0k3n")

    @After
    fun stop() = server.close()

    @Test
    fun getSendsTheTokenAndQuery() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("""{"results":[]}""").build())
        val reply = client.sendStocks(endpoint.baseUrl, token, StocksRequest("GET", "search", mapOf("q" to "bank bca")))
        assertEquals(StocksReply.Http(200, """{"results":[]}"""), reply)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/stocks/search", request.url.encodedPath)
        assertEquals("bank bca", request.url.queryParameter("q"))
        assertEquals("Bearer t0k3n", request.headers["Authorization"])
    }

    @Test
    fun putWithAndWithoutBodyAndDelete() = runBlocking {
        repeat(3) { server.enqueue(MockResponse.Builder().code(200).body("{}").build()) }
        client.sendStocks(endpoint.baseUrl, token, StocksRequest("PUT", "holdings/BBCA", body = """{"lots":10,"avg_price":4800}"""))
        client.sendStocks(endpoint.baseUrl, token, StocksRequest("PUT", "watchlist/BBCA"))
        client.sendStocks(endpoint.baseUrl, token, StocksRequest("DELETE", "watchlist/BBCA"))
        val put = server.takeRequest()
        assertEquals("/v1/stocks/holdings/BBCA", put.url.encodedPath)
        assertEquals("""{"lots":10,"avg_price":4800}""", put.body!!.utf8())
        assertTrue(put.headers["Content-Type"]!!.startsWith("application/json"))
        val empty = server.takeRequest()
        assertEquals("PUT", empty.method)
        assertEquals("", empty.body?.utf8().orEmpty())
        assertEquals("DELETE", server.takeRequest().method)
    }

    @Test
    fun errorStatusesComeBackWithTheirBodyAndRetryAfter() = runBlocking {
        server.enqueue(MockResponse.Builder().code(503).setHeader("Retry-After", "60").body("""{"error":"upstream_blocked","retry_after_s":60}""").build())
        val reply = client.sendStocks(endpoint.baseUrl, token, StocksRequest("GET", "quotes"))
        assertEquals(StocksReply.Http(503, """{"error":"upstream_blocked","retry_after_s":60}""", 60), reply)
    }

    @Test
    fun noServerIsAFailureBelowHttp() = runBlocking {
        server.close()
        val reply = client.sendStocks(endpoint.baseUrl, token, StocksRequest("GET", "quotes"))
        assertEquals(StocksReply.Failed(ConnectionProblem.Unreachable), reply)
    }
}
