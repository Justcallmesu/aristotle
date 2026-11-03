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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The session list over a fake process-wide link: no network, real coroutines, short timings. */
class SessionRepositoryTest {
    private val fast = FeedTimings(refreshDebounceMillis = 50)
    private val endpoint = ServerEndpoint("192.168.1.61", 8760, Network.Lan)

    class Call(val method: String, val params: JsonObject) {
        fun text(key: String): String? = (params[key] as? JsonPrimitive)?.content
        fun int(key: String): Int? = (params[key] as? JsonPrimitive)?.intOrNull
    }

    /** A link that answers each call with [answer] (or throws). */
    private class FakeLink(private val answer: (Call) -> JsonElement) : RelayLink {
        override val state = MutableStateFlow<LinkState>(LinkState.Connecting)
        val frames = MutableSharedFlow<InboundFrame>(extraBufferCapacity = 16)
        override val events: SharedFlow<InboundFrame> = frames
        val calls = CopyOnWriteArrayList<Call>()
        val methods: List<String> get() = calls.map { it.method }

        override suspend fun call(method: String, params: JsonObject): JsonElement {
            val call = Call(method, params)
            calls += call
            return answer(call)
        }

        override fun retryNow() = Unit

        override fun respond(requestId: String, result: JsonObject): Boolean = false

        fun sessionsChanged() = frames.tryEmit(InboundFrame.Event(RpcFrames.SESSIONS_CHANGED, "", null))
    }

    private fun rows(vararg ids: String, running: Boolean = false): JsonElement = HermesJson.parseToJsonElement(
        ids.joinToString(",", """{"sessions":[""", "]}") { """{"session_id":"$it","id":"$it","title":"$it","running":$running}""" },
    )

    private val describePm = HermesJson.parseToJsonElement("""{"profile_id":"/home/u/.hermes/profiles/pm"}""")

    /** Live and stored listings per call count of each scope. */
    private fun lists(live: List<JsonElement>, stored: List<JsonElement> = listOf(rows())): (Call) -> JsonElement {
        var l = 0
        var s = 0
        return { call ->
            when {
                call.method == "runtime.describe" -> describePm
                call.text("scope") == "live" -> live[minOf(l++, live.lastIndex)]
                call.text("scope") == "stored" -> stored[minOf(s++, stored.lastIndex)]
                else -> throw AssertionError("unexpected ${call.method} ${call.params}")
            }
        }
    }

    /** Collects the feed while [script] drives the link; returns everything the feed sent. */
    private fun run(
        link: FakeLink,
        repository: SessionRepository = SessionRepository(link, fast),
        script: suspend (StateFlow<List<SessionFeedEvent>>) -> Unit,
    ): List<SessionFeedEvent> =
        runBlocking {
            val seen = MutableStateFlow<List<SessionFeedEvent>>(emptyList())
            val job = launch { repository.feed().collect { seen.value = seen.value + it } }
            withTimeout(10_000) { script(seen) }
            job.cancel()
            seen.value
        }

    private suspend fun StateFlow<List<SessionFeedEvent>>.until(count: Int) = first { it.size >= count }

    private fun List<SessionFeedEvent>.listings() = filterIsInstance<SessionFeedEvent.Sessions>()

    @Test
    fun nothingIsListedUntilTheLinkIsOnline() {
        val link = FakeLink(lists(listOf(rows("a")), listOf(rows("h"))))
        val events = run(link) { seen ->
            delay(100)
            assertTrue(seen.value.isEmpty())
            link.state.value = LinkState.Online(endpoint, 1)
            seen.until(2)
        }
        assertEquals(SessionFeedEvent.Profile("pm"), events[0])
        val listing = events[1] as SessionFeedEvent.Sessions
        assertEquals(listOf("a"), listing.live.map { it.title })
        assertEquals(listOf("h"), listing.stored.map { it.title })
        assertEquals(listOf("runtime.describe", "session.list", "session.list"), link.methods)
    }

    // Q48 + protocol §4: one listing = `session.list` scope live (all, 200) + scope stored (one page of 50).
    @Test
    fun aListingAsksLiveThenOnePageOfHistoryForTheShownProfile() {
        val link = FakeLink(lists(listOf(rows("a")), listOf(rows("h"))))
        val repository = SessionRepository(link, fast).apply { show("pm") }
        run(link, repository) { seen ->
            link.state.value = LinkState.Online(endpoint, 1)
            seen.until(2)
        }
        val (live, stored) = link.calls.filter { it.method == "session.list" }.map { it.params }
        assertEquals(HermesJson.parseToJsonElement("""{"scope":"live","limit":200,"profile":"pm"}"""), live)
        assertEquals(HermesJson.parseToJsonElement("""{"scope":"stored","limit":50,"profile":"pm"}"""), stored)
    }

    // A session that moved from the stored feed to the live one (resumed on the PC) is listed once, as live.
    @Test
    fun aSessionInBothFeedsIsListedOnceAsLive() {
        val stored = HermesJson.parseToJsonElement(
            """{"sessions":[{"session_id":"tip-1","id":"root-1","resolved_id":"tip-1","title":"moved"},{"session_id":"h","id":"h","title":"h"}]}""",
        )
        val link = FakeLink(lists(listOf(rows("tip-1", running = true)), listOf(stored)))
        val listing = run(link) { seen ->
            link.state.value = LinkState.Online(endpoint, 1)
            seen.until(2)
        }.listings().single()
        assertEquals(listOf("tip-1"), listing.live.map { it.key })
        assertEquals(listOf("h"), listing.stored.map { it.key })
    }

    // Protocol §4 paging: no offset; the next page is the same call with a limit 50 larger, up to Hermes' 200.
    @Test
    fun loadMoreGrowsTheHistoryLimitUpToHermesCap() {
        val page = { n: Int -> rows(*Array(n) { "h$it" }) }
        val link = FakeLink { call ->
            when {
                call.method == "runtime.describe" -> describePm
                call.text("scope") == "live" -> rows()
                else -> page(call.int("limit")!!) // the PC has more history than any page
            }
        }
        val repository = SessionRepository(link, fast)
        val events = run(link, repository) { seen ->
            link.state.value = LinkState.Online(endpoint, 1)
            seen.until(2)
            for (expected in 3..5) {
                assertTrue(repository.loadMore())
                seen.until(expected)
            }
            assertFalse("200 is the most session.list returns", repository.loadMore())
        }
        val limits = link.calls.filter { it.text("scope") == "stored" }.map { it.int("limit") }
        assertEquals(listOf(50, 100, 150, 200), limits)
        val listings = events.listings()
        assertEquals(listOf(true, true, true, false), listings.map { it.more })
        assertEquals(listOf(false, false, false, true), listings.map { it.capped })
        assertEquals(200, listings.last().stored.size)
    }

    @Test
    fun aShortHistoryHasNoMore() {
        val link = FakeLink(lists(listOf(rows()), listOf(rows("h1", "h2"))))
        val listing = run(link) { seen ->
            link.state.value = LinkState.Online(endpoint, 1)
            seen.until(2)
        }.listings().single()
        assertFalse(listing.more)
        assertFalse(listing.capped)
    }

    // T14 profile switch: the next listing is the new profile's, from the first page again.
    @Test
    fun showingAnotherProfileListsItFromTheFirstPage() {
        val link = FakeLink(lists(listOf(rows("a")), listOf(rows(*Array(50) { "h$it" }))))
        val repository = SessionRepository(link, fast)
        val events = run(link, repository) { seen ->
            link.state.value = LinkState.Online(endpoint, 1)
            seen.until(2)
            repository.loadMore()
            seen.until(3)
            repository.show("pm")
            seen.until(4)
        }
        assertEquals(listOf(null, null, "pm"), events.listings().map { it.profile })
        val stored = link.calls.filter { it.text("scope") == "stored" }
        assertEquals(listOf(50, 100, 50), stored.map { it.int("limit") })
        assertEquals(listOf(null, null, "pm"), stored.map { it.text("profile") })
    }

    @Test
    fun everyNewConnectionListsAgain() {
        val link = FakeLink(lists(listOf(rows("first"), rows("second"))))
        val events = run(link) { seen ->
            link.state.value = LinkState.Online(endpoint, 1)
            seen.until(2)
            link.state.value = LinkState.Offline(0, OfflineCause.ConnectionLost, "close 1001", endpoint, 1, null)
            link.state.value = LinkState.Online(endpoint, 2)
            seen.until(4)
        }
        assertEquals(listOf("first", "second"), events.listings().map { it.live.single().title })
    }

    @Test
    fun aBurstOfSessionsChangedRefetchesOnceAfterAList() {
        val link = FakeLink(lists(listOf(rows("first"), rows("second"), rows("third"))))
        val events = run(link) { seen ->
            link.sessionsChanged() // before Online: nobody listens yet
            link.state.value = LinkState.Online(endpoint, 1)
            seen.until(2)
            repeat(3) { link.sessionsChanged() }
            seen.until(3)
            delay(fast.refreshDebounceMillis * 4)
        }
        assertEquals(listOf("first", "second"), events.listings().map { it.live.single().title })
        assertEquals(4, link.methods.count { it == "session.list" })
    }

    @Test
    fun hermesErrorsAreTheListsButAClosedLinkIsNot() {
        val failing = FakeLink { call ->
            if (call.method == "session.list") throw RpcException(RpcError(5006, "storage"), "session.list failed: 5006") else describePm
        }
        val events = run(failing) { seen ->
            failing.state.value = LinkState.Online(endpoint, 1)
            seen.until(2)
        }
        assertEquals(SessionFeedEvent.ListFailed("session.list failed: 5006"), events[1])

        val closed = FakeLink { call ->
            if (call.method == "session.list") throw RpcException(null, "relay closed", linkClosed = true) else describePm
        }
        val quiet = run(closed) { seen ->
            closed.state.value = LinkState.Online(endpoint, 1)
            seen.until(1)
            delay(100)
        }
        assertEquals(listOf<SessionFeedEvent>(SessionFeedEvent.Profile("pm")), quiet)
    }

    // New session: the Desktop's create (`session.create {request_id, source:"gui"}`, c-create.jsonl) in the profile.
    @Test
    fun createSendsTheDesktopsCreateAndReturnsTheNewId() = runBlocking {
        val link = FakeLink { HermesJson.parseToJsonElement("""{"session_id":"local-42f9","messages":[],"running":false}""") }
        val id = SessionRepository(link).create("pm", requestId = "923a")
        assertEquals("local-42f9", id)
        assertEquals("session.create", link.calls.single().method)
        assertEquals(HermesJson.parseToJsonElement("""{"request_id":"923a","source":"gui","profile":"pm"}"""), link.calls.single().params)
    }

    // Rename: `session.mutate rename`; a revision_conflict carries the fresh revision and is retried once at it.
    @Test
    fun renameRetriesOnceAtTheRevisionTheConflictNames() = runBlocking {
        var ids = 0
        val link = FakeLink { call ->
            if (call.params["expected_revision"]?.jsonPrimitive?.intOrNull == 0) {
                val data = HermesJson.parseToJsonElement("""{"reason":"revision_conflict","revision":7,"execution_generation":2}""") as JsonObject
                throw RpcException(RpcError(4001, "revision_conflict", data), "session.mutate failed: 4001")
            }
            HermesJson.parseToJsonElement("""{"session_id":"s1","operation":"rename","revision":8,"title":"New"}""")
        }
        SessionRepository(link).rename(null, "s1", "New", newRequestId = { "r${++ids}" })
        assertEquals(
            listOf(
                """{"session_id":"s1","request_id":"r1","expected_revision":0,"operation":"rename","payload":{"title":"New"}}""",
                """{"session_id":"s1","request_id":"r2","expected_revision":7,"operation":"rename","payload":{"title":"New"}}""",
            ).map(HermesJson::parseToJsonElement),
            link.calls.map { it.params },
        )
    }

    @Test
    fun anotherRenameRefusalIsNotRetried() = runBlocking {
        val link = FakeLink {
            val data = HermesJson.parseToJsonElement("""{"reason":"invalid_params"}""") as JsonObject
            throw RpcException(RpcError(4001, "invalid_params", data), "session.mutate failed: 4001")
        }
        try {
            SessionRepository(link).rename("pm", "s1", "x")
            fail("refusal swallowed")
        } catch (e: RpcException) {
            assertEquals("invalid_params", e.error?.reason)
        }
        assertEquals(1, link.calls.size)
        assertEquals("pm", link.calls.single().text("profile"))
    }
}
