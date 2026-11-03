package io.github.justcallmesu.aristotle.data

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** T14: the profile roster, profile routing of calls, the remembered profile and the phone's pins. */
class ProfileLinkTest {
    private fun json(text: String): JsonElement = HermesJson.parseToJsonElement(text)

    // `profiles.list` (gateway/session_group_controls.py:390-460): own row first; served siblings carry session fields,
    // profiles only found on disk do not and are left out.
    @Test
    fun theRosterKeepsTheOwnRowAndServedSiblings() {
        val roster = json(
            """{"profiles":[
              {"name":"default","path":"/h/.hermes","is_default":true,"display_name":"","last_session":null},
              {"name":"pm","path":"/h/.hermes/profiles/pm","display_name":"PM","last_session":{"id":"x"},"worker_session":null},
              {"name":"parked","path":"/h/.hermes/profiles/parked","display_name":"parked"},
              {"name":"pm","last_session":null},
              {"name":"","last_session":null},
              "junk"
            ],"bot_mode_protocol":true}""",
        )
        assertEquals(
            listOf(ProfileOption("default", "default", own = true), ProfileOption("pm", "PM", own = false)),
            ProfileRoster.of(roster),
        )
        assertEquals(emptyList<ProfileOption>(), ProfileRoster.of(json("""{"sessions":[]}""")))
        assertNull(ProfileRoster.wire(ProfileOption("default", "default", own = true)))
        assertEquals("pm", ProfileRoster.wire(ProfileOption("pm", "PM", own = false)))
    }

    @Test
    fun aProfileLinkAddsTheProfileToEveryCallAndNothingForTheLaunchProfile() = runBlocking {
        val seen = CopyOnWriteArrayList<JsonObject>()
        val base = object : RelayLink {
            override val state = MutableStateFlow<LinkState>(LinkState.Connecting)
            override val events = MutableSharedFlow<InboundFrame>()
            override suspend fun call(method: String, params: JsonObject): JsonElement = params.also { seen += it }
            override fun respond(requestId: String, result: JsonObject) = true
            override fun retryNow() = Unit
        }
        val params = buildJsonObject { put("session_id", "s1") }
        ProfileLink(base, "pm").call("session.resume", params)
        ProfileLink(base, null).call("session.resume", params)
        ProfileLink(base, "pm").call("x", buildJsonObject { put("profile", "other") })
        assertEquals(
            listOf("""{"session_id":"s1","profile":"pm"}""", """{"session_id":"s1"}""", """{"profile":"other"}""").map(::json),
            seen,
        )
    }

    // The frames against the source (gateway/session_controls.py:561-568, :714-737; canonical_sessions.py:21-46).
    @Test
    fun framesCarryOnlyTheKeysHermesAccepts() {
        assertEquals(json("""{"scope":"stored","limit":200}"""), SessionFrames.list("stored", 999, null))
        assertEquals(json("""{"scope":"live","limit":1,"profile":"pm"}"""), SessionFrames.list("live", 0, "pm"))
        assertEquals(json("""{"request_id":"r","source":"gui"}"""), SessionFrames.create("r", null))
        assertEquals(json("""{"include_sessions":true}"""), SessionFrames.profiles())
        assertEquals(json("""{"session_id":"s","subscription_id":"sub","profile":"pm"}"""), SessionFrames.detach("s", "sub", "pm"))
        assertEquals(
            json("""{"session_id":"s","request_id":"r","expected_revision":3,"operation":"rename","payload":{"title":"T"}}"""),
            SessionFrames.rename("s", "r", 3, "T", null),
        )
    }

    @Test
    fun theProfileIsRememberedPerPc() {
        val store = MemoryStore()
        val selection = ProfileSelection(store)
        selection.select("pm") // no PC yet: nothing to remember it for
        assertNull(selection.selected.value)
        selection.follow("pc-1")
        selection.select("pm")
        selection.follow("pc-2")
        assertNull(selection.selected.value)
        val again = ProfileSelection(store).apply { follow("pc-1") }
        assertEquals("pm", again.selected.value)
        again.select(null)
        assertNull(ProfileSelection(store).apply { follow("pc-1") }.selected.value)
    }

    @Test
    fun pinsArePerPcAndProfile() {
        val pins = SessionPins(MemoryStore())
        assertEquals(setOf("a"), pins.set("pc", null, "a", pinned = true))
        assertEquals(setOf("a", "b"), pins.set("pc", null, "b", pinned = true))
        assertEquals(setOf("x"), pins.set("pc", "pm", "x", pinned = true))
        assertEquals(setOf("b"), pins.set("pc", null, "a", pinned = false))
        assertEquals(setOf("b"), pins.of("pc", null))
        assertEquals(setOf("x"), pins.of("pc", "pm"))
        assertEquals(emptySet<String>(), pins.of("other", null))
    }
}
