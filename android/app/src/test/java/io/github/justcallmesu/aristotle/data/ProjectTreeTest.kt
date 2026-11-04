package io.github.justcallmesu.aristotle.data

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TY3: the project tree's shape, the Desktop's project order and status rank, and the per-profile view on the phone. */
class ProjectTreeTest {
    private fun project(id: String, sessions: Int = 1, last: Double = 0.0, auto: Boolean = false, home: Boolean = false, label: String = id) =
        ProjectNode(id = id, label = label, isAuto = auto, isNoProject = home, sessionCount = sessions, lastActive = last)

    @Test
    fun decodesTheTreeAsHermesSendsIt() {
        // Shape of `project_tree._project_node` (tui_gateway/project_tree.py:350-367), Home bucket first.
        val json = """
            {"projects":[
              {"id":"__no_project__","label":"Home","path":null,"color":null,"icon":null,"isAuto":false,"isNoProject":true,
               "sessionCount":2,"lastActive":1700000000.5,"totalTokens":120,"totalCostUsd":0.0,"repos":[{"id":"__no_project__",
               "label":"Home","path":null,"groups":[{"id":"__no_project__","label":"Home","isMain":false,"isKanban":false,"sessions":[]}],
               "sessionCount":2}],
               "previewSessions":[{"id":"s1","title":"One","started_at":1.0,"last_active":5.0,"message_count":4,"input_tokens":100,
                 "output_tokens":20,"actual_cost_usd":null,"estimated_cost_usd":0.25,"_lineage_root_id":"r1","_lineage_ids":["r1","s1"],
                 "profile":"pm","cwd":"/x"}],
               "sessionIds":["s1","s2"]}],
             "active_id":null,"scoped_session_ids":["s1","s2"]}
        """.trimIndent()
        val tree = HermesJson.decodeFromString(ProjectTree.serializer(), json)
        val home = tree.projects.single()
        assertTrue(home.isNoProject)
        assertEquals(2, home.sessionCount)
        val row = home.previewSessions.single()
        assertEquals(120L, row.tokens)
        assertEquals(0.25, row.costUsd, 0.0)
        assertEquals(setOf("s1", "r1"), row.ids)
        assertEquals(2, tree.sessionsCounted)
    }

    @Test
    fun costIsActualElseEstimated() {
        assertEquals(0.5, ProjectTreeSession(actualCostUsd = 0.5, estimatedCostUsd = 0.9).costUsd, 0.0)
        assertEquals(0.9, ProjectTreeSession(actualCostUsd = 0.0, estimatedCostUsd = 0.9).costUsd, 0.0) // JS `||`
        assertEquals(0.0, ProjectTreeSession().costUsd, 0.0)
    }

    @Test
    fun framesCapTheTreeAndNameTheProfile() {
        val tree = ProjectFrames.tree("pm")
        assertEquals("pm", (tree["profile"] as JsonPrimitive).content)
        assertEquals(3, (tree["preview_limit"] as JsonPrimitive).intOrNull)
        assertEquals(300, (tree["session_limit"] as JsonPrimitive).intOrNull)
        assertFalse("launch profile sends none", "profile" in ProjectFrames.tree(null))
        val drill = ProjectFrames.projectSessions(null, "p1")
        assertEquals("p1", (drill["project_id"] as JsonPrimitive).content)
    }

    @Test
    fun overviewOrderIsTheDesktops() {
        val projects = listOf(
            project("auto-new", last = 90.0, auto = true),
            project("empty", sessions = 0, last = 99.0),
            project("b-old", last = 10.0, label = "beta"),
            project("a-old", last = 10.0, label = "Alpha"),
            project("new", last = 50.0),
            project("active", last = 1.0),
            project("__no_project__", last = 0.0, home = true),
        )
        val order = ProjectOrder.overview(projects, activeId = "active").map { it.id }
        // Home, active explicit, explicit with sessions by recency then label, explicit empty, then auto.
        assertEquals(listOf("__no_project__", "active", "new", "a-old", "b-old", "empty", "auto-new"), order)
    }

    @Test
    fun anActiveAutoProjectIsNotLifted() {
        val order = ProjectOrder.overview(listOf(project("x", last = 5.0), project("auto", last = 1.0, auto = true)), activeId = "auto").map { it.id }
        assertEquals(listOf("x", "auto"), order)
    }

    @Test
    fun phoneOrderKeepsFreshProjectsInPlace() {
        val base = listOf(project("__no_project__", home = true), project("a"), project("b"), project("fresh"), project("disc", sessions = 0))
        val order = ProjectOrder.byIds(base, listOf("b", "a", "gone")).map { it.id }
        // Fresh with sessions above the hand order, zero-session discoveries below, Home still first.
        assertEquals(listOf("__no_project__", "fresh", "b", "a", "disc"), order)
        assertEquals(base, ProjectOrder.byIds(base, emptyList()))
    }

    @Test
    fun moveSwapsNeighboursButNeverHome() {
        val shown = listOf("__no_project__", "a", "b", "c")
        assertEquals(listOf("__no_project__", "b", "a", "c"), ProjectOrder.move(shown, "b", -1))
        assertEquals(listOf("__no_project__", "a", "c", "b"), ProjectOrder.move(shown, "b", 1))
        assertEquals(shown, ProjectOrder.move(shown, "a", -1)) // not above Home
        assertEquals(shown, ProjectOrder.move(shown, "c", 1)) // already last
        assertEquals(shown, ProjectOrder.move(shown, "__no_project__", 1))
    }

    @Test
    fun statusRankIsLoudestFirst() {
        assertEquals(listOf(0, 1, 2, 3, 4), StatusBucket.entries.map { it.rank })
        assertEquals(StatusBucket.NeedsInput, StatusBucket.of(needsInput = true, working = true))
        assertEquals(StatusBucket.Working, StatusBucket.of(working = true, draft = true))
        assertEquals(StatusBucket.Draft, StatusBucket.of(draft = true))
        assertEquals(StatusBucket.Idle, StatusBucket.of())
        assertTrue(HistorySort.Tokens.needsMetrics && HistorySort.Cost.needsMetrics && !HistorySort.Created.needsMetrics)
    }

    @Test
    fun viewIsKeptPerPcAndProfileAndEndsWithThePins() {
        val store = MemoryStore()
        val views = HomeViewStore(store)
        val pins = SessionPins(store)
        assertEquals(HomeView(), views.of("pc-1", "pm"))
        val view = HomeView(HistoryGrouping.Project, HistorySort.Tokens, setOf("p1"), listOf("p2", "p1"))
        views.set("pc-1", "pm", view)
        views.set("pc-1", null, HomeView(sort = HistorySort.Created))
        pins.set("pc-1", "pm", "s1", true)
        assertEquals(view, HomeViewStore(store).of("pc-1", "pm"))
        assertEquals(HistorySort.Created, views.of("pc-1", null).sort)
        assertEquals(HomeView(), views.of("pc-2", "pm"))
        assertEquals(setOf("s1"), pins.of("pc-1", "pm")) // the view key is not read as pins
        views.set("pc-1", null, HomeView())
        assertEquals("the default view is not stored", 2, store.map.size)
        pins.forget("pc-1")
        assertEquals(HomeView(), views.of("pc-1", "pm"))
        assertTrue(store.map.isEmpty())
    }

    @Test
    fun unreadableViewFallsBackToDefault() {
        val store = MemoryStore()
        store.put("pins.pc-1.pm#view", "{not json")
        assertEquals(HomeView(), HomeViewStore(store).of("pc-1", "pm"))
        store.put("pins.pc-1.pm#view", """{"grouping":"Nope","sort":"Cost"}""")
        assertEquals(HomeView(sort = HistorySort.Cost), HomeViewStore(store).of("pc-1", "pm"))
    }
}
