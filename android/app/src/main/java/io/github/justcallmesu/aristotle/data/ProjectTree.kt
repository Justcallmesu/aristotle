package io.github.justcallmesu.aristotle.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One session row of the project tree (`methods_projects._project_tree_row`, `tui_gateway/methods_projects.py:324-338`):
 * the stored id, timings, counts and what it cost. Cost = `actual_cost_usd`, else `estimated_cost_usd`, as the Desktop
 * reads it (`apps/desktop/src/store/sidebar-archive.ts:140-142`).
 */
@Serializable
data class ProjectTreeSession(
    val id: String = "",
    val title: String? = null,
    @SerialName("started_at") val startedAt: Double? = null,
    @SerialName("last_active") val lastActive: Double? = null,
    @SerialName("message_count") val messageCount: Int = 0,
    @SerialName("input_tokens") val inputTokens: Long = 0,
    @SerialName("output_tokens") val outputTokens: Long = 0,
    @SerialName("actual_cost_usd") val actualCostUsd: Double? = null,
    @SerialName("estimated_cost_usd") val estimatedCostUsd: Double? = null,
    @SerialName("_lineage_root_id") val lineageRootId: String? = null,
    @SerialName("_lineage_ids") val lineageIds: List<String>? = null,
) {
    val tokens: Long get() = inputTokens + outputTokens
    val costUsd: Double get() = actualCostUsd?.takeIf { it != 0.0 } ?: estimatedCostUsd ?: 0.0

    /** Every id this row answers to (its own, the lineage root and the lineage), to meet a `session.list` row. */
    val ids: Set<String> get() = buildSet {
        if (id.isNotEmpty()) add(id)
        lineageRootId?.takeIf { it.isNotEmpty() }?.let(::add)
        lineageIds?.filter { it.isNotEmpty() }?.let(::addAll)
    }
}

/** A branch / worktree lane; `sessions` is filled only by `projects.project_sessions` (hydrated). */
@Serializable
data class ProjectLane(val id: String = "", val sessions: List<ProjectTreeSession> = emptyList())

@Serializable
data class ProjectRepo(val id: String = "", val groups: List<ProjectLane> = emptyList())

/**
 * A project of `projects.tree` (`tui_gateway/project_tree.py:350-367`, contract `contracts/projects_pets.py:264-280`):
 * explicit (user-made), auto (a git root) or the synthetic Home bucket ([isNoProject], id `__no_project__`).
 */
@Serializable
data class ProjectNode(
    val id: String = "",
    val label: String = "",
    val path: String? = null,
    val isAuto: Boolean = false,
    val isNoProject: Boolean = false,
    val sessionCount: Int = 0,
    val lastActive: Double = 0.0,
    val totalTokens: Long = 0,
    val totalCostUsd: Double = 0.0,
    val repos: List<ProjectRepo> = emptyList(),
    val previewSessions: List<ProjectTreeSession> = emptyList(),
    val sessionIds: List<String> = emptyList(),
) {
    /** Every hydrated row across repos and lanes, each once, newest activity first. */
    val laneSessions: List<ProjectTreeSession>
        get() = repos.flatMap { repo -> repo.groups.flatMap { it.sessions } }
            .distinctBy { it.id }
            .sortedByDescending { it.lastActive ?: it.startedAt ?: 0.0 }

    /** The Desktop's activity time (`projects/model.ts:56-60`): the backend stamp or the newest loaded row. */
    val activityTime: Double get() = maxOf(lastActive, laneSessions.maxOfOrNull { it.lastActive ?: it.startedAt ?: 0.0 } ?: 0.0)
}

/** `projects.tree` result (`contracts/projects_pets.py:288-291`). */
@Serializable
data class ProjectTree(
    val projects: List<ProjectNode> = emptyList(),
    @SerialName("active_id") val activeId: String? = null,
    @SerialName("scoped_session_ids") val scopedSessionIds: List<String> = emptyList(),
) {
    /** Every session row the tree carries (previews, and lanes when hydrated). */
    val rows: List<ProjectTreeSession> get() = projects.flatMap { it.previewSessions + it.laneSessions }

    /** Sessions the tree counted (Home + explicit + auto); at [ProjectFrames.SESSION_LIMIT] older ones were not read. */
    val sessionsCounted: Int get() = projects.sumOf { it.sessionCount.coerceAtLeast(0) }
}

/** `projects.project_sessions` result (`contracts/projects_pets.py:303-304`): the one project, hydrated, or null. */
@Serializable
data class ProjectSessionsResult(val project: ProjectNode? = null)

/** Params of the two project RPCs (protocol §18). Both are profile-scoped sidecar calls (`methods_config.py:137-165`). */
object ProjectFrames {
    /** Rows previewed under each project, as the Desktop's overview (`projects/model.ts:17`). */
    const val PREVIEW = 3

    /** Sessions the tree reads (Hermes' default is 2000, `methods_config.py:147`; the phone caps it). */
    const val SESSION_LIMIT = 300

    /** Sessions a drill-in reads (Hermes' default 5000, `methods_config.py:164`). */
    const val DRILL_IN_LIMIT = 500

    /** Rows each "Load more" reveals inside a project (`projects/model.ts:23`, PROJECT_SESSION_PAGE). */
    const val PAGE = 50

    /** The Home bucket's id (`tui_gateway/project_tree.py:29`). */
    const val NO_PROJECT_ID = "__no_project__"

    /** Process-wide hint that projects.db moved (`tui_gateway/change_watcher.py:386`): refetch the tree. */
    const val CHANGED = "projects.changed"

    fun tree(profile: String?, previewLimit: Int = PREVIEW, sessionLimit: Int = SESSION_LIMIT): JsonObject = buildJsonObject {
        profile?.let { put("profile", it) }
        put("preview_limit", previewLimit)
        put("session_limit", sessionLimit)
    }

    fun projectSessions(profile: String?, projectId: String, sessionLimit: Int = DRILL_IN_LIMIT): JsonObject = buildJsonObject {
        profile?.let { put("profile", it) }
        put("project_id", projectId)
        put("session_limit", sessionLimit)
    }
}

/** The Desktop's project order (`apps/desktop/src/app/chat/sidebar/projects/model.ts:84-147`), copied. */
object ProjectOrder {
    /**
     * `sortProjectsForOverview`: the active explicit project first, explicit before auto, projects with sessions before
     * empty ones, then newest activity, then label (case-insensitive); finally the Home bucket leads, whatever the rest.
     */
    fun overview(projects: List<ProjectNode>, activeId: String?): List<ProjectNode> {
        fun active(p: ProjectNode) = activeId != null && p.id == activeId && !p.isAuto
        val sorted = projects.sortedWith(
            compareBy<ProjectNode> { if (active(it)) 0 else 1 }
                .thenBy { if (it.isAuto) 1 else 0 }
                .thenBy { if (it.sessionCount > 0) 0 else 1 }
                .thenByDescending { it.activityTime }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label },
        )
        return homeFirst(sorted)
    }

    /**
     * `orderProjectsByIds`: the user's order over [overview]'s. Projects missing from [orderIds] keep their place in the
     * deterministic order: those with sessions go above the hand-ordered list, empty ones below; Home still leads.
     */
    fun byIds(projects: List<ProjectNode>, orderIds: List<String>): List<ProjectNode> {
        if (orderIds.isEmpty()) return projects
        val byId = projects.associateBy { it.id }
        val ordered = orderIds.distinct().mapNotNull { byId[it] }
        val seen = ordered.mapTo(HashSet()) { it.id }
        val fresh = projects.filterNot { it.id in seen }
        if (fresh.isEmpty()) return homeFirst(ordered)
        return homeFirst(fresh.filter { it.sessionCount > 0 } + ordered + fresh.filter { it.sessionCount <= 0 })
    }

    /** The order after moving [id] one place up ([by] = -1) or down (+1) in [shown]; Home never moves. */
    fun move(shown: List<String>, id: String, by: Int): List<String> {
        val from = shown.indexOf(id)
        val to = from + by
        if (from < 0 || to !in shown.indices || id == ProjectFrames.NO_PROJECT_ID || shown[to] == ProjectFrames.NO_PROJECT_ID) return shown
        return shown.toMutableList().apply { add(to, removeAt(from)) }
    }

    private fun homeFirst(projects: List<ProjectNode>): List<ProjectNode> =
        if (projects.firstOrNull()?.isNoProject == true || projects.none { it.isNoProject }) projects
        else projects.filter { it.isNoProject } + projects.filterNot { it.isNoProject }
}

/** How Home divides its history (the Desktop's `date|project|status`, `store/layout.ts:275`; `profile` has no use here). */
enum class HistoryGrouping { Date, Project, Status }

/** What ranks rows inside a group (the Desktop's sort keys, `store/layout.ts:304`, `store/sidebar-sort.ts:14-34`). */
enum class HistorySort {
    Updated, Created, Status, Tokens, Cost;

    /** Tokens and cost exist only on project-tree rows, so these keys need the tree with every row (protocol §18). */
    val needsMetrics: Boolean get() = this == Tokens || this == Cost
}

/**
 * The Desktop's status buckets, loudest first (`store/session-dot-state.ts:107-116`): needs-input < working < unread <
 * draft < idle. The phone knows working (`running`) and its own drafts; it has no unread or needs-input over
 * `session.list`.
 */
enum class StatusBucket(val rank: Int) {
    NeedsInput(0), Working(1), Unread(2), Draft(3), Idle(4);

    companion object {
        fun of(needsInput: Boolean = false, working: Boolean = false, unread: Boolean = false, draft: Boolean = false): StatusBucket = when {
            needsInput -> NeedsInput
            working -> Working
            unread -> Unread
            draft -> Draft
            else -> Idle
        }
    }
}

/** Home's history view for one profile of one PC, kept on this phone. */
@Serializable
data class HomeView(
    val grouping: HistoryGrouping = HistoryGrouping.Date,
    val sort: HistorySort = HistorySort.Updated,
    /** Project ids folded shut. */
    val collapsed: Set<String> = emptySet(),
    /** This phone's own project order (ids); empty = the Desktop's order. */
    val projectOrder: List<String> = emptyList(),
)

/**
 * [HomeView] per PC and profile. Keys live beside the pins and share their `pins.<pcId>.` prefix, so
 * [SessionPins.forget] (Unpair, re-pair) ends them too. `#` cannot be in a Hermes profile name
 * (`^[a-z0-9][a-z0-9_-]{0,63}$`, `hermes_cli/main.py:430`), so no pins key collides.
 */
class HomeViewStore(private val store: KeyValueStore) {
    fun of(pcId: String, profile: String?): HomeView =
        store.get(key(pcId, profile))?.let { runCatching { HermesJson.decodeFromString(HomeView.serializer(), it) }.getOrNull() } ?: HomeView()

    @Synchronized
    fun set(pcId: String, profile: String?, view: HomeView) {
        val key = key(pcId, profile)
        if (view == HomeView()) store.remove(key) else store.put(key, HermesJson.encodeToString(HomeView.serializer(), view))
    }

    private fun key(pcId: String, profile: String?) = "pins.$pcId.${profile ?: "\u0000"}#view"

    companion object {
        /** Nothing kept (tests, previews). */
        fun memory(): HomeViewStore = HomeViewStore(object : KeyValueStore {
            private val map = HashMap<String, String>()
            override fun get(key: String) = map[key]
            override fun put(key: String, value: String) {
                map[key] = value
            }
            override fun remove(key: String) {
                map.remove(key)
            }
            override fun keys(): Set<String> = map.keys.toSet()
        })
    }
}
