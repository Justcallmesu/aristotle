package io.github.justcallmesu.aristotle.data.stocks

import io.github.justcallmesu.aristotle.data.CredentialState
import io.github.justcallmesu.aristotle.data.KeyValueStore
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.PcCredentials
import io.github.justcallmesu.aristotle.data.ServerEndpoint
import io.github.justcallmesu.aristotle.data.serverEndpoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// Stock alerts (protocol §20.4, TBA1 server; TSC0 §2 delivery): the phone long-polls `GET /v1/stocks/alerts`, keeps the
// cursor (`after`) and the alerts it has read, and turns the new ones into notifications. Pure apart from the models.

/**
 * One alert of the server's ring. [title] and [text] are the server's words in the phone's last language (plain text);
 * [silent] alerts are kept for the list and never notified (quiet hours or over a cap). [detail] carries the kind's
 * numbers; the phone reads only `detail.date` of a brief alert.
 */
@Serializable
data class StockAlert(
    val seq: Long = 0,
    val id: String = "",
    val kind: String = "",
    val code: String? = null,
    val title: String = "",
    val text: String = "",
    val at: String = "",
    @SerialName("data_as_of") val dataAsOf: String? = null,
    val silent: Boolean = false,
    val detail: JsonObject? = null,
) {
    val alertKind: AlertKind get() = AlertKind.of(kind)

    /** The brief's day (`detail.date`) of a `brief` alert. */
    val briefDate: String? get() = (detail?.get("date") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    /** The key the list is deduplicated by: the server's id, else the seq. */
    val key: String get() = id.ifEmpty { "seq:$seq" }
}

enum class AlertKind(val wire: String) {
    Level("level"),
    News("news"),
    Setup("setup"),
    Analysis("analysis"),
    Brief("brief"),
    Summary("summary"),

    /** A kind this app does not know yet: listed and notified like any alert, opening its stock or the list. */
    Other(""),
    ;

    companion object {
        fun of(wire: String): AlertKind = entries.firstOrNull { it.wire == wire && it != Other } ?: Other
    }
}

/** Today's automatic analyses (Q87: at most [cap] a day, reset at 00:00 WIB). */
@Serializable
data class AutoRuns(val date: String = "", val used: Int = 0, val cap: Int = DEFAULT_CAP, val codes: List<String> = emptyList()) {
    companion object {
        const val DEFAULT_CAP = 5
    }
}

/** `GET /v1/stocks/alerts`: items oldest first; [reset] = the cursor was ahead of the ring (the PC lost its file). */
@Serializable
data class AlertsBody(
    val seq: Long = 0,
    val items: List<StockAlert> = emptyList(),
    @SerialName("auto_runs") val autoRuns: AutoRuns? = null,
    val reset: Boolean = false,
)

/** `GET /v1/stocks/jobs` with its `auto_runs` (§20.2). */
@Serializable
data class JobsWithRuns(val jobs: List<JobView> = emptyList(), @SerialName("auto_runs") val autoRuns: AutoRuns? = null) {
    fun job(id: String): JobView? = jobs.firstOrNull { it.id == id }

    companion object {
        const val ALERTS = "alerts"
        const val BRIEF = "brief"
    }
}

/**
 * What this phone holds of the ring: [items] newest first, deduplicated, at most [AlertFeeds.MAX]; [seenSeq] the newest
 * alert the user has looked at (the list page), so Stocks home can say "N new alerts".
 */
data class AlertFeed(val items: List<StockAlert> = emptyList(), val seenSeq: Long? = null, val autoRuns: AutoRuns? = null) {
    /** Alerts after the last look; none before the first read (a first read is history, not news). */
    val unseen: Int get() = seenSeq?.let { seen -> items.count { it.seq > seen } } ?: 0
}

/** The list and notification rules, pure. */
object AlertFeeds {
    /** Alerts kept on the phone (the server keeps 300 / 7 days; its list call answers the newest 50). */
    const val MAX = 100

    /** The server holds a long-poll at most this long (§20.4 `wait` 0..50). */
    const val WAIT_SECONDS = 50

    /** Older alerts read later (the phone was away, Q109) go to the list only: a 3-hour-old stop is no heads-up. */
    const val FRESH_MILLIS = 30 * 60 * 1000L

    /** [incoming] over [current], newest first, each alert once; a [reset] ring replaces what was held (its seqs restarted). */
    fun merge(current: List<StockAlert>, incoming: List<StockAlert>, reset: Boolean): List<StockAlert> {
        val base = if (reset) emptyList() else current
        return (incoming + base).distinctBy { it.key }.sortedByDescending { it.seq }.take(MAX)
    }

    /**
     * The alerts of a long-poll answer to notify: newer than [after], not silent, at most [FRESH_MILLIS] old (a time the
     * phone cannot read is not fresh). Nothing on the first read ([after] null: history) or on a [AlertsBody.reset].
     */
    fun toNotify(body: AlertsBody, after: Long?, nowMillis: Long): List<StockAlert> {
        if (after == null || body.reset) return emptyList()
        return body.items.filter { it.seq > after && !it.silent && fresh(it, nowMillis) }
    }

    fun fresh(alert: StockAlert, nowMillis: Long): Boolean {
        val at = StockTimes.parse(alert.at)?.toInstant()?.toEpochMilli() ?: return false
        return nowMillis - at <= FRESH_MILLIS
    }
}

/**
 * When the phone long-polls (Q109): only while paired and aristotle-server answers, and then while the app is on screen
 * or the "keep connected" service keeps the process ([keepRunning]: the setting on, not stopped from the notification).
 * With "keep connected" off, alerts arrive only while the app is open; missed ones are read on the next open.
 */
object AlertPolicy {
    fun polling(paired: Boolean, serverReachable: Boolean, appVisible: Boolean, keepRunning: Boolean): Boolean =
        paired && serverReachable && (appVisible || keepRunning)

    /** The PC and address to long-poll now, or null (Q109): [credentials] paired, [link] answering, [visible] or [keep]. */
    fun target(credentials: CredentialState, link: LinkState, visible: Boolean, keep: Boolean): AlertTarget? {
        val pc = (credentials as? CredentialState.Paired)?.credentials
        val endpoint = link.serverEndpoint
        return if (pc != null && endpoint != null && polling(paired = true, serverReachable = true, appVisible = visible, keepRunning = keep)) AlertTarget(pc, endpoint) else null
    }
}

/** The PC and the address the alerts long-poll goes to. */
data class AlertTarget(val pc: PcCredentials, val endpoint: ServerEndpoint)

/**
 * The phone's alert state for the active PC: the long-poll cursor (`after`, persisted so a restart neither floods nor
 * loses alerts), the alerts read, and the last look. Kept per PC: another PC's cursor is never used; Unpair clears it.
 * Thread-safe: the watcher writes from its coroutine, the screens read [feed] and mark it seen.
 */
class StockAlertStore(private val store: KeyValueStore) {
    private val state = MutableStateFlow(AlertFeed(seenSeq = store.get(SEEN)?.toLongOrNull()))
    val feed: StateFlow<AlertFeed> = state.asStateFlow()

    /**
     * Bumped by [clear]. The watcher reads it before each poll and hands it back to [take], so an answer that was on its
     * way while the user unpaired is dropped instead of writing the old PC's cursor and alerts back.
     */
    val generation: Long @Synchronized get() = epoch

    /** The cursor for [pcId]; null before the first read of that PC. */
    @Synchronized
    fun cursor(pcId: String): Long? = if (store.get(PC) == pcId) store.get(CURSOR)?.toLongOrNull() else null

    /**
     * Takes one answer for [pcId] read with [after]: the list gets the items, the cursor moves to the answer's seq, and the
     * alerts to notify come back ([AlertFeeds.toNotify]). The first read of a PC (or a reset) also marks everything seen.
     * An answer read under an older [generation] (a [clear] came in between) is dropped: nothing written, nothing returned.
     */
    @Synchronized
    fun take(pcId: String, after: Long?, body: AlertsBody, nowMillis: Long, generation: Long): List<StockAlert> {
        if (generation != epoch) return emptyList()
        val first = after == null || body.reset
        if (store.get(PC) != pcId) state.value = AlertFeed()
        store.edit(mapOf(PC to pcId, CURSOR to body.seq.toString()) + if (first) mapOf(SEEN to body.seq.toString()) else emptyMap())
        state.update { feed ->
            feed.copy(
                items = AlertFeeds.merge(feed.items, body.items, body.reset),
                seenSeq = if (first) body.seq else feed.seenSeq,
                autoRuns = body.autoRuns ?: feed.autoRuns,
            )
        }
        return AlertFeeds.toNotify(body, after, nowMillis)
    }

    /** The list page's own read (newest 50, no cursor): merged in, the cursor untouched. */
    @Synchronized
    fun list(body: AlertsBody) {
        state.update { it.copy(items = AlertFeeds.merge(it.items, body.items, reset = false), autoRuns = body.autoRuns ?: it.autoRuns) }
    }

    /** The user looked at the list: nothing is new any more. */
    @Synchronized
    fun markSeen() {
        val newest = state.value.items.maxOfOrNull { it.seq } ?: return
        if ((state.value.seenSeq ?: -1) >= newest) return
        store.put(SEEN, newest.toString())
        state.update { it.copy(seenSeq = newest) }
    }

    /** Unpair, or another PC: no cursor, no alerts. */
    @Synchronized
    fun clear() {
        epoch++
        store.edit(mapOf(PC to null, CURSOR to null, SEEN to null))
        state.value = AlertFeed()
    }

    private var epoch = 0L

    private companion object {
        const val PC = "alerts_pc"
        const val CURSOR = "alerts_after"
        const val SEEN = "alerts_seen"
    }
}
