package io.github.justcallmesu.aristotle.data

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * A failed call. [linkClosed]: the relay was gone or went away (the link state reports that, not the caller).
 * [written]: a socket took the request; false only when none did, so Hermes surely never saw it.
 */
class RpcException(val error: RpcError?, message: String, val linkClosed: Boolean = false, val written: Boolean = true) : Exception(message)

/** JSON-RPC calls over the relay: integer ids, responses matched by id (protocol §1.3). */
class RelayRpc(private val send: (String) -> Boolean, private val timeoutMillis: Long = 15_000) {
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<InboundFrame.Response>>()

    /**
     * [timeout]: how long this call waits for its answer; the default is [timeoutMillis]. There is no call queue here:
     * every call is written at once to the one socket (OkHttp writes its messages in order), and each call's own timer
     * runs from its write, so a large upload does not wait for other calls and does not lengthen their timers. What it
     * can do is delay the writes behind it on a slow link, which is why only staging calls get the longer timeout.
     */
    suspend fun call(method: String, params: JsonObject = JsonObject(emptyMap()), timeout: Long = timeoutMillis): JsonElement {
        val id = nextId.getAndIncrement()
        val answer = CompletableDeferred<InboundFrame.Response>()
        pending[id] = answer
        try {
            if (!send(RpcFrames.request(id, method, params))) throw RpcException(null, "$method: socket not open", linkClosed = true, written = false)
            val response = withTimeout(timeout) { answer.await() }
            response.error?.let { throw RpcException(it, "$method failed: ${it.code}") }
            return response.result ?: throw RpcException(null, "$method: empty result")
        } catch (e: TimeoutCancellationException) {
            throw RpcException(null, "$method: no answer in ${timeout / 1000} s").apply { initCause(e) }
        } finally {
            pending.remove(id)
        }
    }

    /** Answers a Hermes -> client request; false when the socket is not open. No reply comes back. */
    fun respond(requestId: String, result: JsonObject): Boolean = send(RpcFrames.response(requestId, result))

    /** Routes a response frame to its waiting call; unknown ids are ignored. */
    fun onResponse(frame: InboundFrame.Response) {
        pending[frame.id]?.complete(frame)
    }

    fun failAll(reason: String) {
        val waiting = pending.values.toList()
        pending.clear()
        waiting.forEach { it.completeExceptionally(RpcException(null, reason, linkClosed = true)) }
    }
}
