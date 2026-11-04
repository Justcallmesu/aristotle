package io.github.justcallmesu.aristotle.data

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TY1: the Running page's fan-out, merge, id matching, filter, sort and grouping. */
class RunningWorkTest {
    private val own = ProfileOption("default", "Default", own = true)
    private val pm = ProfileOption("pm", "PM", own = false)

    private fun active(id: String, status: String, last: Double? = null, title: String = "t-$id", key: String? = id, resolved: String? = null) =
        ActiveSession(id = id, sessionKey = key, resolvedId = resolved, title = title, status = status, startedAt = 1.0, lastActive = last)

    private fun rows(active: List<ActiveSession>, roster: List<RosterEntry> = emptyList(), profile: ProfileOption = own) =
        RunningMerge.rows(ProfileRead(profile, active, roster))

    // ── parsing ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `active_list and roster rows parse as Hermes sends them`() {
        // gateway/session_active_list.py:39-43 (title may be null in the store), tui_gateway/methods_subagents.py:138.
        val list = HermesJson.decodeFromString(
            ActiveListResult.serializer(),
            """{"sessions":[{"current":false,"id":"s1","session_key":"s1","last_active":12.5,"started_at":10.0,"message_count":4,
               "model":"m","preview":"","title":null,"source":"gui","status":"waiting"}]}""",
        )
        assertEquals(ActiveSession("s1", "s1", null, "", "waiting", 10.0, 12.5, 4), list.sessions.single())
        val roster = HermesJson.decodeFromString(RosterResult.serializer(), """{"sessions":[{"session_id":"r1","subagents":2,"background":1}]}""")
        assertEquals(RosterEntry("r1", 2, 1), roster.sessions.single())
    }

    @Test
    fun `frames carry only profile, and none for the launch profile`() {
        assertEquals(JsonObject(emptyMap()), RunningFrames.activeList(null))
        assertEquals(JsonObject(mapOf("profile" to JsonPrimitive("pm"))), RunningFrames.activeList("pm"))
        assertEquals(JsonObject(emptyMap()), RunningFrames.roster(null))
        assertEquals(JsonObject(mapOf("profile" to JsonPrimitive("pm"))), RunningFrames.roster("pm"))
    }

    @Test
    fun `status maps Hermes' four values and reads anything else as idle`() {
        assertEquals(RunningStatus.Waiting, RunningStatus.of("waiting"))
        assertEquals(RunningStatus.Working, RunningStatus.of("working"))
        assertEquals(RunningStatus.Starting, RunningStatus.of("starting"))
        assertEquals(RunningStatus.Idle, RunningStatus.of("idle"))
        assertEquals(RunningStatus.Idle, RunningStatus.of("bogus"))
    }

    // ── id matching ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a roster row matches a live session on id, session_key or resolved_id`() {
        val result = rows(
            listOf(
                active("a", "working"),
                active("b", "idle", key = "stored-b"), // sidecar: session_key is the agent's stored id
                active("c", "idle", key = "c", resolved = "tip-c"),
            ),
            listOf(RosterEntry("a", 1, 0), RosterEntry("stored-b", 0, 2), RosterEntry("tip-c", 3, 1)),
        )
        val byId = result.associateBy { it.sessionId }
        assertEquals(1 to 0, byId.getValue("a").let { it.subagents to it.background })
        assertEquals(0 to 2, byId.getValue("b").let { it.subagents to it.background })
        assertEquals(3 to 1, byId.getValue("c").let { it.subagents to it.background })
        assertTrue(result.all { it.live })
        assertEquals(3, result.size) // no orphan rows: every roster id was claimed
    }

    @Test
    fun `a roster row is counted once, and the same id twice is summed`() {
        val result = rows(
            listOf(active("a", "working", key = "a", resolved = "a")),
            listOf(RosterEntry("a", 1, 0), RosterEntry("a", 0, 1)),
        )
        assertEquals(1 to 1, result.single().let { it.subagents to it.background })
    }

    @Test
    fun `a roster row no live session claims still shows, named by its stored id`() {
        val result = rows(listOf(active("a", "working")), listOf(RosterEntry("gone", 0, 1)))
        val orphan = result.single { !it.live }
        assertEquals("gone", orphan.sessionId)
        assertNull(orphan.title)
        assertEquals(RunningStatus.Idle, orphan.status)
        assertEquals(1, orphan.background)
    }

    // ── filter ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `idle sessions are hidden unless they have subagents or background work`() {
        val result = rows(
            listOf(active("busy", "working"), active("quiet", "idle"), active("kids", "idle"), active("boot", "starting"), active("ask", "waiting")),
            listOf(RosterEntry("kids", 1, 0), RosterEntry("zero", 0, 0)),
        )
        assertEquals(setOf("busy", "kids", "boot", "ask"), result.map { it.sessionId }.toSet())
    }

    @Test
    fun `rows carry the profile they open in`() {
        val mine = rows(listOf(active("a", "working")), profile = own).single()
        assertEquals("default", mine.profile)
        assertNull(mine.wireProfile) // the launch profile: no profile key (T14)
        val theirs = rows(listOf(active("a", "working")), profile = pm).single()
        assertEquals("pm", theirs.wireProfile)
    }

    @Test
    fun `an empty title reads as untitled`() {
        assertNull(rows(listOf(active("a", "working", title = "  "))).single().title)
    }

    // ── sort and grouping ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `waiting first, then working, starting, idle with work, latest activity first within each`() {
        val result = rows(
            listOf(
                active("w-old", "working", last = 10.0),
                active("idle-kids", "idle", last = 99.0),
                active("ask-old", "waiting", last = 5.0),
                active("w-new", "working", last = 50.0),
                active("boot", "starting", last = 60.0),
                active("ask-new", "waiting", last = 40.0),
            ),
            listOf(RosterEntry("idle-kids", 1, 0)),
        )
        assertEquals(listOf("ask-new", "ask-old", "w-new", "w-old", "boot", "idle-kids"), result.map { it.sessionId })
    }

    @Test
    fun `groups follow their most urgent row, ties keep Hermes' profile order, empty profiles drop`() {
        val work = ProfileOption("work", "Work", own = false)
        val snapshot = RunningMerge.snapshot(
            listOf(
                ProfileRead(own, listOf(active("d1", "working", last = 1.0)), emptyList()),
                ProfileRead(pm, listOf(active("p1", "waiting", last = 1.0)), emptyList()),
                ProfileRead(work, listOf(active("x", "idle")), emptyList()),
            ),
        )
        assertEquals(listOf("pm", "default"), snapshot.groups.map { it.profile.name })
        assertEquals(2, snapshot.count)
        assertTrue(snapshot.failed.isEmpty())

        val tie = RunningMerge.snapshot(
            listOf(
                ProfileRead(own, listOf(active("d1", "working", last = 1.0)), emptyList()),
                ProfileRead(pm, listOf(active("p1", "working", last = 1.0)), emptyList()),
            ),
        )
        // Same status and time: the session id breaks the tie; rows equal in every way keep Hermes' profile order.
        val equal = RunningMerge.snapshot(
            listOf(
                ProfileRead(pm, listOf(active("same", "working", last = 1.0)), emptyList()),
                ProfileRead(own, listOf(active("same", "working", last = 1.0)), emptyList()),
            ),
        )
        assertEquals(listOf("default", "pm"), tie.groups.map { it.profile.name })
        assertEquals(listOf("pm", "default"), equal.groups.map { it.profile.name })
    }

    @Test
    fun `a failed call names its profile and keeps what the other call said`() {
        val snapshot = RunningMerge.snapshot(
            listOf(
                ProfileRead(own, listOf(active("a", "working")), null),
                ProfileRead(pm, null, listOf(RosterEntry("r", 0, 1))),
            ),
        )
        assertEquals(listOf("Default", "PM"), snapshot.failed)
        assertEquals(setOf("a", "r"), snapshot.groups.flatMap { g -> g.rows.map { it.sessionId } }.toSet())
    }

    // ── fan-out ───────────────────────────────────────────────────────────────────────────────────

    private class Call(val method: String, val params: JsonObject) {
        val profile: String? get() = (params["profile"] as? JsonPrimitive)?.content
    }

    private class FakeLink(val answer: suspend (Call) -> JsonElement) : RelayLink {
        override val state = MutableStateFlow<LinkState>(LinkState.Connecting)
        override val events = MutableSharedFlow<InboundFrame>(extraBufferCapacity = 64)
        val calls = CopyOnWriteArrayList<Call>()

        override suspend fun call(method: String, params: JsonObject): JsonElement {
            val call = Call(method, params)
            calls += call
            return answer(call)
        }

        override fun respond(requestId: String, result: JsonObject) = false
        override fun retryNow() = Unit
    }

    private fun json(text: String): JsonElement = HermesJson.parseToJsonElement(text)

    private val profiles = """{"profiles":[{"name":"default","display_name":"Default","last_session":null},
        {"name":"pm","display_name":"PM","last_session":null},{"name":"parked"}]}"""

    @Test
    fun `fetch asks profiles, then active_list and roster per served profile, and merges`() = runBlocking {
        val link = FakeLink { call ->
            when (call.method) {
                "profiles.list" -> json(profiles)
                "session.active_list" -> if (call.profile == "pm") {
                    json("""{"sessions":[{"id":"p1","session_key":"p1","title":"Plan","status":"waiting","started_at":1}]}""")
                } else {
                    json("""{"sessions":[{"id":"d1","session_key":"d1","title":"Code","status":"working","started_at":2},
                        {"id":"d2","session_key":"d2","title":"Old","status":"idle","started_at":3}]}""")
                }
                "subagent.roster" -> if (call.profile == "pm") json("""{"sessions":[]}""") else json("""{"sessions":[{"session_id":"d2","subagents":0,"background":1}]}""")
                else -> error(call.method)
            }
        }
        val snapshot = RunningWork(link).fetch()
        assertEquals(listOf("pm", "default"), snapshot.groups.map { it.profile.name })
        assertEquals(listOf("d1", "d2"), snapshot.groups[1].rows.map { it.sessionId })
        assertEquals(3, snapshot.count)
        // 1 + 2 x 2 served profiles; the parked one is never asked. The launch profile's calls carry no profile key.
        assertEquals(5, link.calls.size)
        assertEquals(listOf(null, "pm"), link.calls.filter { it.method == "session.active_list" }.map { it.profile }.sortedBy { it ?: "" })
        assertEquals(listOf(null, "pm"), link.calls.filter { it.method == "subagent.roster" }.map { it.profile }.sortedBy { it ?: "" })
        assertTrue(link.calls.filter { it.method != "profiles.list" }.all { it.params.keys - "profile" == emptySet<String>() })
    }

    @Test
    fun `a refused profile is reported and the others still show`() = runBlocking {
        val link = FakeLink { call ->
            when {
                call.method == "profiles.list" -> json(profiles)
                call.profile == "pm" -> throw RpcException(RpcError(4001, "profile_mismatch"), "session.active_list failed: 4001")
                call.method == "session.active_list" -> json("""{"sessions":[{"id":"d1","status":"working"}]}""")
                else -> json("""{"sessions":"not a list"}""") // unreadable roster: counted as failed too
            }
        }
        val snapshot = RunningWork(link).fetch()
        assertEquals(listOf("d1"), snapshot.groups.flatMap { g -> g.rows.map { it.sessionId } })
        assertEquals(listOf("Default", "PM"), snapshot.failed)
    }

    @Test
    fun `profiles_list failing fails the read`() = runBlocking {
        val link = FakeLink { throw RpcException(null, "profiles.list failed: 5000") }
        val failed = runCatching { RunningWork(link).fetch() }.exceptionOrNull()
        assertTrue(failed is RpcException)
        assertFalse(link.calls.any { it.method != "profiles.list" })
    }

    // ── TY7: compressed ids, per-profile timeout ──────────────────────────────────────────────────

    @Test
    fun `a roster id that is a compressed tip of a live session counts for it, once`() {
        val live = listOf(active("root", "working"))
        val roster = listOf(RosterEntry("tip", subagents = 2, background = 1))
        // Without aliases the same work shows twice: the live row with 0 counts and a "not live" row for the tip.
        assertEquals(2, rows(live, roster).size)
        val joined = RunningMerge.rows(ProfileRead(own, live, roster, aliases = listOf(setOf("root", "tip"))))
        val row = joined.single()
        assertEquals("root", row.sessionId)
        assertEquals(2, row.subagents)
        assertEquals(1, row.background)
        assertTrue(row.live)
    }

    @Test
    fun `aliases chain across three segments`() {
        val live = listOf(active("a", "working"))
        val roster = listOf(RosterEntry("c", subagents = 1, background = 0))
        val groups = listOf(setOf("a", "b"), setOf("b", "c"))
        assertEquals(1, RunningMerge.rows(ProfileRead(own, live, roster, aliases = groups)).single().subagents)
    }

    @Test
    fun `lineage fields of session_list make the alias group`() {
        val row = HermesJson.decodeFromString(SessionSummary.serializer(), """{"id":"root","session_id":"tip","resolved_id":"tip","_lineage_root_id":"root","_lineage_ids":["root","mid","tip"]}""")
        assertEquals(setOf("root", "mid", "tip"), row.aliasIds)
        assertEquals(setOf("root", "tip"), row.ids) // the history list's own matching is unchanged
    }

    @Test
    fun `session_list is asked only for a profile whose roster names an unmatched session, and joins the tip`() = runBlocking {
        val link = FakeLink { call ->
            when (call.method) {
                "profiles.list" -> json("""{"profiles":[{"name":"default","display_name":"Default"}]}""")
                "session.active_list" -> json("""{"sessions":[{"id":"root","session_key":"root","title":"Build","status":"working","started_at":1}]}""")
                "subagent.roster" -> json("""{"sessions":[{"session_id":"tip","subagents":3,"background":0}]}""")
                "session.list" -> json("""{"sessions":[{"id":"root","session_id":"tip","resolved_id":"tip"}]}""")
                else -> error(call.method)
            }
        }
        val snapshot = RunningWork(link).fetch()
        assertEquals(1, snapshot.count)
        assertEquals(3, snapshot.groups.single().rows.single().subagents)
        val list = link.calls.single { it.method == "session.list" }
        assertEquals("stored", (list.params["scope"] as JsonPrimitive).content)
    }

    @Test
    fun `a failing session_list leaves the page as before`() = runBlocking {
        val link = FakeLink { call ->
            when (call.method) {
                "profiles.list" -> json("""{"profiles":[{"name":"default","display_name":"Default"}]}""")
                "session.active_list" -> json("""{"sessions":[{"id":"root","status":"working"}]}""")
                "subagent.roster" -> json("""{"sessions":[{"session_id":"tip","subagents":1,"background":0}]}""")
                else -> throw RpcException(null, "session.list failed: 5000")
            }
        }
        val snapshot = RunningWork(link).fetch()
        assertEquals(2, snapshot.count)
        assertTrue(snapshot.failed.isEmpty())
    }

    @Test
    fun `a hung profile times out as failed and the others still show`() = runBlocking {
        val link = FakeLink { call ->
            if (call.profile == "pm" && call.method != "profiles.list") {
                kotlinx.coroutines.delay(60_000)
            }
            when (call.method) {
                "profiles.list" -> json(profiles)
                "session.active_list" -> json("""{"sessions":[{"id":"d1","status":"working"}]}""")
                else -> json("""{"sessions":[]}""")
            }
        }
        val started = System.currentTimeMillis()
        val snapshot = RunningWork(link, profileTimeoutMillis = 150).fetch()
        assertTrue(System.currentTimeMillis() - started < 5_000)
        assertEquals(listOf("d1"), snapshot.groups.flatMap { g -> g.rows.map { it.sessionId } })
        assertEquals(listOf("PM"), snapshot.failed)
    }
}
