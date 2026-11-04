package io.github.justcallmesu.aristotle.ui.sessions

import io.github.justcallmesu.aristotle.data.HistoryGrouping
import io.github.justcallmesu.aristotle.data.HistorySort
import io.github.justcallmesu.aristotle.data.HomeView
import io.github.justcallmesu.aristotle.data.ProjectLane
import io.github.justcallmesu.aristotle.data.ProjectNode
import io.github.justcallmesu.aristotle.data.ProjectRepo
import io.github.justcallmesu.aristotle.data.ProjectTree
import io.github.justcallmesu.aristotle.data.ProjectTreeSession
import io.github.justcallmesu.aristotle.data.SessionSummary
import io.github.justcallmesu.aristotle.data.StatusBucket
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TY3: the Desktop's sort keys inside groups, the cost rule, status grouping and the project sections. */
class HistoryViewTest {
    private val zone = ZoneId.of("Asia/Jakarta")
    private val now = ZonedDateTime.of(2026, 11, 4, 22, 0, 0, 0, zone)
    private val today = now.toEpochSecond().toDouble() - 3_600

    private fun row(key: String) = SessionRow(key = key, title = key, running = false, messageCount = 1, time = null)

    private fun facts(vararg pairs: Pair<String, RowFacts>): (SessionRow) -> RowFacts {
        val map = mapOf(*pairs)
        return { map[it.key] ?: RowFacts() }
    }

    private val rows = listOf(row("a"), row("b"), row("c"), row("d"))
    private val f = facts(
        "a" to RowFacts(startedAt = 1.0, tokens = 10, costUsd = 0.0, status = StatusBucket.Idle),
        "b" to RowFacts(startedAt = 4.0, tokens = 500, costUsd = 0.2, status = StatusBucket.Draft),
        "c" to RowFacts(startedAt = null, tokens = 0, costUsd = 1.5, status = StatusBucket.Working),
        "d" to RowFacts(startedAt = 3.0, tokens = 500, costUsd = 0.0, status = StatusBucket.Draft),
    )

    private fun keys(list: List<SessionRow>) = list.map { it.key }

    @Test
    fun updatedKeepsTheOrderRowsCameIn() = assertEquals(listOf("a", "b", "c", "d"), keys(sortRows(rows, HistorySort.Updated, f)))

    @Test
    fun createdIsNewestStartFirstUnknownLast() = assertEquals(listOf("b", "d", "a", "c"), keys(sortRows(rows, HistorySort.Created, f)))

    @Test
    fun statusIsLoudestFirstAndStable() = assertEquals(listOf("c", "b", "d", "a"), keys(sortRows(rows, HistorySort.Status, f)))

    @Test
    fun tokensMostFirstAndStable() = assertEquals(listOf("b", "d", "a", "c"), keys(sortRows(rows, HistorySort.Tokens, f)))

    @Test
    fun costMostFirst() = assertEquals(listOf("c", "b", "a", "d"), keys(sortRows(rows, HistorySort.Cost, f)))

    private fun tree(vararg projects: ProjectNode) = ProjectTree(projects = projects.toList())

    @Test
    fun costSortOnlyWhenSomethingCosts() {
        assertFalse(costSortShown(null))
        assertFalse(costSortShown(tree(ProjectNode(id = "p", sessionCount = 2, previewSessions = listOf(ProjectTreeSession(id = "s"))))))
        assertTrue(costSortShown(tree(ProjectNode(id = "p", totalCostUsd = 0.01))))
        assertTrue(costSortShown(tree(ProjectNode(id = "p", previewSessions = listOf(ProjectTreeSession(id = "s", estimatedCostUsd = 0.3))))))
    }

    @Test
    fun effectiveSortFallsBackWithoutItsData() {
        val free = tree(ProjectNode(id = "p"))
        val paid = tree(ProjectNode(id = "p", totalCostUsd = 2.0))
        assertEquals(HistorySort.Updated, effectiveSort(HomeView(sort = HistorySort.Tokens), null))
        assertEquals(HistorySort.Tokens, effectiveSort(HomeView(sort = HistorySort.Tokens), free))
        assertEquals(HistorySort.Updated, effectiveSort(HomeView(sort = HistorySort.Cost), free))
        assertEquals(HistorySort.Cost, effectiveSort(HomeView(sort = HistorySort.Cost), paid))
        assertEquals(HistorySort.Created, effectiveSort(HomeView(sort = HistorySort.Created), null))
    }

    private fun shape(items: List<LedgerItem>): List<String> = items.map {
        when (it) {
            is LedgerItem.Header -> "#${it.section}"
            is LedgerItem.Row -> it.row.key
            is LedgerItem.ViewBar -> "bar:${it.grouping}/${it.sort}"
            is LedgerItem.StatusHeader -> "#${it.bucket}"
            is LedgerItem.ProjectHeader -> "@${it.id}:${it.count}" + if (it.collapsed) "-" else ""
            is LedgerItem.ProjectFooter -> "more:${it.id}:${it.more}"
            else -> it.toString()
        }
    }

    private val home = HomeList(
        live = emptyList(),
        ledger = listOf(
            LedgerItem.Header(LedgerSection.Pinned), LedgerItem.Row(row("p")),
            LedgerItem.Header(LedgerSection.Today, "Wed 4 Nov"), LedgerItem.Row(row("a")), LedgerItem.Row(row("b")),
            LedgerItem.Header(LedgerSection.Earlier), LedgerItem.Row(row("c")), LedgerItem.Row(row("d")),
            LedgerItem.Footer(HistoryEnd.More),
        ),
    )

    @Test
    fun dateGroupingSortsInsideEachDayAndKeepsPinsOnTop() {
        val out = arrangeHistory(home, HomeView(sort = HistorySort.Tokens), HistorySort.Tokens, f, searching = false)
        assertEquals(listOf("bar:Date/Tokens", "#Pinned", "p", "#Today", "b", "a", "#Earlier", "d", "c", "Footer(end=More)"), shape(out))
    }

    @Test
    fun statusGroupingInBucketOrder() {
        val out = arrangeHistory(home, HomeView(grouping = HistoryGrouping.Status), HistorySort.Updated, f, searching = false)
        assertEquals(listOf("bar:Status/Updated", "#Pinned", "p", "#Working", "c", "#Draft", "b", "d", "#Idle", "a", "Footer(end=More)"), shape(out))
    }

    @Test
    fun searchHidesTheBarAndNoHistoryMeansNoBar() {
        val out = arrangeHistory(home, HomeView(), HistorySort.Updated, f, searching = true)
        assertFalse(out.any { it is LedgerItem.ViewBar })
        assertEquals(emptyList<LedgerItem>(), arrangeHistory(HomeList(emptyList(), emptyList()), HomeView(), HistorySort.Updated, f, searching = false))
    }

    // ── projects ──

    private fun ts(id: String, last: Double, tokens: Long = 0, title: String = id) =
        ProjectTreeSession(id = id, title = title, startedAt = last - 10, lastActive = last, messageCount = 2, inputTokens = tokens)

    private val projectTree = ProjectTree(
        projects = listOf(
            ProjectNode(id = "aristotle", label = "Aristotle", sessionCount = 5, lastActive = today,
                previewSessions = listOf(ts("x1", today, 5), ts("x2", today - 10, 900), ts("x3", today - 20, 50))),
            ProjectNode(id = "__no_project__", label = "Home", isNoProject = true, sessionCount = 1, lastActive = today - 100,
                previewSessions = listOf(ts("h1", today - 100))),
            ProjectNode(id = "disc", label = "disc", isAuto = true, sessionCount = 0),
            ProjectNode(id = "live-only", label = "Live", sessionCount = 1, lastActive = today - 5, previewSessions = listOf(ts("L", today - 5))),
        ),
    )

    private fun sections(
        input: ProjectsInput = ProjectsInput(TreeLoad.Loaded(projectTree)),
        view: HomeView = HomeView(grouping = HistoryGrouping.Project),
        sort: HistorySort = HistorySort.Updated,
        stored: List<SessionSummary> = emptyList(),
        live: List<SessionSummary> = emptyList(),
        excluded: Set<String> = emptySet(),
        query: String = "",
    ): List<LedgerItem> {
        val tree = (input.tree as? TreeLoad.Loaded)?.tree
        val facts = HistoryFacts(stored, live, tree, input.hydrated.values) { false }
        return projectSections(input, view, sort, facts, excluded, query, now, Locale.ENGLISH)
    }

    @Test
    fun projectsInDesktopOrderWithPreviewsAndLoadMore() {
        assertEquals(
            listOf("@__no_project__:1", "h1", "@aristotle:5", "x1", "x2", "x3", "more:aristotle:More", "@live-only:1", "L"),
            shape(sections()),
        )
    }

    @Test
    fun liveAndPinnedRowsAreLeftToTheirOwnPlace() {
        val out = shape(sections(excluded = setOf("L", "x2")))
        assertTrue("L" !in out && "x2" !in out)
        assertTrue("@live-only:1" in out) // the header keeps Hermes' count
    }

    @Test
    fun treeRowsUseTheListedKeyAndTitle() {
        val listed = SessionSummary(sessionId = "tip-1", id = "x1", title = "Renamed", resolvedId = "tip-1")
        val row = sections(stored = listOf(listed)).filterIsInstance<LedgerItem.Row>().first { it.row.title == "Renamed" }.row
        assertEquals("tip-1", row.key)
        assertTrue(row.stored)
    }

    @Test
    fun sortRanksInsideAProjectBeforeTrimming() {
        val out = shape(sections(sort = HistorySort.Tokens, input = ProjectsInput(TreeLoad.Loaded(projectTree), shown = mapOf("aristotle" to 2))))
        assertEquals(listOf("@aristotle:5", "x2", "x3", "more:aristotle:More"), out.subList(out.indexOf("@aristotle:5"), out.indexOf("@aristotle:5") + 4))
    }

    @Test
    fun foldedProjectsShowOnlyTheirHeader() {
        val out = shape(sections(view = HomeView(grouping = HistoryGrouping.Project, collapsed = setOf("aristotle"))))
        assertEquals(listOf("@__no_project__:1", "h1", "@aristotle:5-", "@live-only:1", "L"), out)
    }

    @Test
    fun phoneOrderApplies() {
        val out = shape(sections(view = HomeView(grouping = HistoryGrouping.Project, projectOrder = listOf("live-only", "aristotle"))))
        assertEquals(listOf("@__no_project__:1", "@live-only:1", "@aristotle:5"), out.filter { it.startsWith("@") })
    }

    @Test
    fun hydratedRowsFollowThePreviewAndEndTheMore() {
        val lanes = ProjectNode(
            id = "aristotle",
            repos = listOf(ProjectRepo(groups = listOf(ProjectLane(sessions = listOf(ts("x5", today - 40), ts("x1", today), ts("x4", today - 30)))))),
        )
        val input = ProjectsInput(TreeLoad.Loaded(projectTree), hydrated = mapOf("aristotle" to lanes), shown = mapOf("aristotle" to 53))
        val out = shape(sections(input = input))
        assertEquals(listOf("@aristotle:5", "x1", "x2", "x3", "x4", "x5", "@live-only:1"), out.subList(out.indexOf("@aristotle:5"), out.indexOf("@live-only:1") + 1))
    }

    @Test
    fun drillInStatesShowUnderTheProject() {
        assertTrue("more:aristotle:Loading" in shape(sections(input = ProjectsInput(TreeLoad.Loaded(projectTree), loading = setOf("aristotle")))))
        assertTrue("more:aristotle:Failed" in shape(sections(input = ProjectsInput(TreeLoad.Loaded(projectTree), failed = setOf("aristotle")))))
    }

    @Test
    fun treeStatesAndSearch() {
        assertEquals(listOf(LedgerItem.ProjectsLoading), sections(input = ProjectsInput(TreeLoad.Loading)))
        assertEquals(listOf(LedgerItem.ProjectsFailed), sections(input = ProjectsInput(TreeLoad.Failed("x"))))
        assertEquals(listOf(LedgerItem.ProjectsEmpty), sections(input = ProjectsInput(TreeLoad.Loaded(ProjectTree()))))
        // A search opens folded projects, shows every match and drops projects without one.
        val found = shape(sections(query = "x2", view = HomeView(grouping = HistoryGrouping.Project, collapsed = setOf("aristotle"))))
        assertEquals(listOf("@aristotle:5", "x2"), found)
        val none = arrangeHistory(HomeList(emptyList(), emptyList()), HomeView(grouping = HistoryGrouping.Project), HistorySort.Updated, { RowFacts() }, searching = true) {
            sections(query = "zzz")
        }
        assertEquals(listOf(LedgerItem.NoMatch), none)
    }

    @Test
    fun cappedTreeSaysSo() {
        val big = ProjectTree(projects = listOf(ProjectNode(id = "p", label = "p", sessionCount = 300, previewSessions = listOf(ts("s", today)))))
        assertEquals(LedgerItem.ProjectsCapped, sections(input = ProjectsInput(TreeLoad.Loaded(big))).last())
    }
}
