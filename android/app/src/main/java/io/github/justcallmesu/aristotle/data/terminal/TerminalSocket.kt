package io.github.justcallmesu.aristotle.data.terminal

import io.github.justcallmesu.aristotle.data.ConnectionProblem
import io.github.justcallmesu.aristotle.data.DeviceToken
import io.github.justcallmesu.aristotle.data.connectionProblemOf
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * What the terminal socket reports, on OkHttp's reading thread. Implementations must return at once: that thread also
 * answers the server's pings (protocol §12/§14 keepalive), so [onOutput] only hands the bytes to a bounded queue.
 */
interface TerminalEvents {
    fun onOpen()

    /** A binary frame: PTY output, unchanged (UTF-8 may be split across frames). */
    fun onOutput(bytes: ByteArray)
    fun onControl(control: TerminalControl)

    /** The server's close frame. */
    fun onClosed(code: Int, reason: String)

    /** No close frame: [opened] false = the upgrade failed ([httpStatus], [errorBody] of a 4xx); true = the link broke. */
    fun onFailed(opened: Boolean, httpStatus: Int?, errorBody: String?, problem: ConnectionProblem)
}

/** One terminal socket; [TerminalSocket] in the app, a fake in tests. Single use: never reopened (Q55). */
interface TerminalChannel {
    fun open(events: TerminalEvents)

    /** A binary frame (keystrokes or one paste chunk); false when the socket is not open. */
    fun send(bytes: ByteArray): Boolean

    /** A text control frame (`resize`). */
    fun sendText(text: String): Boolean

    /** Ends it with `1000`: the server ends the shell (protocol §14 No reattach). */
    fun close()

    /** Drops it without the close handshake: the network it ran on is gone. */
    fun cancel()
}

/**
 * The phone's terminal socket `wss://…/v1/terminal` (protocol §14) on [client], the PC's pinned client for the
 * address the link uses (same pin, same token, its own socket: never the relay). No `pingInterval`: the server pings.
 * Nothing here logs; frames go straight to [TerminalEvents].
 */
class TerminalSocket(private val client: OkHttpClient, baseUrl: String, private val token: DeviceToken) : TerminalChannel {
    private val url = baseUrl.toHttpUrl().resolve("/v1/terminal")!!

    @Volatile
    private var socket: WebSocket? = null
    private val opened = AtomicBoolean(false)
    private val used = AtomicBoolean(false)

    override fun open(events: TerminalEvents) {
        check(used.compareAndSet(false, true)) { "a terminal socket is opened once" }
        val request = Request.Builder().url(url).header("Authorization", "Bearer ${token.value}").build()
        socket = client.newWebSocket(request, Listener(events))
    }

    override fun send(bytes: ByteArray): Boolean = opened.get() && socket?.send(bytes.toByteString()) == true

    override fun sendText(text: String): Boolean = opened.get() && socket?.send(text) == true

    override fun close() {
        val ws = socket ?: return
        if (opened.get()) ws.close(NORMAL_CLOSURE, null) else ws.cancel()
    }

    override fun cancel() {
        socket?.cancel()
    }

    private inner class Listener(private val events: TerminalEvents) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened.set(true)
            events.onOpen()
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = events.onOutput(bytes.toByteArray())

        override fun onMessage(webSocket: WebSocket, text: String) = events.onControl(TerminalFrames.control(text))

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSURE, null)
            events.onClosed(code, reason)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // A refused upgrade's JSON error (`terminal_disabled`, …); a short peek, never logged.
            val body = response?.let { runCatching { it.peekBody(MAX_ERROR_BODY).string() }.getOrNull() }
            events.onFailed(opened.get(), response?.code, body, connectionProblemOf(t))
        }
    }

    private companion object {
        const val NORMAL_CLOSURE = 1000
        const val MAX_ERROR_BODY = 512L
    }
}
