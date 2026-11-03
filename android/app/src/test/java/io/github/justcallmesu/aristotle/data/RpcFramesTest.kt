package io.github.justcallmesu.aristotle.data

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RpcFramesTest {
    @Test
    fun requestIsJsonRpc2WithIntegerId() {
        val text = RpcFrames.request(7, "session.list", buildJsonObject { put("limit", 50) })
        val obj = HermesJson.parseToJsonElement(text).jsonObject
        assertEquals("2.0", obj["jsonrpc"]!!.jsonPrimitive.content)
        assertEquals(7L, obj["id"]!!.jsonPrimitive.content.toLong())
        assertEquals("session.list", obj["method"]!!.jsonPrimitive.content)
        assertEquals(50, obj["params"]!!.jsonObject["limit"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun classifiesEventsResponsesErrorsAndServerRequests() {
        val ready = RpcFrames.parse("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"heartbeat":true}}}""")
        assertEquals("gateway.ready", (ready as InboundFrame.Event).type)
        val changed = RpcFrames.parse("""{"jsonrpc":"2.0","method":"event","params":{"type":"sessions.changed","session_id":"","payload":{}}}""")
        assertEquals(InboundFrame.Event("sessions.changed", "", JsonObject(emptyMap())), changed)

        // A session event carries its stream position and admission (protocol §2).
        val delta = RpcFrames.parse(
            """{"jsonrpc":"2.0","method":"event","params":{"authority_epoch":1,"execution_generation":6,"admission_id":"7ba9",""" +
                """"type":"message.delta","session_id":"local-1","payload":{"text":"one"},"replay_epoch":"509e","seq":138}}""",
        )
        assertEquals(
            InboundFrame.Event("message.delta", "local-1", JsonObject(mapOf("text" to JsonPrimitive("one"))), seq = 138, replayEpoch = "509e", admissionId = "7ba9"),
            delta,
        )
        val ok = RpcFrames.parse("""{"jsonrpc":"2.0","id":3,"result":{"sessions":[]}}""") as InboundFrame.Response
        assertEquals(3L, ok.id)
        assertNull(ok.error)

        val err = RpcFrames.parse("""{"jsonrpc":"2.0","id":4,"error":{"code":4001,"message":"profile_mismatch"}}""") as InboundFrame.Response
        assertEquals(RpcError(4001, "profile_mismatch"), err.error)

        assertEquals(InboundFrame.ServerRequest("srq-0123456789ab", "sudo"),
            RpcFrames.parse("""{"jsonrpc":"2.0","id":"srq-0123456789ab","method":"sudo","params":{}}"""))
        assertEquals(InboundFrame.Unknown, RpcFrames.parse("not json"))
        // Our call ids are numbers: a string id "5" must not answer pending call 5.
        assertEquals(InboundFrame.Unknown, RpcFrames.parse("""{"jsonrpc":"2.0","id":"5","result":{}}"""))
        assertEquals(InboundFrame.Unknown, RpcFrames.parse("[1,2]"))
    }

    @Test
    fun sessionListDecodesTheCapturedShapeIncludingNulls() {
        val result = HermesJson.parseToJsonElement(
            """{"sessions":[
                {"session_id":"local-42f9","id":"local-42f9","resolved_id":"local-42f9","title":"aristotle-spike",
                 "root_title":"aristotle-spike","source":"gui","started_at":1793674036.824036,"message_count":0,"running":true},
                {"session_id":"local-1","id":"local-1","title":null,"source":"desktop","started_at":null,"message_count":null,
                 "model":"x","running":false}
              ],"scope":"live"}""",
        )
        val sessions = HermesJson.decodeFromJsonElement(SessionListResult.serializer(), result).sessions
        assertEquals(2, sessions.size)
        assertEquals("aristotle-spike", sessions[0].title)
        assertTrue(sessions[0].running)
        assertEquals(1793674036.824036, sessions[0].lastActivitySeconds!!, 0.0)
        assertEquals("", sessions[1].title)
        assertEquals(0, sessions[1].messageCount)
        assertNull(sessions[1].lastActivitySeconds)
    }

    @Test
    fun profileNameComesFromTheProfileHomePath() {
        assertEquals("default", Profiles.nameOf("/home/justcallmesu/.hermes"))
        assertEquals("pm", Profiles.nameOf("/home/justcallmesu/.hermes/profiles/pm"))
        assertEquals("pm", Profiles.nameOf("/home/justcallmesu/.hermes/profiles/pm/"))
        assertNull(Profiles.nameOf(""))
    }
}

class RelayRpcTest {
    @Test
    fun callIsAnsweredByTheResponseWithTheSameId() = runBlocking {
        val sent = mutableListOf<String>()
        val rpc = RelayRpc({ sent.add(it) })
        val call = async { rpc.call("runtime.describe") }
        while (sent.isEmpty()) yield()
        val id = HermesJson.parseToJsonElement(sent.single()).jsonObject["id"]!!.jsonPrimitive.content.toLong()
        rpc.onResponse(InboundFrame.Response(id + 100, JsonPrimitive("wrong"), null))
        rpc.onResponse(InboundFrame.Response(id, JsonPrimitive("right"), null))
        assertEquals(JsonPrimitive("right"), call.await())
    }

    @Test
    fun errorsClosedSocketsAndTimeoutsBecomeRpcExceptions() = runBlocking {
        val rpc = RelayRpc({ true }, timeoutMillis = 100)
        val failing = async { runCatching { rpc.call("session.list") }.exceptionOrNull() }
        yield()
        rpc.onResponse(InboundFrame.Response(1, null, RpcError(5006, "storage")))
        assertEquals(5006, (failing.await() as RpcException).error?.code)

        val timedOut = runCatching { rpc.call("session.list") }.exceptionOrNull()
        assertTrue(timedOut is RpcException)

        val closed = RelayRpc({ false })
        assertThrows(RpcException::class.java) { runBlocking { closed.call("session.list") } }

        val dropped = RelayRpc({ true })
        val pending = async { runCatching { dropped.call("session.list") }.exceptionOrNull() }
        yield()
        dropped.failAll("relay closed")
        assertTrue(pending.await() is RpcException)
    }
}
