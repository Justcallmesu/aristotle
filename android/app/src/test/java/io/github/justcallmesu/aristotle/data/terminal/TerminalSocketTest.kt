package io.github.justcallmesu.aristotle.data.terminal

import io.github.justcallmesu.aristotle.data.ConnectionProblem
import io.github.justcallmesu.aristotle.data.DeviceToken
import io.github.justcallmesu.aristotle.data.Network
import io.github.justcallmesu.aristotle.data.ServerClient
import io.github.justcallmesu.aristotle.data.lanCertificate
import io.github.justcallmesu.aristotle.data.localEndpoint
import io.github.justcallmesu.aristotle.data.pinOf
import io.github.justcallmesu.aristotle.data.startServer
import io.github.justcallmesu.aristotle.data.testClients
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The terminal socket over the pinned TLS client, against a local WebSocket server (never a real PC). */
class TerminalSocketTest {
    private val held = lanCertificate()
    private val server: MockWebServer = startServer(held)
    private val clients = testClients().pinnedTo(pinOf(held), Network.Lan)

    @After
    fun stop() = server.close()

    private class Recorder : TerminalEvents {
        val output = CopyOnWriteArrayList<String>()
        val controls = CopyOnWriteArrayList<TerminalControl>()
        var opened = false
        var closed: Pair<Int, String>? = null
        var failed: List<Any?>? = null
        val done = CountDownLatch(1)

        override fun onOpen() {
            opened = true
        }

        override fun onOutput(bytes: ByteArray) {
            output += String(bytes)
        }

        override fun onControl(control: TerminalControl) {
            controls += control
        }

        override fun onClosed(code: Int, reason: String) {
            closed = code to reason
            done.countDown()
        }

        override fun onFailed(opened: Boolean, httpStatus: Int?, errorBody: String?, problem: ConnectionProblem) {
            failed = listOf(opened, httpStatus, errorBody, problem)
            done.countDown()
        }
    }

    private fun socket(token: String = "tok") = TerminalSocket(clients, localEndpoint(server).baseUrl, DeviceToken(token))

    @Test
    fun binaryBothWaysThenTheExitAndItsClose() {
        val fromPhone = CopyOnWriteArrayList<Any>()
        val phoneDone = CountDownLatch(2)
        server.enqueue(
            MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("banner\r\n".toByteArray().toByteString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    fromPhone += text
                    phoneDone.countDown()
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    fromPhone += bytes.utf8()
                    phoneDone.countDown()
                    webSocket.send("""{"type":"exit","code":7}""")
                    webSocket.close(1000, "exit")
                }
            }).build(),
        )
        val socket = socket("secret")
        val events = Recorder()
        socket.open(object : TerminalEvents by events {
            override fun onOpen() {
                events.onOpen()
                socket.sendText(TerminalFrames.resize(TermSize(90, 30)))
                socket.send("ls\r".toByteArray())
            }
        })
        assertTrue(events.done.await(10, TimeUnit.SECONDS))
        assertTrue(phoneDone.await(10, TimeUnit.SECONDS))
        assertTrue(events.opened)
        assertEquals(listOf("banner\r\n"), events.output)
        assertEquals(listOf<TerminalControl>(TerminalControl.Exit(7)), events.controls)
        assertEquals(1000 to "exit", events.closed)
        assertEquals(listOf<Any>("""{"type":"resize","cols":90,"rows":30}""", "ls\r"), fromPhone)
        val upgrade = server.takeRequest()
        assertEquals("/v1/terminal", upgrade.url.encodedPath)
        assertEquals("Bearer secret", upgrade.headers["Authorization"])
        assertNull(upgrade.url.query)
    }

    @Test
    fun aRefusedUpgradeReportsItsStatusAndJsonError() {
        server.enqueue(MockResponse.Builder().code(403).body("""{"error":"terminal_disabled"}""").build())
        val events = Recorder()
        socket().open(events)
        assertTrue(events.done.await(10, TimeUnit.SECONDS))
        assertEquals(listOf(false, 403, """{"error":"terminal_disabled"}""", ConnectionProblem.Unreachable), events.failed)
        val failed = events.failed!!
        assertEquals(TerminalEnd.TurnedOff, TerminalEnd.ofFailure(failed[0] as Boolean, failed[1] as Int?, failed[2] as String?, failed[3] as ConnectionProblem))
    }

    @Test
    fun theServersCloseCodeReachesThePhone() {
        server.enqueue(
            MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"type":"closing","reason":"terminal_not_granted"}""")
                    webSocket.close(4403, "terminal_not_granted")
                }
            }).build(),
        )
        val events = Recorder()
        socket().open(events)
        assertTrue(events.done.await(10, TimeUnit.SECONDS))
        assertEquals(4403 to "terminal_not_granted", events.closed)
        assertEquals(listOf<TerminalControl>(TerminalControl.Closing("terminal_not_granted")), events.controls)
    }

    @Test
    fun meSaysWhetherTheTerminalMayOpen() = runBlocking {
        val client = ServerClient(clients, localEndpoint(server).baseUrl)
        fun me(body: String, code: Int = 200) = server.enqueue(MockResponse.Builder().code(code).body(body).build())
        me("""{"device_id":"dev_1","name":"Poco","terminal":{"enabled":true,"granted":true}}""")
        assertEquals(true, client.terminalAllowed(DeviceToken("secret")))
        me("""{"device_id":"dev_1","name":"Poco","terminal":{"enabled":true,"granted":false}}""")
        assertEquals(false, client.terminalAllowed(DeviceToken("t")))
        me("""{"device_id":"dev_1","name":"Poco","terminal":{"enabled":false,"granted":true}}""")
        assertEquals(false, client.terminalAllowed(DeviceToken("t")))
        me("""{"device_id":"dev_1","name":"Poco"}""") // a server from before T19a
        assertEquals(false, client.terminalAllowed(DeviceToken("t")))
        me("""{"error":"unauthorized"}""", code = 401)
        assertNull(client.terminalAllowed(DeviceToken("t")))
        me("not json")
        assertNull(client.terminalAllowed(DeviceToken("t")))
        val request = server.takeRequest()
        assertEquals("/v1/me", request.url.encodedPath)
        assertEquals("Bearer secret", request.headers["Authorization"])
    }
}
