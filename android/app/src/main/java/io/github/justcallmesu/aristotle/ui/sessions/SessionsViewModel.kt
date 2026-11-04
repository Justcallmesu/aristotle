package io.github.justcallmesu.aristotle.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.justcallmesu.aristotle.data.HistoryGrouping
import io.github.justcallmesu.aristotle.data.HistorySort
import io.github.justcallmesu.aristotle.data.HomeView
import io.github.justcallmesu.aristotle.data.HomeViewStore
import io.github.justcallmesu.aristotle.data.InboundFrame
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.PcCredentials
import io.github.justcallmesu.aristotle.data.HomeLimits
import io.github.justcallmesu.aristotle.data.HomeLimitsPoller
import io.github.justcallmesu.aristotle.data.PcStatusPoller
import io.github.justcallmesu.aristotle.data.PcStatusSource
import io.github.justcallmesu.aristotle.data.PcStatusView
import io.github.justcallmesu.aristotle.data.chat.PlanUsage
import io.github.justcallmesu.aristotle.data.ProjectFrames
import io.github.justcallmesu.aristotle.data.ProjectOrder
import io.github.justcallmesu.aristotle.data.ProfileOption
import io.github.justcallmesu.aristotle.data.ProfileRoster
import io.github.justcallmesu.aristotle.data.ProfileSelection
import io.github.justcallmesu.aristotle.data.RelayLink
import io.github.justcallmesu.aristotle.data.RpcException
import io.github.justcallmesu.aristotle.data.RpcFrames
import io.github.justcallmesu.aristotle.data.ServerEndpoint
import io.github.justcallmesu.aristotle.data.SessionFeedEvent
import io.github.justcallmesu.aristotle.data.SessionPins
import io.github.justcallmesu.aristotle.data.SessionRepository
import io.github.justcallmesu.aristotle.data.chat.ChatDrafts
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The profile picker sheet: the roster once `profiles.list` answered, or why it did not. */
data class ProfilePicker(val options: List<ProfileOption>? = null, val problem: String? = null)

/** A chat to open: a row the user tapped, or the session just created ([focusComposer]). */
data class OpenRequest(val sessionId: String, val title: String?, val profile: String?, val focusComposer: Boolean = false)

/** One-off snackbar text. */
sealed interface SessionsMessage {
    data class RenameFailed(val reason: String) : SessionsMessage
    data class CreateFailed(val reason: String) : SessionsMessage

    /** The PC no longer serves the remembered profile [name]; the launch profile is listed instead. */
    data class ProfileFellBack(val name: String) : SessionsMessage
}

data class SessionsUiState(
    val link: LinkState = LinkState.Connecting,
    /** Where the link is, or the address it is about while offline. */
    val address: ServerEndpoint?,
    /** The relay's launch profile (`runtime.describe`), null until Hermes said. */
    val launchProfile: String? = null,
    /** The profile listed (Q25): null = the launch profile; otherwise its name, as calls send it. */
    val profile: String? = null,
    /** The last list fetched, kept while offline; null until the first one arrives. */
    val items: List<SessionListItem>? = null,
    /** When [items] was fetched, as HH:mm. */
    val listedAt: String? = null,
    /** `session.list` failed although the link is up. */
    val listProblem: String? = null,
    /** Pull to refresh is running: the live and stored lists are fetched again. */
    val refreshing: Boolean = false,
    val searching: Boolean = false,
    val query: String = "",
    /** The profile sheet is open. */
    val picker: ProfilePicker? = null,
    /** The row whose actions sheet is open (long press). */
    val actionsFor: SessionRow? = null,
    /** The row being renamed; [renameBusy] while `session.mutate` runs. */
    val renaming: SessionRow? = null,
    val renameBusy: Boolean = false,
    /** `session.create` is running. */
    val creating: Boolean = false,
    val message: SessionsMessage? = null,
    /** A chat to open; the screen takes it ([SessionsViewModel.opened]). */
    val open: OpenRequest? = null,
    /** The PC's last status reading (Q78); [PcStatusView.None] until the first one. */
    val pcStatus: PcStatusView = PcStatusView.None,
    /** The Claude limits (Q83); [HomeLimits.None] until a reading with numbers arrives, and then the row is simply absent. */
    val limits: HomeLimits = HomeLimits.None,
    /** The listed profile's history view on this phone (Q81d): grouping, sort, folded projects, project order. */
    val view: HomeView = HomeView(),
    /** The ledger under the live cards for [view] ([arrangeHistory]); null until the first list. */
    val ledger: List<LedgerItem>? = null,
    /** The "Group and sort" sheet is open. */
    val viewSheet: Boolean = false,
    /** Offer the cost sort: some session reports spend (the Desktop's rule, [costSortShown]). */
    val costSort: Boolean = false,
) {
    /** The name on the profile chip. */
    val profileName: String? get() = profile ?: launchProfile

    /**
     * When the list is from while it is not live (the link is not Online: offline, or connecting again after
     * a network switch): running rows read "Was running at …" then, never a live spinner (design.md §5).
     */
    val staleAt: String? get() = listedAt.takeIf { link !is LinkState.Online }
}

/**
 * UI state for the session list (T14): live sessions above stored history (Q48), search over the loaded rows,
 * pins on this phone, rename, new session and the profile switch (Q25). The connection is the process-wide
 * [RelayLink]; the profile choice is [selection], shared with the background watcher.
 */
class SessionsViewModel(
    private val pc: PcCredentials,
    private val link: RelayLink,
    private val drafts: ChatDrafts? = null,
    private val selection: ProfileSelection,
    private val pins: SessionPins,
    private val views: HomeViewStore = HomeViewStore.memory(),
    private val locale: () -> Locale = { Locale.getDefault() },
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() },
    scope: CoroutineScope? = null,
    statusSource: PcStatusSource? = null,
    statusPause: suspend (Long) -> Unit = { delay(it) },
    limitsSource: (suspend (String?) -> PlanUsage)? = null,
) : ViewModel() {
    private val work: CoroutineScope = scope ?: viewModelScope
    private val repository = SessionRepository(link)
    private val state = MutableStateFlow(
        SessionsUiState(address = pc.lastGood ?: pc.addresses.firstOrNull(), profile = selection.selected.value, view = views.of(pc.pcId, selection.selected.value)),
    )
    val uiState: StateFlow<SessionsUiState> = state.asStateFlow()

    /** Whether Home is on screen and resumed (Q78): the PC status is read only then, and only while online. */
    private val homeVisible = MutableStateFlow(false)
    private val poller = statusSource?.let { source ->
        PcStatusPoller(work, link.state, homeVisible, { endpoint -> source.pcStatus(pc, endpoint) }, pause = statusPause)
    }

    /** The Claude limits row (Q83): `model.options` without a session, read when Home shows and at most every 5 min ([HomeLimitsPoller]). */
    private val limitsPoller = limitsSource?.let { source ->
        HomeLimitsPoller(work, link.state, homeVisible, selection.selected, source)
    }

    fun homeVisible(visible: Boolean) {
        homeVisible.value = visible
    }

    private var listing: SessionFeedEvent.Sessions? = null
    private var pinned: Set<String> = pins.of(pc.pcId, state.value.profile)
    private var loadingMore = false
    private var feed: Job? = null

    // ── project tree (Q81d, protocol §18) ──
    private var projects = ProjectsInput()
    private var treePreview = 0
    private var tree: Job? = null
    private var treeRefetch: Job? = null
    private var treeAtNanos = 0L

    init {
        repository.show(state.value.profile)
        work.launch { link.state.collect(::onLink) }
        // The watcher (or this list) fell back from a profile the PC no longer serves: follow, and say so once.
        work.launch { selection.selected.collect(::applyProfile) }
        work.launch {
            selection.fellBackFrom.collect { name ->
                if (name == null) return@collect
                state.update { it.copy(message = SessionsMessage.ProfileFellBack(name)) }
                selection.noticeShown()
            }
        }
        poller?.let { p ->
            p.start()
            work.launch { p.view.collect { view -> state.update { it.copy(pcStatus = view) } } }
        }
        limitsPoller?.let { p ->
            p.start()
            work.launch { p.view.collect { view -> state.update { it.copy(limits = view) } } }
        }
        work.launch { link.events.collect(::onEvent) }
        startFeed()
    }

    /** Retry now / Check again: the link tries at once; a failed list is fetched again. */
    fun retry() {
        link.retryNow()
        if (state.value.listProblem != null) {
            state.update { it.copy(listProblem = null) }
            repository.reload()
        }
    }

    /** Pull to refresh: both lists again; offline it is a Retry of the link. */
    fun refresh() {
        if (state.value.link !is LinkState.Online) return link.retryNow()
        state.update { it.copy(refreshing = true, listProblem = null) }
        limitsPoller?.force()
        repository.reload()
        if (treeWanted()) loadTree()
    }

    /** The history row's Retry: both lists again (the live rows stay meanwhile). */
    fun retryHistory() = repository.reload()

    // ── history view: group, sort, projects (Q81d) ────────────────────────────────────────────────

    /** Opens "Group and sort"; the tree is read for the cost rule if it is not yet. */
    fun openView() {
        state.update { it.copy(viewSheet = true) }
        if (projects.tree !is TreeLoad.Loaded && projects.tree !is TreeLoad.Loading) loadTree()
    }

    fun closeView() = state.update { it.copy(viewSheet = false) }

    fun setGrouping(grouping: HistoryGrouping) = changeView { it.copy(grouping = grouping) }

    fun setSort(sort: HistorySort) = changeView { it.copy(sort = sort) }

    /** Folds or opens [projectId]'s rows. */
    fun toggleProject(projectId: String) = changeView {
        it.copy(collapsed = if (projectId in it.collapsed) it.collapsed - projectId else it.collapsed + projectId)
    }

    /** Moves [projectId] one place up ([by] -1) or down (+1) in the order shown; the order is kept on this phone. */
    fun moveProject(projectId: String, by: Int) {
        val tree = (projects.tree as? TreeLoad.Loaded)?.tree ?: return
        val shown = orderedProjects(tree, state.value.view).filter { it.sessionCount > 0 || knownProjectRows(it, projects.hydrated[it.id]).isNotEmpty() }.map { it.id }
        val moved = ProjectOrder.move(shown, projectId, by)
        if (moved != shown) changeView { it.copy(projectOrder = moved) }
    }

    /** Back to the Desktop's project order. */
    fun resetProjectOrder() = changeView { it.copy(projectOrder = emptyList()) }

    /** A project's "Load more": one more page of its rows, read from Hermes (`projects.project_sessions`) when needed. */
    fun moreInProject(projectId: String) {
        val tree = (projects.tree as? TreeLoad.Loaded)?.tree ?: return
        val project = tree.projects.firstOrNull { it.id == projectId } ?: return
        if (projectId in projects.loading) return
        val shown = projects.shown[projectId] ?: ProjectFrames.PREVIEW
        val hydrated = projects.hydrated[projectId]
        val needsRows = hydrated == null && (projectId in projects.failed || project.sessionCount > knownProjectRows(project, null).size)
        if (needsRows) {
            hydrate(projectId, shown + ProjectFrames.PAGE, quiet = false)
        } else {
            projects = projects.copy(shown = projects.shown + (projectId to shown + ProjectFrames.PAGE))
            rebuild()
        }
    }

    /** The projects line's Retry. */
    fun retryProjects() = loadTree()

    private fun changeView(change: (HomeView) -> HomeView) {
        val current = state.value
        val next = change(current.view)
        if (next == current.view) return
        views.set(pc.pcId, current.profile, next)
        state.update { it.copy(view = next) }
        if (treeWanted() && (projects.tree !is TreeLoad.Loaded || treePreview < previewWanted())) loadTree() else rebuild()
    }

    /** The tree is read for the project grouping and the tokens / cost sorts, or while the sheet is open (cost rule). */
    private fun treeWanted(): Boolean {
        val current = state.value
        return current.view.grouping == HistoryGrouping.Project || current.view.sort.needsMetrics || current.viewSheet
    }

    /** Tokens and cost live only on tree rows: those sorts ask for every row (bounded by the tree's session limit). */
    private fun previewWanted(): Int = if (state.value.view.sort.needsMetrics) ProjectFrames.SESSION_LIMIT else ProjectFrames.PREVIEW

    /** `projects.tree` for the listed profile; drill-ins already open are read again quietly after it. */
    private fun loadTree() {
        val profile = state.value.profile
        val preview = previewWanted()
        tree?.cancel()
        treeAtNanos = System.nanoTime()
        if (projects.tree !is TreeLoad.Loaded) {
            projects = projects.copy(tree = TreeLoad.Loading)
            rebuild()
        }
        tree = work.launch {
            val load = try {
                TreeLoad.Loaded(repository.projectTree(profile, preview))
            } catch (e: RpcException) {
                // A closed link: read again when it is back (onLink); only Hermes' own failure is shown.
                when {
                    !e.linkClosed -> TreeLoad.Failed(reason(e))
                    projects.tree is TreeLoad.Loaded -> return@launch
                    else -> TreeLoad.Idle
                }
            } catch (_: IllegalArgumentException) {
                TreeLoad.Failed("projects.tree: unexpected result")
            }
            if (profile != state.value.profile) return@launch
            if (load is TreeLoad.Loaded) treePreview = preview
            val again = if (load is TreeLoad.Loaded) projects.hydrated.keys.toList() else emptyList()
            projects = projects.copy(tree = load)
            rebuild()
            again.forEach { hydrate(it, projects.shown[it] ?: ProjectFrames.PREVIEW, quiet = true) }
        }
    }

    /** `projects.project_sessions` for [projectId], then [shown] rows of it; [quiet]: no "Loading…" (a refresh). */
    private fun hydrate(projectId: String, shown: Int, quiet: Boolean) {
        val profile = state.value.profile
        if (!quiet) {
            projects = projects.copy(loading = projects.loading + projectId, failed = projects.failed - projectId)
            rebuild()
        }
        work.launch {
            val node = try {
                repository.projectSessions(profile, projectId)
            } catch (_: RpcException) {
                failHydrate(profile, projectId, quiet)
                return@launch
            } catch (_: IllegalArgumentException) {
                failHydrate(profile, projectId, quiet)
                return@launch
            }
            if (profile != state.value.profile) return@launch
            projects = projects.copy(
                hydrated = if (node != null) projects.hydrated + (projectId to node) else projects.hydrated - projectId,
                loading = projects.loading - projectId,
                failed = projects.failed - projectId,
                shown = projects.shown + (projectId to shown),
            )
            rebuild()
        }
    }

    private fun failHydrate(profile: String?, projectId: String, quiet: Boolean) {
        if (profile != state.value.profile || quiet) return
        projects = projects.copy(loading = projects.loading - projectId, failed = projects.failed + projectId)
        rebuild()
    }

    /**
     * Hints from Hermes: `projects.changed` reads the tree again (after the same 2 s the watcher checks at); a
     * `sessions.changed` burst does so at most every 30 s, and only while a view needs the tree.
     */
    private fun onEvent(frame: InboundFrame) {
        if (frame !is InboundFrame.Event || projects.tree !is TreeLoad.Loaded || !treeWanted()) return
        val wait = when (frame.type) {
            ProjectFrames.CHANGED -> TREE_CHANGED_WAIT_MS
            RpcFrames.SESSIONS_CHANGED -> {
                val since = (System.nanoTime() - treeAtNanos) / NANOS_PER_MS
                if (since < TREE_SESSIONS_EVERY_MS) TREE_SESSIONS_EVERY_MS - since else TREE_CHANGED_WAIT_MS
            }
            else -> return
        }
        if (treeRefetch?.isActive == true) return
        treeRefetch = work.launch {
            delay(wait)
            if (treeWanted() && state.value.link is LinkState.Online) loadTree()
        }
    }

    /** "HH:mm" in the phone's zone, for "PC offline since …". */
    fun timeText(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(now().zone).format(DateTimeFormatter.ofPattern(TIME, locale()))

    // ── search (over the loaded rows; the relay has no session search) ─────────────────────────────

    fun openSearch() = state.update { it.copy(searching = true) }

    fun search(query: String) {
        state.update { it.copy(query = query) }
        rebuild()
    }

    fun closeSearch() {
        state.update { it.copy(searching = false, query = "") }
        rebuild()
    }

    // ── history paging ─────────────────────────────────────────────────────────────────────────────

    /** The end of the history came into view, or the user tapped "Load more". */
    fun loadMore() {
        if (loadingMore || listing?.more != true) return
        if (repository.loadMore()) {
            loadingMore = true
            rebuild()
        }
    }

    // ── profiles (Q25) ─────────────────────────────────────────────────────────────────────────────

    fun openProfiles() {
        state.update { it.copy(picker = ProfilePicker()) }
        work.launch {
            val picker = try {
                ProfilePicker(options = repository.profiles())
            } catch (e: RpcException) {
                ProfilePicker(problem = reason(e))
            }
            state.update { if (it.picker != null) it.copy(picker = picker) else it }
        }
    }

    fun closeProfiles() = state.update { it.copy(picker = null) }

    /** Lists [option]'s sessions; the watcher follows ([selection]); remembered for the next start. */
    fun selectProfile(option: ProfileOption) {
        val profile = ProfileRoster.wire(option)
        state.update { it.copy(picker = null) }
        if (profile == state.value.profile) return
        selection.select(profile)
        applyProfile(profile)
    }

    /** The listed profile becomes [profile] (idempotent: the selection flow reports a pick a second time). */
    private fun applyProfile(profile: String?) {
        if (profile == state.value.profile) return
        listing = null
        loadingMore = false
        pinned = pins.of(pc.pcId, profile)
        tree?.cancel()
        treeRefetch?.cancel()
        projects = ProjectsInput()
        treePreview = 0
        state.update {
            it.copy(
                profile = profile, items = null, ledger = null, listedAt = null, listProblem = null, refreshing = false, actionsFor = null,
                renaming = null, view = views.of(pc.pcId, profile), costSort = false,
            )
        }
        repository.show(profile)
        if (treeWanted()) loadTree()
    }

    // ── row actions ────────────────────────────────────────────────────────────────────────────────

    fun open(row: SessionRow) = state.update { it.copy(open = OpenRequest(row.key, row.title, it.profile)) }

    /** The screen has shown the chat [open] asked for. */
    fun opened() = state.update { it.copy(open = null) }

    fun showActions(row: SessionRow) = state.update { it.copy(actionsFor = row) }

    fun closeActions() = state.update { it.copy(actionsFor = null) }

    /** Pins or unpins [row] on this phone. */
    fun togglePin(row: SessionRow) {
        pinned = pins.set(pc.pcId, state.value.profile, row.key, !row.pinned)
        state.update { it.copy(actionsFor = null) }
        rebuild()
    }

    fun startRename(row: SessionRow) = state.update { it.copy(actionsFor = null, renaming = row) }

    fun cancelRename() = state.update { if (it.renameBusy) it else it.copy(renaming = null) }

    /** Renames the row being renamed to [title] (trimmed; blank does nothing). */
    fun rename(title: String) {
        val current = state.value
        val row = current.renaming ?: return
        val text = title.trim()
        if (text.isEmpty() || current.renameBusy) return
        state.update { it.copy(renameBusy = true) }
        work.launch {
            try {
                repository.rename(current.profile, row.key, text)
                state.update { it.copy(renaming = null, renameBusy = false) }
                repository.reload()
            } catch (e: RpcException) {
                state.update { it.copy(renameBusy = false, message = SessionsMessage.RenameFailed(reason(e))) }
            }
        }
    }

    /** New session in the listed profile; its chat opens with the composer focused. */
    fun newSession() {
        val current = state.value
        if (current.creating) return
        state.update { it.copy(creating = true) }
        work.launch {
            try {
                val id = repository.create(current.profile)
                state.update { it.copy(creating = false, open = OpenRequest(id, null, current.profile, focusComposer = true)) }
            } catch (e: RpcException) {
                state.update { it.copy(creating = false, message = SessionsMessage.CreateFailed(reason(e))) }
            }
        }
    }

    fun messageShown() = state.update { it.copy(message = null) }

    // ── feed ───────────────────────────────────────────────────────────────────────────────────────

    private fun startFeed() {
        feed?.cancel()
        feed = work.launch { repository.feed().collect(::onFeed) }
    }

    private fun onLink(link: LinkState) {
        val wasOnline = state.value.link is LinkState.Online
        state.update {
            val address = when (link) {
                is LinkState.Online -> link.endpoint
                is LinkState.Offline -> link.address
                else -> null
            }
            it.copy(link = link, address = address ?: it.address, refreshing = it.refreshing && link is LinkState.Online)
        }
        // Back online: a tree that never came (or failed) is read again.
        if (link is LinkState.Online && !wasOnline && treeWanted() && projects.tree !is TreeLoad.Loaded) loadTree()
    }

    private fun onFeed(event: SessionFeedEvent) {
        when (event) {
            is SessionFeedEvent.Profile -> state.update { it.copy(launchProfile = event.name, listProblem = null) }
            is SessionFeedEvent.Sessions -> {
                if (event.profile != state.value.profile) return // a listing from before a switch
                // Drafts untouched for 30 days are pruned; absence from the list is not proof of deletion (T13 NIT).
                drafts?.prune()
                listing = event
                loadingMore = false
                val at = now()
                state.update { it.copy(listedAt = at.format(DateTimeFormatter.ofPattern(TIME, locale())), listProblem = null, refreshing = false) }
                rebuild()
            }
            is SessionFeedEvent.ListFailed -> if (event.profile == state.value.profile) {
                loadingMore = false
                val fellBack = event.unserved && event.profile != null && selection.fallBack(event.profile)
                if (fellBack) applyProfile(selection.selected.value) // the notice follows through [SessionsMessage.ProfileFellBack]
                else state.update { it.copy(listProblem = event.details, refreshing = false) }
            }
        }
    }

    /** The rows from the last listing, with this phone's pins and search over them. */
    private fun rebuild() {
        val current = listing ?: return
        val end = when {
            loadingMore -> HistoryEnd.Loading
            current.historyProblem != null -> HistoryEnd.Failed
            current.more -> HistoryEnd.More
            current.capped -> HistoryEnd.Capped
            else -> HistoryEnd.All
        }
        val ui = state.value
        val at = now()
        val items = sessionListItems(ListInput(current.live, current.stored, pinned, ui.query, end), at, locale())
        val tree = (projects.tree as? TreeLoad.Loaded)?.tree
        val sort = effectiveSort(ui.view, tree)
        val facts = HistoryFacts(current.stored, current.live, tree, projects.hydrated.values) { drafts?.get(it)?.isNotBlank() == true }
        val searching = ui.query.isNotBlank()
        val excluded = current.live.flatMap { it.ids }.toSet() + current.stored.filter { it.key in pinned }.flatMap { it.ids }
        val ledger = arrangeHistory(homeList(items), ui.view, sort, facts::facts, searching) {
            projectSections(projects, ui.view, sort, facts, excluded, ui.query, at, locale())
        }
        state.update { it.copy(items = items, ledger = ledger, costSort = costSortShown(tree)) }
    }

    private fun reason(e: RpcException): String = e.error?.let { error -> error.reason ?: error.message.ifBlank { null } } ?: e.message.orEmpty()

    private companion object {
        const val TIME = "HH:mm"
        const val TREE_CHANGED_WAIT_MS = 2_000L
        const val TREE_SESSIONS_EVERY_MS = 30_000L
        const val NANOS_PER_MS = 1_000_000L
    }
}

/** The rows of a list, in order. */
internal fun List<SessionListItem>.rows(): List<SessionRow> = filterIsInstance<SessionListItem.Row>().map { it.row }
