package io.github.justcallmesu.aristotle.data

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

sealed interface RelayEvent {
    data object Opened : RelayEvent
    data class Text(val text: String) : RelayEvent

    /** The server sent a close frame (docs/protocol.md §12 close codes). */
    data class Closed(val code: Int, val reason: String) : RelayEvent

    /** No close frame: refused upgrade ([httpStatus], e.g. 401) or a transport failure. */
    data class Failed(val httpStatus: Int?, val problem: ConnectionProblem) : RelayEvent
}

/** A relay socket as the session feed sees it; [RelaySocket] in the app, a fake in tests. */
interface RelayChannel {
    /** Opens on collect; cancelling the collector closes the socket. */
    fun events(): Flow<RelayEvent>

    /** False when the socket is not open (or already closing). */
    fun send(text: String): Boolean
}

/**
 * The phone's relay socket `/v1/ws` (protocol §12). No `pingInterval`: the server pings, and a
 * client ping timeout could cut a large message mid-download. Reconnecting is the caller's call.
 */
class RelaySocket(private val client: OkHttpClient, baseUrl: String, private val token: DeviceToken) : RelayChannel {
    private val url = baseUrl.toHttpUrl().resolve("/v1/ws")!!

    @Volatile
    private var socket: WebSocket? = null

    /** Opens on collect; cancelling the collector closes the socket with 1000. */
    override fun events(): Flow<RelayEvent> = callbackFlow {
        val request = Request.Builder().url(url).header("Authorization", "Bearer ${token.value}").build()
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                trySend(RelayEvent.Opened)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                trySend(RelayEvent.Text(text))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(NORMAL_CLOSURE, null)
                trySend(RelayEvent.Closed(code, reason))
                channel.close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                trySend(RelayEvent.Failed(response?.code, connectionProblemOf(t)))
                channel.close()
            }
        })
        socket = ws
        awaitClose {
            socket = null
            ws.close(NORMAL_CLOSURE, null)
        }
    }.buffer(Channel.UNLIMITED)

    override fun send(text: String): Boolean = socket?.send(text) ?: false

    private companion object {
        const val NORMAL_CLOSURE = 1000
    }
}
