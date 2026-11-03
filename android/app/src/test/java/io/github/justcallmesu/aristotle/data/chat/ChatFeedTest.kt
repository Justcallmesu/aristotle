package io.github.justcallmesu.aristotle.data.chat

import io.github.justcallmesu.aristotle.data.HermesJson
import io.github.justcallmesu.aristotle.data.InboundFrame
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.Network
import io.github.justcallmesu.aristotle.data.RelayLink
import io.github.justcallmesu.aristotle.data.ResumedSessions
import io.github.justcallmesu.aristotle.data.RpcError
import io.github.justcallmesu.aristotle.data.RpcException
import io.github.justcallmesu.aristotle.data.ServerEndpoint
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The chat's subscription over a fake link: one resume per socket, catch-up on reconnect, rebuild on a gap. */
class ChatFeedTest {
    private val session = "s1"
    private val endpoint = ServerEndpoint("192.168.1.61", 8760, Network.Lan)

    private class Call(val method: String, val params: JsonObject)

    private class FakeLink(private val answer: suspend (Call) -> JsonElement) : RelayLink {
        override val state = MutableStateFlow<LinkState>(LinkState.Connecting)
        val frames = MutableSharedFlow<InboundFrame>(extraBufferCapacity = 64)
        override val events: SharedFlow<InboundFrame> = frames
        val calls = CopyOnWriteArrayList<Call>()

        override suspend fun call(method: String, params: JsonObject): JsonElement {
            val call = Call(method, params)
            calls += call
            return answer(call)
        }

        override fun retryNow() = Unit

        override fun respond(requestId: String, result: JsonObject): Boolean = false

        fun event(type: String, sessionId: String, payload: String, seq: Long? = null) = frames.tryEmit(
            InboundFrame.Event(type, sessionId, HermesJson.parseToJsonElement(payload) as JsonObject, seq = seq, replayEpoch = "e"),
        )
    }

    private fun json(text: String): JsonElement = HermesJson.parseToJsonElement(text)

    private val fullSnapshot = json(
        """{"session_id":"s1","messages":[{"role":"user","content":"Count to ten.","row_id":1}],"running":true,
           "replay_epoch":"e","last_sequence":10,"prompts":[],"pending":[],"info":{"title":"Counting"}}""",
    )
    private val omittedSnapshot = json(
        """{"session_id":"s1","messages":[],"messages_omitted":true,"running":true,"replay_epoch":"e","last_sequence":12,"prompts":[],"pending":[],"info":{}}""",
    )
    private val oneMissedDelta = json(
        """{"events":[{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"s1","payload":{"text":"one"},"seq":11,"replay_epoch":"e"}}],
           "latest_seq":11,"replay_epoch":"e","truncated":false,"snapshot_required":false}""",
    )

    private fun FakeLink.methods(): List<String> = calls.map { it.method }

    private fun run(link: FakeLink, resumed: ResumedSessions, script: suspend (MutableStateFlow<ChatFeedState?>) -> Unit) = runBlocking {
        val seen = MutableStateFlow<ChatFeedState?>(null)
        val job = launch { ChatFeed(link, resumed, session).states().collect { seen.value = it } }
        withTimeout(10_000) { script(seen) }
        job.cancel()
    }

    private suspend fun waitUntil(condition: () -> Boolean) {
        while (!condition()) delay(5)
    }

    @Test
    fun openingResumesOnceWithTheTranscriptThenAppliesLiveFrames() {
        val resumed = ResumedSessions()
        val link = FakeLink { fullSnapshot }
        run(link, resumed) { seen ->
            link.state.value = LinkState.Online(endpoint, 1)
            waitUntil { seen.value?.loaded == true }
            assertEquals(json("""{"session_id":"s1"}"""), link.calls.single().params)
            assertEquals(setOf(session), resumed.snapshot(1))
            assertTrue(resumed.chatOpen(session))
            link.event("message.delta", session, """{"text":"stale"}""", seq = 10)
            link.event("message.delta", session, """{"text":"one"}""", seq = 11)
            waitUntil { seen.value?.chat?.items?.size == 2 }
            assertEquals(listOf(ChatItem.User("h-1", "Count to ten."), ChatItem.Assistant("a-0", "one", streaming = true)), seen.value!!.chat.items)
            assertEquals("Counting", seen.value!!.chat.title)
        }
        assertEquals(listOf("session.resume"), link.methods())
        assertFalse(resumed.chatOpen(session))
    }

    @Test
    fun framesArrivingDuringTheResumeAreKeptAndNotAppliedTwice() {
        val answer = CompletableDeferred<Unit>()
        val link = FakeLink { answer.await(); fullSnapshot }
        run(link, ResumedSessions()) { seen ->
            link.state.value = LinkState.Online(endpoint, 1)
            waitUntil { link.calls.isNotEmpty() }
            link.event("message.delta", session, """{"text":"already in the snapshot"}""", seq = 9)
            link.event("message.delta", session, """{"text":"new"}""", seq = 11)
            answer.complete(Unit)
            waitUntil { seen.value?.chat?.items?.size == 2 }
            assertEquals(ChatItem.Assistant("a-0", "new", streaming = true), seen.value!!.chat.items.last())
        }
    }

    @Test
    fun aNewSocketCatchesUpWithEventsSinceThenSubscribesWithoutTheTranscript() {
        val link = FakeLink { call ->
            when {
                call.method == "session.events.since" -> oneMissedDelta
                "omit_messages" in call.params -> omittedSnapshot
                else -> fullSnapshot
            }
        }
        run(link, ResumedSessions()) { seen ->
            link.state.value = LinkState.Online(endpoint, 1)
            waitUntil { seen.value?.loaded == true }
            link.state.value = LinkState.Offline(0, io.github.justcallmesu.aristotle.data.OfflineCause.ConnectionLost, "", null, 1, null)
            link.state.value = LinkState.Online(endpoint, 2)
            waitUntil { link.calls.size == 3 && seen.value?.chat?.items?.size == 2 }
            delay(50)
            assertEquals(listOf(ChatItem.User("h-1", "Count to ten."), ChatItem.Assistant("a-0", "one", streaming = true)), seen.value!!.chat.items)
            // Seq 11 is what was applied; 12 (the snapshot's position) has not arrived, so it is still to apply.
            assertEquals(11L, seen.value!!.chat.lastSeq)
        }
        assertEquals(listOf("session.resume", "session.events.since", "session.resume"), link.methods())
        assertEquals(json("""{"session_id":"s1","last_sequence":10,"replay_epoch":"e"}"""), link.calls[1].params)
        assertEquals(json("""{"session_id":"s1","omit_messages":true}"""), link.calls[2].params)
    }

    // T11 review MAJOR-1: Hermes writes the frames after the caught-up seq to the new socket before it answers
    // the omit resume (`session_authority.py:369-370`); they are applied once, a repeat of seq 11 is not.
    @Test
    fun framesHermesSendsBeforeAnsweringTheOmitResumeAreAppliedOnce() {
        lateinit var link: FakeLink
        link = FakeLink { call ->
            when {
                call.method == "session.events.since" -> oneMissedDelta
                "omit_messages" in call.params -> {
                    link.event("message.delta", session, """{"text":"one"}""", seq = 11)
                    link.event("message.delta", session, """{"text":" two"}""", seq = 12)
                    omittedSnapshot
                }
                else -> fullSnapshot
            }
        }
        run(link, ResumedSessions()) { seen ->
            link.state.value = LinkState.Online(endpoint, 1)
            waitUntil { seen.value?.loaded == true }
            link.state.value = LinkState.Online(endpoint, 2)
            waitUntil { seen.value?.chat?.lastSeq == 12L }
            link.event("message.delta", session, """{"text":" three"}""", seq = 13)
            waitUntil { seen.value?.chat?.lastSeq == 13L }
            assertEquals(
                listOf(ChatItem.User("h-1", "Count to ten."), ChatItem.Assistant("a-0", "one two three", streaming = true)),
                seen.value!!.chat.items,
            )
        }
        assertEquals(listOf("session.resume", "session.events.since", "session.resume"), link.methods())
    }

    // Hermes' event log restarted between the catch-up and the omit resume: the epoch differs, so the chat
    // rebuilds from the full transcript instead of trusting the caught-up items.
    @Test
    fun anOmitResumeOnANewEpochFallsBackToTheFullTranscript() {
        val newEpoch = json(
            """{"session_id":"s1","messages":[],"messages_omitted":true,"running":false,"replay_epoch":"e2","last_sequence":3,"prompts":[],"pending":[],"info":{}}""",
        )
        val link = FakeLink { call ->
            when {
                call.method == "session.events.since" -> oneMissedDelta
                "omit_messages" in call.params -> newEpoch
                else -> fullSnapshot
            }
        }
        run(link, ResumedSessions()) { seen ->
            link.state.value = LinkState.Online(endpoint, 1)
            waitUntil { seen.value?.loaded == true }
            link.state.value = LinkState.Online(endpoint, 2)
            waitUntil { link.calls.size == 4 }
            waitUntil { seen.value?.chat?.items == listOf(ChatItem.User("h-1", "Count to ten.")) }
        }
        assertEquals(listOf("session.resume", "session.events.since", "session.resume", "session.resume"), link.methods())
        assertEquals(json("""{"session_id":"s1"}"""), link.calls[3].params)
    }

    // T11 review MINOR-6: a loaded chat whose resume is refused on a new socket keeps its transcript and says so.
    @Test
    fun aRefusedResumeAfterAReconnectKeepsTheTranscriptAndReportsIt() {
        var resumes = 0
        val link = FakeLink { call ->
            if (call.method == "session.resume" && resumes++ == 0) fullSnapshot else throw RpcException(RpcError(4001, "not_found"), "session.resume failed: 4001")
        }
        run(link, ResumedSessions()) { seen ->
            link.state.value = LinkState.Online(endpoint, 1)
            waitUntil { seen.value?.loaded == true }
            link.state.value = LinkState.Online(endpoint, 2)
            waitUntil { seen.value?.problem != null }
            assertTrue(seen.value!!.loaded)
            assertEquals(listOf(ChatItem.User("h-1", "Count to ten.")), seen.value!!.chat.items)
        }
    }

    // Retry from a loaded chat (the reattach banner) catches up from where it is and keeps the transcript.
    @Test
    fun aRetryFromALoadedChatCatchesUpInsteadOfReloading() {
        val link = FakeLink { call -> if (call.method == "session.events.since") oneMissedDelta else omittedSnapshot }
        val from = ChatFeedState(ChatReducer.fromSnapshot(session, fullSnapshot as JsonObject), loaded = true, problem = "session.resume timed out")
        runBlocking {
            val seen = MutableStateFlow<ChatFeedState?>(null)
            val job = launch { ChatFeed(link, ResumedSessions(), session).states(from).collect { seen.value = it } }
            withTimeout(10_000) {
                link.state.value = LinkState.Online(endpoint, 3)
                waitUntil { seen.value?.problem == null && seen.value?.chat?.items?.size == 2 }
            }
            job.cancel()
        }
        assertEquals(listOf("session.events.since", "session.resume"), link.methods())
        assertEquals(json("""{"session_id":"s1","omit_messages":true}"""), link.calls[1].params)
    }

    @Test
    fun whenTheWatcherAlreadyResumedOnTheNewSocketTheCatchUpIsEnough() {
        val resumed = ResumedSessions()
        val link = FakeLink { call -> if (call.method == "session.events.since") oneMissedDelta else fullSnapshot }
        run(link, resumed) { seen ->
            link.state.value = LinkState.Online(endpoint, 1)
            waitUntil { seen.value?.loaded == true }
            assertTrue(resumed.claim(2, session)) // the watcher, on the next socket
            link.state.value = LinkState.Online(endpoint, 2)
            waitUntil { seen.value?.chat?.items?.size == 2 }
            delay(50)
        }
        assertEquals(listOf("session.resume", "session.events.since"), link.methods())
    }

    @Test
    fun aTruncatedCatchUpRebuildsFromTheFullSnapshot() {
        val link = FakeLink { call ->
            if (call.method == "session.events.since") json("""{"events":[],"truncated":true,"snapshot_required":true}""") else fullSnapshot
        }
        run(link, ResumedSessions()) { seen ->
            link.state.value = LinkState.Online(endpoint, 1)
            waitUntil { seen.value?.loaded == true }
            link.state.value = LinkState.Online(endpoint, 2)
            waitUntil { link.calls.size == 3 }
        }
        assertEquals(listOf("session.resume", "session.events.since", "session.resume"), link.methods())
        assertEquals(json("""{"session_id":"s1"}"""), link.calls[2].params)
    }

    @Test
    fun aReplayGapRebuildsTheTranscriptOnTheSameSocket() {
        val resumed = ResumedSessions()
        val link = FakeLink { fullSnapshot }
        run(link, resumed) { seen ->
            link.state.value = LinkState.Online(endpoint, 1)
            waitUntil { seen.value?.loaded == true }
            link.event("message.delta", session, """{"text":"partial"}""", seq = 11)
            waitUntil { seen.value?.chat?.items?.size == 2 }
            link.event("session.replay_gap", "other", "{}")
            link.event("session.replay_gap", session, "{}")
            waitUntil { link.calls.size == 2 && seen.value?.chat?.items?.size == 1 }
            assertEquals(setOf(session), resumed.snapshot(1))
        }
        assertEquals(listOf("session.resume", "session.resume"), link.methods())
    }

    @Test
    fun aRefusedResumeShowsTheProblemAndFreesTheSessionForTheWatcher() {
        val resumed = ResumedSessions()
        val link = FakeLink { throw RpcException(RpcError(4001, "not_found"), "session.resume failed: 4001") }
        run(link, resumed) { seen ->
            link.state.value = LinkState.Online(endpoint, 1)
            waitUntil { seen.value?.problem != null }
            assertFalse(seen.value!!.loaded)
            assertEquals(emptySet<String>(), resumed.snapshot(1))
        }
    }

    @Test
    fun aSudoRequestIsTakenOnlyWhenNoOtherProfileHasTheSameSessionIdOnThisSocket() {
        val resumed = ResumedSessions()
        val link = FakeLink { fullSnapshot }
        fun sudo(id: String) = link.frames.tryEmit(
            InboundFrame.ServerRequest(id, "sudo", HermesJson.parseToJsonElement("""{"session_id":"s1","command":"sudo ls"}""") as JsonObject),
        )
        run(link, resumed) { seen ->
            link.state.value = LinkState.Online(endpoint, 1)
            waitUntil { seen.value?.loaded == true }
            // Session ids repeat across profiles on a host-scoped socket: once profile pm's own "s1" is attached
            // here too, a request naming "s1" may be pm's, so this chat (the launch profile's) does not show it.
            assertTrue(resumed.claim(1, session, profile = "pm"))
            sudo("srq-aaaaaaaaaaaa")
            link.event("message.delta", session, """{"text":"marker"}""", seq = 11)
            waitUntil { seen.value?.chat?.items?.size == 2 }
            assertTrue(seen.value!!.chat.prompts.isEmpty())
            assertTrue(resumed.release(1, session, profile = "pm"))
            sudo("srq-bbbbbbbbbbbb")
            waitUntil { seen.value?.chat?.prompts?.isNotEmpty() == true }
            assertEquals(listOf("srq-bbbbbbbbbbbb"), seen.value!!.chat.prompts.map { it.key })
        }
    }
}
