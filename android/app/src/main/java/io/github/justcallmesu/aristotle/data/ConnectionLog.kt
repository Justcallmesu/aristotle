package io.github.justcallmesu.aristotle.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Why the link went offline, as the history names it (Q73). A class only: never the server's own text. */
enum class OfflineReason { PcUnreachable, TailscaleMayBeOff, CertificateChanged, ConnectionLost, HermesNotRunning, HermesUpdated, Revoked, Superseded, AppError }

/**
 * One thing that happened to the link or to discovery (Q73). Addresses and kinds only: never a token, a fingerprint,
 * a session id or message text, so nothing secret can reach the history.
 */
sealed interface ConnectionEvent {
    data class Connected(val endpoint: ServerEndpoint) : ConnectionEvent
    data class Switched(val from: ServerEndpoint, val to: ServerEndpoint) : ConnectionEvent
    data class Offline(val reason: OfflineReason) : ConnectionEvent

    /** LAN discovery verified the PC at [endpoint]; [auto]: after a Wi-Fi join, else "Find PC on this Wi-Fi". */
    data class Found(val endpoint: ServerEndpoint, val auto: Boolean) : ConnectionEvent
    data object NotFound : ConnectionEvent
    data object CantScan : ConnectionEvent
    data class Saved(val endpoint: ServerEndpoint, val source: AddressSource) : ConnectionEvent
    data class Deleted(val endpoint: ServerEndpoint) : ConnectionEvent

    /** A LAN address that keeps dropping is tried after Tailscale for a while ([LanFlaps]). */
    data class Demoted(val endpoint: ServerEndpoint) : ConnectionEvent
}

/** Where the link and discovery report what happened ([ConnectionLog] in the app, a fake in tests). Must not block. */
fun interface ConnectionEvents {
    fun record(event: ConnectionEvent)

    companion object {
        val None = ConnectionEvents { }
    }
}

enum class LogKind { Connected, Switched, Offline, Found, NotFound, CantScan, Saved, Deleted, Demoted }

/** One stored line of the history: its time, its kind and the few fields the line shows. */
@Serializable
data class LogEntry(
    val at: Long,
    val kind: LogKind,
    val address: String? = null,
    val network: Network? = null,
    val from: String? = null,
    val reason: OfflineReason? = null,
    val source: AddressSource? = null,
    val auto: Boolean = false,
) {
    companion object {
        fun of(event: ConnectionEvent, at: Long): LogEntry = when (event) {
            is ConnectionEvent.Connected -> LogEntry(at, LogKind.Connected, event.endpoint.displayAddress, event.endpoint.network)
            is ConnectionEvent.Switched ->
                LogEntry(at, LogKind.Switched, event.to.displayAddress, event.to.network, from = event.from.displayAddress)
            is ConnectionEvent.Offline -> LogEntry(at, LogKind.Offline, reason = event.reason)
            is ConnectionEvent.Found -> LogEntry(at, LogKind.Found, event.endpoint.displayAddress, event.endpoint.network, auto = event.auto)
            ConnectionEvent.NotFound -> LogEntry(at, LogKind.NotFound)
            ConnectionEvent.CantScan -> LogEntry(at, LogKind.CantScan)
            is ConnectionEvent.Saved -> LogEntry(at, LogKind.Saved, event.endpoint.displayAddress, event.endpoint.network, source = event.source)
            is ConnectionEvent.Deleted -> LogEntry(at, LogKind.Deleted, event.endpoint.displayAddress, event.endpoint.network)
            is ConnectionEvent.Demoted -> LogEntry(at, LogKind.Demoted, event.endpoint.displayAddress, event.endpoint.network)
        }
    }
}

/** The history as Settings reads it. */
interface ConnectionHistory {
    /** Newest first. */
    val entries: StateFlow<List<LogEntry>>

    suspend fun clear()
}

/**
 * The connection history on this phone (Q73): append-only, newest first, at most [maxEntries] lines and none older than
 * [maxAgeMillis] (pruned on every write and on load). Kept in its own app-private preferences file, which the backup
 * rules exclude like the credentials (`res/xml/data_extraction_rules.xml`), so it survives restarts and never leaves
 * the phone. [record] only queues: a bounded queue (the oldest event goes when it is full) is written on [io] by one
 * coroutine, so the link never waits for a disk. [paired] false (no PC is paired, e.g. after Unpair) drops the event. [clear] (Unpair, "Clear history") also drops what is still queued.
 */
class ConnectionLog(
    private val storage: KeyValueStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val maxEntries: Int = MAX_ENTRIES,
    private val maxAgeMillis: Long = MAX_AGE_MILLIS,
    private val paired: () -> Boolean = { true },
) : ConnectionEvents, ConnectionHistory {
    private class Pending(val entry: LogEntry, val generation: Int)

    private val queue = Channel<Pending>(QUEUE, BufferOverflow.DROP_OLDEST)
    private val mutex = Mutex()
    private val current = MutableStateFlow<List<LogEntry>>(emptyList())
    override val entries: StateFlow<List<LogEntry>> = current.asStateFlow()

    @Volatile
    private var generation = 0

    private val json = Json { ignoreUnknownKeys = true }

    /** Reads the stored history, then writes what is queued; call once. */
    fun start(): Job = scope.launch(io) {
        mutex.withLock { current.value = prune(read()) }
        for (pending in queue) {
            mutex.withLock {
                if (pending.generation != generation || !paired()) return@withLock
                val next = prune(listOf(pending.entry) + current.value)
                write(next)
                current.value = next
            }
        }
    }

    override fun record(event: ConnectionEvent) {
        if (!paired()) return
        queue.trySend(Pending(LogEntry.of(event, clock()), generation))
    }

    override suspend fun clear() = withContext(io) {
        mutex.withLock {
            generation++
            storage.edit(mapOf(KEY to null), durable = true)
            current.value = emptyList()
        }
    }

    private fun prune(entries: List<LogEntry>): List<LogEntry> {
        val now = clock()
        return entries.filter { now - it.at < maxAgeMillis && it.at <= now + MAX_AHEAD_MILLIS }.take(maxEntries)
    }

    /** One JSON object per line; a line this build cannot read is skipped, never the whole history. */
    private fun read(): List<LogEntry> = storage.get(KEY).orEmpty().lineSequence()
        .filter { it.isNotBlank() }
        .mapNotNull { line -> runCatching { json.decodeFromString(LogEntry.serializer(), line) }.getOrNull() }
        .sortedByDescending { it.at }
        .toList()

    private fun write(entries: List<LogEntry>) {
        storage.put(KEY, entries.joinToString("\n") { json.encodeToString(LogEntry.serializer(), it) })
    }

    companion object {
        const val MAX_ENTRIES = 200
        const val MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000
        private const val QUEUE = 64

        /** A line dated further ahead than this (the clock was moved back) is dropped like an old one. */
        const val MAX_AHEAD_MILLIS = 24L * 60 * 60 * 1000
        private const val KEY = "entries"
    }
}
