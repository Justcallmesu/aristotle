package io.github.justcallmesu.aristotle.ui.sessions

import io.github.justcallmesu.aristotle.data.HistoryGrouping
import io.github.justcallmesu.aristotle.data.HistorySort
import io.github.justcallmesu.aristotle.data.HomeView
import io.github.justcallmesu.aristotle.data.ProjectFrames
import io.github.justcallmesu.aristotle.data.ProjectNode
import io.github.justcallmesu.aristotle.data.ProjectOrder
import io.github.justcallmesu.aristotle.data.ProjectTree
import io.github.justcallmesu.aristotle.data.ProjectTreeSession
import io.github.justcallmesu.aristotle.data.SessionSummary
import io.github.justcallmesu.aristotle.data.StatusBucket
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.Locale

/** What the sort keys read about one row; tokens and cost are known only for rows the project tree carried. */
data class RowFacts(
    val startedAt: Double? = null,
    val tokens: Long = 0,
    val costUsd: Double = 0.0,
    val status: StatusBucket = StatusBucket.Idle,
)

/**
 * The Desktop's ranking (`apps/desktop/src/store/sidebar-sort.ts:14-34`), stable: `updated` keeps the order the rows
 * came in (newest activity first), `created` newest start first, `status` loudest bucket first, `tokens` and `cost`
 * the most first. Applied inside each group, never across groups.
 */
fun sortRows(rows: List<SessionRow>, sort: HistorySort, facts: (SessionRow) -> RowFacts): List<SessionRow> = when (sort) {
    HistorySort.Updated -> rows
    HistorySort.Created -> rows.sortedBy { -(facts(it).startedAt ?: 0.0) }
    HistorySort.Status -> rows.sortedBy { facts(it).status.rank }
    HistorySort.Tokens -> rows.sortedBy { -facts(it).tokens }
    HistorySort.Cost -> rows.sortedBy { -facts(it).costUsd }
}

/**
 * The Desktop hides the cost sort when no session reports spend ("subscription auth never quotes a price",
 * `store/sidebar-archive.ts:144-148`). The phone reads spend from the tree: each project's total and every row it carries.
 */
fun costSortShown(tree: ProjectTree?): Boolean =
    tree != null && (tree.projects.any { it.totalCostUsd > 0.0 } || tree.rows.any { it.costUsd > 0.0 })

/** The sort applied: [HomeView.sort], or `updated` while its data is missing (no tree yet, or nothing costs). */
fun effectiveSort(view: HomeView, tree: ProjectTree?): HistorySort = when {
    view.sort.needsMetrics && tree == null -> HistorySort.Updated
    view.sort == HistorySort.Cost && !costSortShown(tree) -> HistorySort.Updated
    else -> view.sort
}

/** Where the project tree is. */
sealed interface TreeLoad {
    data object Idle : TreeLoad
    data object Loading : TreeLoad
    data class Failed(val reason: String) : TreeLoad
    data class Loaded(val tree: ProjectTree) : TreeLoad
}

/** A project's "Load more": more rows to show, the drill-in running, or it failed (tap retries). */
enum class ProjectMore { More, Loading, Failed }

/** The project grouping's inputs beyond the list: the tree, drill-ins and how many rows each project shows. */
data class ProjectsInput(
    val tree: TreeLoad = TreeLoad.Idle,
    /** `projects.project_sessions` answers, by project id. */
    val hydrated: Map<String, ProjectNode> = emptyMap(),
    val loading: Set<String> = emptySet(),
    val failed: Set<String> = emptySet(),
    /** Rows shown per project; absent = [ProjectFrames.PREVIEW]. */
    val shown: Map<String, Int> = emptyMap(),
)

/** The phone's own order over the Desktop's ([ProjectOrder.overview], then [ProjectOrder.byIds]). */
fun orderedProjects(tree: ProjectTree, view: HomeView): List<ProjectNode> =
    ProjectOrder.byIds(ProjectOrder.overview(tree.projects, tree.activeId), view.projectOrder)

/** Everything the phone knows of [project]'s rows: the preview first, then hydrated rows not in it (`projects/model.ts:72-76`). */
fun knownProjectRows(project: ProjectNode, hydrated: ProjectNode?): List<ProjectTreeSession> {
    val preview = project.previewSessions
    val seen = preview.mapTo(HashSet()) { it.id }
    return preview + hydrated?.laneSessions.orEmpty().filterNot { it.id in seen }
}

/** How the arranged history reads one row: its facts and, for a tree row, the `session.list` row it is. */
class HistoryFacts(
    stored: List<SessionSummary>,
    live: List<SessionSummary>,
    tree: ProjectTree?,
    hydrated: Collection<ProjectNode>,
    private val draft: (String) -> Boolean,
) {
    private val summaries = HashMap<String, SessionSummary>()
    private val treeRows = HashMap<String, ProjectTreeSession>()

    init {
        (live + stored).forEach { s -> s.ids.forEach { summaries.putIfAbsent(it, s) } }
        (tree?.rows.orEmpty() + hydrated.flatMap { it.laneSessions }).forEach { r -> r.ids.forEach { treeRows.putIfAbsent(it, r) } }
    }

    /** The `session.list` row (stored or live) that is [row], when listed. */
    fun summaryOf(row: ProjectTreeSession): SessionSummary? = row.ids.firstNotNullOfOrNull { summaries[it] }

    fun facts(row: SessionRow): RowFacts {
        val summary = summaries[row.key]
        val metrics = (summary?.ids ?: setOf(row.key)).firstNotNullOfOrNull { treeRows[it] }
        return RowFacts(
            startedAt = summary?.startedAt ?: metrics?.startedAt,
            tokens = metrics?.tokens ?: 0,
            costUsd = metrics?.costUsd ?: 0.0,
            status = StatusBucket.of(working = row.running, draft = draft(row.key)),
        )
    }
}

/**
 * Home's ledger under the live cards for [view] (Q81d): the view bar, Pinned as today, then the history grouped by
 * date (as [homeList] built it), status (Desktop bucket order) or project (tree order, [projects]); rows ranked by
 * [sort] inside each group. [projectRows] builds the project sections (only read for [HistoryGrouping.Project]).
 */
fun arrangeHistory(
    home: HomeList,
    view: HomeView,
    sort: HistorySort,
    facts: (SessionRow) -> RowFacts,
    searching: Boolean,
    projectRows: () -> List<LedgerItem> = { emptyList() },
): List<LedgerItem> {
    val pinned = mutableListOf<LedgerItem>()
    val days = mutableListOf<Pair<LedgerItem.Header, MutableList<SessionRow>>>()
    val tail = mutableListOf<LedgerItem>()
    var inPinned = false
    for (item in home.ledger) {
        when (item) {
            is LedgerItem.Header -> if (item.section == LedgerSection.Pinned) {
                inPinned = true
                pinned += item
            } else {
                inPinned = false
                days += item to mutableListOf()
            }
            is LedgerItem.Row -> if (inPinned) pinned += item else days.lastOrNull()?.second?.add(item.row)
            else -> tail += item
        }
    }
    val out = mutableListOf<LedgerItem>()
    if (!searching) out += LedgerItem.ViewBar(view.grouping, sort)
    out += pinned
    when (view.grouping) {
        HistoryGrouping.Date -> {
            days.forEach { (header, rows) ->
                out += header
                sortRows(rows, sort, facts).mapTo(out) { LedgerItem.Row(it) }
            }
            out += tail
        }
        HistoryGrouping.Status -> {
            val rows = days.flatMap { it.second }
            val byBucket = rows.groupBy { facts(it).status }
            StatusBucket.entries.forEach { bucket ->
                val group = byBucket[bucket] ?: return@forEach
                out += LedgerItem.StatusHeader(bucket)
                sortRows(group, sort, facts).mapTo(out) { LedgerItem.Row(it) }
            }
            out += tail
        }
        HistoryGrouping.Project -> {
            val sections = projectRows()
            out += sections
            val anyRow = pinned.any { it is LedgerItem.Row } || sections.any { it is LedgerItem.Row }
            if (searching && !anyRow && sections.none { it is LedgerItem.ProjectsLoading || it is LedgerItem.ProjectsFailed }) out += LedgerItem.NoMatch
        }
    }
    // Nothing under the bar: no history to arrange (a project view always has a line, so it can be left).
    return if (out.size == 1 && out.first() is LedgerItem.ViewBar) emptyList() else out
}

/**
 * The project sections (TX1 §3): each project with sessions in the phone's order, its header (name, Hermes' count,
 * folded or not) and, unless folded, its newest rows ranked by [sort], the first [ProjectsInput.shown] of them, then
 * "Load more" while the project has more than shows. Rows already shown as live cards or pinned ([excluded] ids) are left
 * out; a search shows every matching row of every project and hides projects without one.
 */
fun projectSections(
    input: ProjectsInput,
    view: HomeView,
    sort: HistorySort,
    facts: HistoryFacts,
    excluded: Set<String>,
    query: String,
    now: ZonedDateTime,
    locale: Locale,
): List<LedgerItem> {
    val tree = when (val load = input.tree) {
        TreeLoad.Idle, TreeLoad.Loading -> return listOf(LedgerItem.ProjectsLoading)
        is TreeLoad.Failed -> return listOf(LedgerItem.ProjectsFailed)
        is TreeLoad.Loaded -> load.tree
    }
    val search = query.trim()
    val today = now.toLocalDate()
    val out = mutableListOf<LedgerItem>()
    val keys = HashSet<String>()
    for (project in orderedProjects(tree, view)) {
        val hydrated = input.hydrated[project.id]
        val known = knownProjectRows(project, hydrated)
        if (project.sessionCount <= 0 && known.isEmpty()) continue
        val rows = known
            .filterNot { r -> r.ids.any { it in excluded } || facts.summaryOf(r)?.ids.orEmpty().any { it in excluded } }
            .map { r -> rowOf(r, facts, now, today, locale) }
            .filter { search.isEmpty() || (it.title?.contains(search, ignoreCase = true) == true) }
            .filter { keys.add(it.key) } // one conversation, one row (two lineage rows can meet one listed row)
        if (search.isNotEmpty() && rows.isEmpty()) continue
        val collapsed = search.isEmpty() && project.id in view.collapsed
        out += LedgerItem.ProjectHeader(project.id, project.label, project.sessionCount, collapsed, project.isNoProject)
        if (collapsed) continue
        val ranked = sortRows(rows, sort, facts::facts)
        val limit = if (search.isNotEmpty()) ranked.size else input.shown[project.id] ?: ProjectFrames.PREVIEW
        ranked.take(limit).mapTo(out) { LedgerItem.Row(it) }
        val more = when {
            search.isNotEmpty() -> null
            project.id in input.loading -> ProjectMore.Loading
            project.id in input.failed -> ProjectMore.Failed
            ranked.size > limit -> ProjectMore.More
            hydrated == null && project.sessionCount > known.size -> ProjectMore.More
            else -> null
        }
        more?.let { out += LedgerItem.ProjectFooter(project.id, it) }
    }
    if (search.isEmpty() && out.isEmpty()) out += LedgerItem.ProjectsEmpty
    if (search.isEmpty() && tree.sessionsCounted >= ProjectFrames.SESSION_LIMIT) out += LedgerItem.ProjectsCapped
    return out
}

/** A tree row as a ledger row; when `session.list` listed it too, its key and title are used (so open and pins agree). */
private fun rowOf(row: ProjectTreeSession, facts: HistoryFacts, now: ZonedDateTime, today: LocalDate, locale: Locale): SessionRow {
    val listed = facts.summaryOf(row)
    val summary = SessionSummary(
        sessionId = listed?.key ?: row.id,
        id = listed?.id ?: row.id,
        title = listed?.title?.takeIf { it.isNotBlank() } ?: row.title.orEmpty(),
        startedAt = row.startedAt ?: listed?.startedAt,
        lastActive = row.lastActive,
        messageCount = row.messageCount,
    )
    return sessionRow(summary, now, today, locale).copy(stored = true)
}
