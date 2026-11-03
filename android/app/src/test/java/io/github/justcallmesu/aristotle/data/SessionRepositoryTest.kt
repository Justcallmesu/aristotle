package io.github.justcallmesu.aristotle.data

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The session list over a fake process-wide link: no network, real coroutines, short timings. */
class SessionRepositoryTest {
    private val fast = FeedTimings(refreshDebounceMillis = 50)
    private val endpoint = ServerEndpoint("192.168.1.61", 8760, Network.Lan)

    /** A link that answers each call with [answer] (method -> result, or throws). */
    private class FakeLink(private val answer: (method: String) -> JsonElement) : RelayLink {
        override val state = MutableStateFlow<LinkState>(LinkState.Connecting)
        val frames = MutableSharedFlow<InboundFrame>(extraBufferCapacity = 16)
        override val events: SharedFlow<InboundFrame> = frames
        val methods = CopyOnWriteArrayList<String>()

        override suspend fun call(method: String, params: JsonObject): JsonElement {
            methods += method
            return answer(method)
        }

        override fun retryNow() = Unit

        fun sessionsChanged() = frames.tryEmit(InboundFrame.Event(RpcFrames.SESSIONS_CHANGED, "", null))
    }

    private fun sessions(vararg titles: String): JsonElement = HermesJson.parseToJsonElement(
        titles.joinToString(",", """{"sessions":[""", "]}") { """{"session_id":"s-$it","title":"$it"}""" },
    )

    private val describePm = HermesJson.parseToJsonElement("""{"profile_id":"/home/u/.hermes/profiles/pm"}""")

    private fun answers(vararg lists: JsonElement): (String) -> JsonElement {
        var next = 0
        return { method -> if (method == "runtime.describe") describePm else lists[minOf(next++, lists.lastIndex)] }
    }

    /** Collects the feed while [script] drives the link; returns everything the feed sent. */
    private fun run(link: FakeLink, script: suspend (StateFlow<List<SessionFeedEvent>>) -> Unit): List<SessionFeedEvent> =
        runBlocking {
            val seen = MutableStateFlow<List<SessionFeedEvent>>(emptyList())
            val job = launch { SessionRepository(link, fast).feed().collect { seen.value = seen.value + it } }
            withTimeout(10_000) { script(seen) }
            job.cancel()
            seen.value
        }

    private suspend fun StateFlow<List<SessionFeedEvent>>.until(count: Int) = first { it.size >= count }

    @Test
    fun nothingIsListedUntilTheLinkIsOnline() {
        val link = FakeLink(answers(sessions("a")))
        val events = run(link) { seen ->
            delay(100)
            assertTrue(seen.value.isEmpty())
            link.state.value = LinkState.Online(endpoint, 1)
            seen.until(2)
        }
        assertEquals(SessionFeedEvent.Profile("pm"), events[0])
        assertEquals(listOf("a"), (events[1] as SessionFeedEvent.Sessions).sessions.map { it.title })
        assertEquals(listOf("runtime.describe", "session.list"), link.methods)
    }

    @Test
    fun everyNewConnectionListsAgain() {
        val link = FakeLink(answers(sessions("first"), sessions("second")))
        val events = run(link) { seen ->
            link.state.value = LinkState.Online(endpoint, 1)
            seen.until(2)
            link.state.value = LinkState.Offline(0, OfflineCause.ConnectionLost, "close 1001", endpoint, 1, null)
            link.state.value = LinkState.Online(endpoint, 2)
            seen.until(4)
        }
        val titles = events.filterIsInstance<SessionFeedEvent.Sessions>().map { it.sessions.single().title }
        assertEquals(listOf("first", "second"), titles)
    }

    @Test
    fun aBurstOfSessionsChangedRefetchesOnceAfterAList() {
        val link = FakeLink(answers(sessions("first"), sessions("second"), sessions("third")))
        val events = run(link) { seen ->
            link.sessionsChanged() // before Online: nobody listens yet
            link.state.value = LinkState.Online(endpoint, 1)
            seen.until(2)
            repeat(3) { link.sessionsChanged() }
            seen.until(3)
            delay(fast.refreshDebounceMillis * 4)
        }
        val titles = events.filterIsInstance<SessionFeedEvent.Sessions>().map { it.sessions.single().title }
        assertEquals(listOf("first", "second"), titles)
        assertEquals(2, link.methods.count { it == "session.list" })
    }

    @Test
    fun hermesErrorsAreTheListsButAClosedLinkIsNot() {
        val failing = FakeLink { method ->
            if (method == "session.list") throw RpcException(RpcError(5006, "storage"), "session.list failed: 5006") else describePm
        }
        val events = run(failing) { seen ->
            failing.state.value = LinkState.Online(endpoint, 1)
            seen.until(2)
        }
        assertEquals(SessionFeedEvent.ListFailed("session.list failed: 5006"), events[1])

        val closed = FakeLink { method ->
            if (method == "session.list") throw RpcException(null, "relay closed", linkClosed = true) else describePm
        }
        val quiet = run(closed) { seen ->
            closed.state.value = LinkState.Online(endpoint, 1)
            seen.until(1)
            delay(100)
        }
        assertEquals(listOf<SessionFeedEvent>(SessionFeedEvent.Profile("pm")), quiet)
    }
}
