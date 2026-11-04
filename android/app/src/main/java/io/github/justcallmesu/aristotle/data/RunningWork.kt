package io.github.justcallmesu.aristotle.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One row of `session.active_list` (protocol §17; `gateway/session_active_list.py:39-43`, the sidecar's
 * `tui_gateway/session_registry.py:679-686`). [id] is the live session id the chat opens; [sessionKey] is the same id
 * on the authority and the agent's stored id on the sidecar. Hermes sends no `resolved_id` today; it is read in case it
 * comes, since `subagent.roster` keys by STORED id (TX1 §1 risk).
 */
@Serializable
data class ActiveSession(
    val id: String = "",
    @SerialName("session_key") val sessionKey: String? = null,
    @SerialName("resolved_id") val resolvedId: String? = null,
    val title: String = "",
    val status: String = "",
    @SerialName("started_at") val startedAt: Double? = null,
    @SerialName("last_active") val lastActive: Double? = null,
    @SerialName("message_count") val messageCount: Int = 0,
) {
    /** Every id a roster row may name this session by. */
    val ids: Set<String> get() = setOfNotNull(id.ifEmpty { null }, sessionKey?.ifEmpty { null }, resolvedId?.ifEmpty { null })
}

@Serializable
data class ActiveListResult(val sessions: List<ActiveSession> = emptyList())

/** One row of `subagent.roster` (`tui_gateway/methods_subagents.py:114-138`): running children and processes of a stored session. */
@Serializable
data class RosterEntry(
    @SerialName("session_id") val sessionId: String = "",
    val subagents: Int = 0,
    val background: Int = 0,
)

@Serializable
data class RosterResult(val sessions: List<RosterEntry> = emptyList())

/** `session.active_list` status (`gateway/session_active_list.py:15-20`); anything else reads as idle. Order = list order. */
enum class RunningStatus {
    Waiting, Working, Starting, Idle;

    companion object {
        fun of(wire: String): RunningStatus = when (wire) {
            "waiting" -> Waiting
            "working" -> Working
            "starting" -> Starting
            else -> Idle
        }
    }
}

/**
 * One piece of running work on the Running page. [sessionId] is what the chat opens: the live id, or the roster's stored
 * id when no live row matched ([live] false). [wireProfile]: what calls send for the profile (null = launch, T14).
 */
data class RunningRow(
    val profile: String,
    val wireProfile: String?,
    val sessionId: String,
    val title: String?,
    val status: RunningStatus,
    val subagents: Int,
    val background: Int,
    /** Seconds since the epoch, `last_active` else `started_at`. */
    val lastActivity: Double?,
    val live: Boolean = true,
) {
    val hasWork: Boolean get() = subagents > 0 || background > 0
}

/** One profile's rows, most urgent first. */
data class RunningGroup(val profile: ProfileOption, val rows: List<RunningRow>)

/** One read of every profile. [failed]: the display names of profiles whose `active_list` or `roster` call failed. */
data class RunningSnapshot(val groups: List<RunningGroup>, val failed: List<String> = emptyList()) {
    val count: Int get() = groups.sumOf { it.rows.size }
}

/** What one profile answered; null = that call failed. */
data class ProfileRead(val profile: ProfileOption, val active: List<ActiveSession>?, val roster: List<RosterEntry>?)

/** The Running page's calls (protocol §17), each with only the keys Hermes accepts. [profile] null = launch profile. */
object RunningFrames {
    /** `session.active_list` refuses keys other than `profile` / `current_session_id` (`gateway/session_active_list.py:24-25`). */
    fun activeList(profile: String?): JsonObject = buildJsonObject { profile?.let { put("profile", it) } }

    /** `subagent.roster {profile?}` (`tui_gateway/methods_subagents.py:114-138`). */
    fun roster(profile: String?): JsonObject = buildJsonObject { profile?.let { put("profile", it) } }
}

/** The fan-out merge, id matching, filter, sort and grouping of the Running page. Pure. */
object RunningMerge {
    /**
     * [read]'s rows. A roster row counts for the live session that answers to its id on `id`, `session_key` or
     * `resolved_id` (the first such session; a roster id seen twice is summed). Live rows show while their turn runs,
     * waits or starts; idle ones only with subagents or background work. Roster rows no live session claims still show
     * (work whose session is not live, e.g. a background process left behind), named by their stored id.
     */
    fun rows(read: ProfileRead): List<RunningRow> {
        val wire = ProfileRoster.wire(read.profile)
        val counts = LinkedHashMap<String, IntArray>()
        read.roster.orEmpty().filter { it.sessionId.isNotEmpty() }.forEach { entry ->
            val sum = counts.getOrPut(entry.sessionId) { IntArray(2) }
            sum[0] += entry.subagents.coerceAtLeast(0)
            sum[1] += entry.background.coerceAtLeast(0)
        }
        val claimed = HashSet<String>()
        val live = read.active.orEmpty().filter { it.id.isNotEmpty() }.distinctBy { it.id }.map { session ->
            var subagents = 0
            var background = 0
            session.ids.forEach { id ->
                val sum = counts[id]
                if (sum != null && claimed.add(id)) {
                    subagents += sum[0]
                    background += sum[1]
                }
            }
            RunningRow(
                profile = read.profile.name,
                wireProfile = wire,
                sessionId = session.id,
                title = session.title.trim().ifEmpty { null },
                status = RunningStatus.of(session.status),
                subagents = subagents,
                background = background,
                lastActivity = session.lastActive ?: session.startedAt,
            )
        }
        val orphans = counts.filterKeys { it !in claimed }.map { (id, sum) ->
            RunningRow(read.profile.name, wire, id, null, RunningStatus.Idle, sum[0], sum[1], lastActivity = null, live = false)
        }
        return sort((live + orphans).filter { it.status != RunningStatus.Idle || it.hasWork })
    }

    /** Waiting first, then working, starting, idle-with-work; within each, the latest activity first. */
    val order: Comparator<RunningRow> = compareBy<RunningRow> { it.status.ordinal }
        .thenByDescending { it.lastActivity ?: Double.NEGATIVE_INFINITY }
        .thenBy { it.sessionId }

    fun sort(rows: List<RunningRow>): List<RunningRow> = rows.sortedWith(order)

    /**
     * Groups by profile; a profile with nothing running is left out. Groups follow their most urgent row ([order]); equal
     * ones keep Hermes' profile order (its own first).
     */
    fun snapshot(reads: List<ProfileRead>): RunningSnapshot {
        val groups = reads.map { RunningGroup(it.profile, rows(it)) }.filter { it.rows.isNotEmpty() }
        val sorted = groups.withIndex().sortedWith { a, b ->
            order.compare(a.value.rows.first(), b.value.rows.first()).takeIf { it != 0 } ?: a.index.compareTo(b.index)
        }.map { it.value }
        val failed = reads.filter { it.active == null || it.roster == null }.map { it.profile.displayName.ifEmpty { it.profile.name } }
        return RunningSnapshot(sorted, failed)
    }
}

/**
 * The fan-out (Q82 D1, TX1 §1): `profiles.list`, then per profile `session.active_list` + `subagent.roster` at once,
 * so 1 + 2×P small calls per read. A failed profile call is kept as [RunningSnapshot.failed]; the other profiles still
 * show. Throws [RpcException] (or IllegalArgumentException for an unreadable answer) only when `profiles.list` fails.
 */
class RunningWork(private val link: RelayLink) {
    suspend fun fetch(): RunningSnapshot = coroutineScope {
        val profiles = ProfileRoster.of(link.call("profiles.list", SessionFrames.profiles()))
            .ifEmpty { listOf(ProfileOption(name = "", displayName = "", own = true)) } // a Hermes with no roster: the launch profile
        val reads = profiles.map { profile -> async { read(profile) } }.awaitAll()
        RunningMerge.snapshot(reads)
    }

    private suspend fun read(profile: ProfileOption): ProfileRead = coroutineScope {
        val wire = ProfileRoster.wire(profile)
        val active = async {
            attempt { HermesJson.decodeFromJsonElement(ActiveListResult.serializer(), link.call(ACTIVE_LIST, RunningFrames.activeList(wire))).sessions }
        }
        val roster = async {
            attempt { HermesJson.decodeFromJsonElement(RosterResult.serializer(), link.call(ROSTER, RunningFrames.roster(wire))).sessions }
        }
        ProfileRead(profile, active.await(), roster.await())
    }

    /** The call's answer, or null when Hermes refused it, it timed out or its answer was unreadable. Cancellation passes. */
    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (_: RpcException) {
        null
    } catch (_: IllegalArgumentException) {
        null // SerializationException: an answer of another shape
    }

    companion object {
        const val ACTIVE_LIST = "session.active_list"
        const val ROSTER = "subagent.roster"
    }
}

/**
 * [pollMillis]: the Desktop's roster poll while visible (`use-live-work-roster.ts:118`). [changeWindowMillis]: after
 * `sessions.changed` (it fires on every message-count move), one read per window.
 */
data class RunningTimings(val pollMillis: Long = 10_000, val changeWindowMillis: Long = 3_000)

/**
 * When the Running page reads (Q82 D1): only while it is visible AND the link is online. Becoming both reads at once;
 * then every [RunningTimings.pollMillis] after the last read, sooner after `sessions.changed` (coalesced per
 * [RunningTimings.changeWindowMillis]) or a [kick] (pull to refresh). Hidden or offline: nothing runs, and a read under
 * way is cancelled. Reads never overlap.
 */
class RunningPoller(
    private val link: RelayLink,
    private val timings: RunningTimings,
    scope: CoroutineScope,
    private val read: suspend () -> Unit,
) {
    private val visible = MutableStateFlow(false)
    private val kicks = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            combine(visible, link.state.map { (it as? LinkState.Online)?.generation }.distinctUntilChanged()) { shown, generation -> generation.takeIf { shown } }
                .distinctUntilChanged()
                .collectLatest { generation -> if (generation != null) loop() }
        }
    }

    fun setVisible(shown: Boolean) {
        visible.value = shown
    }

    /** Read now (if the poll runs). */
    fun kick() {
        kicks.trySend(Unit)
    }

    private suspend fun loop() = coroutineScope {
        var window: Job? = null
        launch(start = CoroutineStart.UNDISPATCHED) {
            link.events.collect { frame ->
                if (frame is InboundFrame.Event && frame.type == RpcFrames.SESSIONS_CHANGED && window?.isActive != true) {
                    window = launch {
                        delay(timings.changeWindowMillis)
                        kick()
                    }
                }
            }
        }
        kicks.tryReceive() // a kick from before is covered by this first read
        while (true) {
            read()
            withTimeoutOrNull(timings.pollMillis) { kicks.receive() }
        }
    }
}
