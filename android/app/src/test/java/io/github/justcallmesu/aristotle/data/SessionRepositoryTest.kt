package io.github.justcallmesu.aristotle.data

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The session feed against a scripted relay socket: no network, real coroutines, short timings. */
class SessionRepositoryTest {
    private val fast = FeedTimings(readyTimeoutMillis = 2_000, refreshDebounceMillis = 50, rpcTimeoutMillis = 2_000)

    /** A relay socket that answers each RPC with [answer] (method -> `"result":…` or `"error":…`, null = silent). */
    private class FakeRelay(private val answer: (method: String) -> String?) : RelayChannel {
        private val incoming = Channel<RelayEvent>(Channel.UNLIMITED)
        val methods = CopyOnWriteArrayList<String>()

        override fun events(): Flow<RelayEvent> = incoming.receiveAsFlow()

        override fun send(text: String): Boolean {
            val frame = HermesJson.parseToJsonElement(text).jsonObject
            val method = frame["method"]!!.jsonPrimitive.content
            methods += method
            answer(method)?.let { push("""{"jsonrpc":"2.0","id":${frame["id"]},$it}""") }
            return true
        }

        fun push(text: String) = incoming.trySend(RelayEvent.Text(text))
        fun event(type: String) = push("""{"jsonrpc":"2.0","method":"event","params":{"type":"$type","session_id":"","payload":{}}}""")
        fun close(code: Int, reason: String = "") {
            incoming.trySend(RelayEvent.Closed(code, reason))
            incoming.close()
        }
    }

    private class FakeLink(private val relay: FakeRelay, private val meResult: MeResult = MeResult.Ok("dev_1", "poco")) : PcLink {
        var opened = 0
        override suspend fun me(): MeResult = meResult
        override fun openRelay(): RelayChannel = relay.also { opened++ }
    }

    /** Collects one feed; [script] runs on each event as the screen would see it. */
    private fun feed(link: PcLink, script: suspend (SessionFeedEvent) -> Unit = {}): List<SessionFeedEvent> = runBlocking {
        val events = mutableListOf<SessionFeedEvent>()
        withTimeout(10_000) { SessionRepository(link, fast).feed().collect { events += it; script(it) } }
        events
    }

    private fun sessions(vararg titles: String) =
        titles.joinToString(",", """"result":{"sessions":[""", "]}") { """{"session_id":"s-$it","title":"$it"}""" }

    private val describePm = """"result":{"profile_id":"/home/u/.hermes/profiles/pm"}"""

    private fun answers(vararg lists: String): (String) -> String? {
        var next = 0
        return { method -> if (method == "runtime.describe") describePm else lists[minOf(next++, lists.lastIndex)] }
    }

    @Test
    fun readyThenProfileAndListThenARevokedCloseEndsAsRevoked() {
        val relay = FakeRelay(answers(sessions("a")))
        relay.event(RpcFrames.GATEWAY_READY)
        val events = feed(FakeLink(relay)) { if (it is SessionFeedEvent.Sessions) relay.close(4401, "revoked") }
        assertEquals(SessionFeedEvent.Profile("pm"), events[0])
        assertEquals(listOf("a"), (events[1] as SessionFeedEvent.Sessions).sessions.map { it.title })
        assertEquals(listOf(SessionFeedEvent.Ended(LinkOutcome.Revoked)), events.drop(2))
        assertEquals(listOf("runtime.describe", "session.list"), relay.methods)
    }

    @Test
    fun meUnauthorizedEndsAsRevokedWithoutOpeningTheSocket() {
        val link = FakeLink(FakeRelay { null }, MeResult.Unauthorized)
        assertEquals(listOf(SessionFeedEvent.Ended(LinkOutcome.Revoked)), feed(link))
        assertEquals(0, link.opened)
    }

    @Test
    fun noGatewayReadyEndsAfterTheReadyTimeout() {
        val relay = FakeRelay { null }
        val started = System.nanoTime()
        val events = feed(FakeLink(relay))
        assertEquals(
            listOf(SessionFeedEvent.Ended(LinkOutcome.Show(ConnectionIssue.Generic("no gateway.ready within 2 s")))),
            events,
        )
        assertTrue((System.nanoTime() - started) / 1_000_000 >= fast.readyTimeoutMillis)
        assertTrue(relay.methods.isEmpty())
    }

    @Test
    fun sessionsChangedBeforeTheFirstListIsIgnored() {
        val relay = FakeRelay(answers(sessions("first"), sessions("second")))
        relay.event(RpcFrames.SESSIONS_CHANGED)
        relay.event(RpcFrames.GATEWAY_READY)
        feed(FakeLink(relay)) {
            if (it !is SessionFeedEvent.Sessions) return@feed
            delay(fast.refreshDebounceMillis * 4)
            relay.close(1000)
        }
        assertEquals(1, relay.methods.count { it == "session.list" })
    }

    @Test
    fun aBurstOfSessionsChangedRefetchesOnce() {
        val relay = FakeRelay(answers(sessions("first"), sessions("second")))
        relay.event(RpcFrames.GATEWAY_READY)
        var lists = 0
        val events = feed(FakeLink(relay)) {
            if (it !is SessionFeedEvent.Sessions) return@feed
            if (++lists == 1) repeat(3) { relay.event(RpcFrames.SESSIONS_CHANGED) } else relay.close(1000)
        }
        val titles = events.filterIsInstance<SessionFeedEvent.Sessions>().map { it.sessions.single().title }
        assertEquals(listOf("first", "second"), titles)
        assertEquals(2, relay.methods.count { it == "session.list" })
        assertEquals(SessionFeedEvent.Ended(LinkOutcome.Show(ConnectionIssue.Generic("close 1000"))), events.last())
    }

    @Test
    fun aFailedSessionListEndsTheFeed() {
        val relay = FakeRelay(answers(""""error":{"code":5006,"message":"storage"}"""))
        relay.event(RpcFrames.GATEWAY_READY)
        val events = feed(FakeLink(relay))
        assertEquals(SessionFeedEvent.Ended(LinkOutcome.Show(ConnectionIssue.Generic("session.list failed: 5006"))), events.last())
    }

    @Test
    fun hermesNotRunningCloseHasItsOwnPage() {
        val relay = FakeRelay { null }
        relay.close(4503, "hermes_not_running")
        assertEquals(
            listOf(SessionFeedEvent.Ended(LinkOutcome.Show(ConnectionIssue.HermesNotRunning("close 4503 hermes_not_running")))),
            feed(FakeLink(relay)),
        )
    }
}
