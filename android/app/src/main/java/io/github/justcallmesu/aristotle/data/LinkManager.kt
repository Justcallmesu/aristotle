package io.github.justcallmesu.aristotle.data

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** What screens use of the one link to the PC; [LinkManager] in the app, a fake in tests. */
interface RelayLink {
    val state: StateFlow<LinkState>

    /** Events and server requests from the live relay, as they arrive. Nothing is replayed. */
    val events: SharedFlow<InboundFrame>

    /** A JSON-RPC call on the live relay; throws [RpcException] (`linkClosed`) when there is none. */
    suspend fun call(method: String, params: JsonObject = JsonObject(emptyMap())): JsonElement

    /** The user asked to try now (Retry now, Check again). */
    fun retryNow()
}

/** The two calls the link makes to a PC address; [HttpLinkDialer] in the app, fakes in tests. */
interface LinkDialer {
    fun openRelay(pc: PcCredentials, endpoint: ServerEndpoint): RelayChannel

    /** `GET /v1/addresses`; null when it failed. */
    suspend fun addresses(pc: PcCredentials, endpoint: ServerEndpoint): List<ServerEndpoint>?

    /** The phone's network changed: pooled connections lead nowhere now. */
    fun networkChanged() {}
}

/**
 * Stores what the link learnt about a PC: [fresh], the addresses it serves now (empty when unknown), merged
 * into the stored ones (never replacing them, T09b), and [lastGood], the one that worked.
 */
fun interface LinkMemory {
    suspend fun remember(pcId: String, fresh: List<ServerEndpoint>, lastGood: ServerEndpoint)
}

data class LinkTimings(
    val readyTimeoutMillis: Long = 20_000,
    val hermesDownRetryMillis: Long = 10_000,
    val rpcTimeoutMillis: Long = 15_000,
    val backoff: Backoff = Backoff(),
)

/**
 * The process-wide link to the paired PC (T09), owned by the Application: one relay socket at a time.
 * Tries the PC's addresses in [EndpointChoice] order, reconnects after a loss with [Backoff] (reset once a
 * relay delivers `gateway.ready`), and at once when the phone's network changes, the app returns to the
 * foreground or the user asks. No retry after `4401` (wipe) or `4505`; `4503` is retried every 10 s.
 * [stop] (the background notification's Stop, T10) closes the relay until [onForeground].
 * Frames are handed off OkHttp's reader thread at once ([RelaySocket]) and parsed here (protocol §12).
 * An unexpected error in a round shows as [OfflineCause.AppError] and the next round runs: the link never dies.
 */
class LinkManager(
    private val scope: CoroutineScope,
    private val credentials: StateFlow<CredentialState>,
    private val dialer: LinkDialer,
    private val network: NetworkWatch,
    private val memory: LinkMemory,
    private val timings: LinkTimings = LinkTimings(),
    private val clock: () -> Long = System::currentTimeMillis,
) : RelayLink {
    private val current = MutableStateFlow<LinkState>(LinkState.Idle)
    override val state: StateFlow<LinkState> = current.asStateFlow()

    private val frames = MutableSharedFlow<InboundFrame>(extraBufferCapacity = FRAME_BUFFER)
    override val events: SharedFlow<InboundFrame> = frames.asSharedFlow()

    private val wakes = Channel<Wake>(Channel.UNLIMITED)
    private val stopped = MutableStateFlow(false)

    @Volatile
    private var live: RelayRpc? = null
    private var generation = 0L

    private enum class Wake { User, Network, Foreground }

    /** Starts following the stored pairing; call once. */
    fun start(): Job = scope.launch {
        launch {
            network.changes.collect {
                wakes.trySend(Wake.Network)
                unlessCancelled { dialer.networkChanged() }
            }
        }
        combine(credentials.map { (it as? CredentialState.Paired)?.credentials }, stopped, ::Target)
            // The link updates the stored addresses itself; only another PC, token or a stop restarts it.
            .distinctUntilChanged { a, b -> a.pc?.pcId == b.pc?.pcId && a.pc?.token == b.pc?.token && a.stopped == b.stopped }
            .collectLatest { target ->
                when {
                    target.pc == null -> current.value = LinkState.Idle
                    target.stopped -> current.value = LinkState.Stopped
                    else -> PcSession(target.pc).run()
                }
            }
    }

    /** The app is in front again: a stopped link starts, one waiting to retry tries now. */
    fun onForeground() {
        stopped.value = false
        wakes.trySend(Wake.Foreground)
    }

    /** Closes the relay and stays off until [onForeground]; the user stopped the background link (T10). */
    fun stop() {
        stopped.value = true
    }

    override fun retryNow() {
        wakes.trySend(Wake.User)
    }

    override suspend fun call(method: String, params: JsonObject): JsonElement =
        (live ?: throw RpcException(null, "$method: not connected", linkClosed = true)).call(method, params)

    /** One paired PC, from pairing (or start) until it is forgotten. */
    private inner class PcSession(private val paired: PcCredentials) {
        private var failures = 0
        private var offlineSince: Long? = null

        suspend fun run() {
            current.value = LinkState.Connecting
            while (true) {
                when (val end = nextRound()) {
                    RoundEnd.Revoked -> {
                        current.value = LinkState.Revoked
                        return
                    }
                    is RoundEnd.HermesUpdated -> {
                        reached()
                        current.value = LinkState.HermesUpdated(end.details)
                        awaitUser()
                    }
                    is RoundEnd.HermesDown -> {
                        reached()
                        current.value = LinkState.HermesDown(end.details)
                        sleep(timings.hermesDownRetryMillis)
                    }
                    RoundEnd.Switch -> current.value = LinkState.Connecting
                    is RoundEnd.Lost -> backOff(end)
                }
            }
        }

        /** One round over the PC's addresses, best first; an unexpected error ends it like a loss. */
        private suspend fun nextRound(): RoundEnd = try {
            drainWakes()
            val pc = latest()
            round(pc, EndpointChoice.order(pc.addresses, pc.lastGood, network.prefersLan()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LinkEnds.unexpected(e)
        }

        /** The stored entry as it is now (the link itself updates its addresses); [paired] if it is gone. */
        private fun latest(): PcCredentials =
            (credentials.value as? CredentialState.Paired)?.credentials?.takeIf { it.pcId == paired.pcId } ?: paired

        private suspend fun round(pc: PcCredentials, order: List<ServerEndpoint>): RoundEnd {
            val misses = mutableListOf<Miss>()
            for (endpoint in order) {
                when (val result = attempt(pc, endpoint)) {
                    is Attempt.Missed -> misses += result.miss
                    is Attempt.Ended -> return result.end
                }
            }
            val pcHasLan = pc.addresses.any { it.network == Network.Lan }
            return LinkEnds.offline(misses, pcHasLan, network.prefersLan(), network.vpnUp())
        }

        /** One address: open the relay, wait for `gateway.ready`, then stay online until the relay ends. */
        private suspend fun attempt(pc: PcCredentials, endpoint: ServerEndpoint): Attempt = coroutineScope {
            val socket = dialer.openRelay(pc, endpoint)
            val rpc = RelayRpc(socket::send, timings.rpcTimeoutMillis)
            val ready = CompletableDeferred<Unit>()
            val ended = CompletableDeferred<SocketEnd>()
            val opened = AtomicBoolean(false)
            val pump = launch {
                socket.events().collect { event ->
                    when (event) {
                        RelayEvent.Opened -> opened.set(true)
                        is RelayEvent.Text -> route(RpcFrames.parse(event.text), rpc, ready)
                        is RelayEvent.Closed -> ended.complete(SocketEnd.Closed(event.code, event.reason))
                        is RelayEvent.Failed -> ended.complete(SocketEnd.Failed(event.httpStatus, event.problem))
                    }
                }
                ended.complete(SocketEnd.Closed(ABNORMAL_CLOSURE, ""))
            }
            val start: Start = withTimeoutOrNull(timings.readyTimeoutMillis) {
                select<Start> { ready.onAwait { Start.Ready }; ended.onAwait { Start.Ended(it) } }
            } ?: Start.TimedOut(opened.get())
            val result = when (start) {
                Start.Ready -> Attempt.Ended(online(pc, endpoint, rpc, ended))
                is Start.Ended -> notReady(endpoint, start.end)
                is Start.TimedOut -> timedOut(endpoint, start.opened)
            }
            if ((result as? Attempt.Ended)?.end == RoundEnd.Switch) socket.cancel() // its network is gone: no handshake
            pump.cancel() // closes the socket (1000) if it is open, drops it if it is still connecting
            result
        }

        private suspend fun route(frame: InboundFrame, rpc: RelayRpc, ready: CompletableDeferred<Unit>) {
            when (frame) {
                is InboundFrame.Response -> rpc.onResponse(frame)
                is InboundFrame.Event -> {
                    if (frame.type == RpcFrames.GATEWAY_READY) ready.complete(Unit)
                    frames.emit(frame)
                }
                is InboundFrame.ServerRequest -> frames.emit(frame)
                InboundFrame.Unknown -> Unit
            }
        }

        /** The relay ended before `gateway.ready`: a close frame means the PC answered; a failure is a miss. */
        private fun notReady(endpoint: ServerEndpoint, end: SocketEnd): Attempt = when (end) {
            is SocketEnd.Closed -> Attempt.Ended(LinkEnds.ofClose(end.code, end.reason, endpoint))
            is SocketEnd.Failed -> LinkEnds.missOf(endpoint, end.httpStatus, end.problem)?.let(Attempt::Missed)
                ?: Attempt.Ended(RoundEnd.Revoked)
        }

        /** Open but silent: the PC answered and Hermes did not; never opened: this address is a miss. */
        private fun timedOut(endpoint: ServerEndpoint, opened: Boolean): Attempt {
            val wait = seconds(timings.readyTimeoutMillis)
            return if (opened) {
                Attempt.Ended(RoundEnd.Lost(OfflineCause.ConnectionLost, "no gateway.ready within $wait", endpoint))
            } else {
                Attempt.Missed(Miss(endpoint, MissKind.Unreachable, "no answer within $wait"))
            }
        }

        private suspend fun online(
            pc: PcCredentials,
            endpoint: ServerEndpoint,
            rpc: RelayRpc,
            ended: CompletableDeferred<SocketEnd>,
        ): RoundEnd {
            reached()
            live = rpc
            current.value = LinkState.Online(endpoint, ++generation)
            // Not joined: the link moves on the moment the relay ends, whatever the refresh is doing.
            val refresh = scope.launch { refreshAddresses(pc, endpoint) }
            try {
                var end: RoundEnd? = null
                while (end == null) {
                    end = select<RoundEnd?> {
                        ended.onAwait { socketEnd -> endOf(socketEnd, endpoint) }
                        wakes.onReceive { wake -> if (wake == Wake.Network) RoundEnd.Switch else null }
                    }
                }
                return end
            } finally {
                refresh.cancel()
                live = null
                rpc.failAll("relay closed")
            }
        }

        private fun endOf(end: SocketEnd, endpoint: ServerEndpoint): RoundEnd = when (end) {
            is SocketEnd.Closed -> LinkEnds.ofClose(end.code, end.reason, endpoint)
            is SocketEnd.Failed -> LinkEnds.ofFailure(end.httpStatus, end.problem, endpoint)
        }

        /**
         * Remembers the address that worked and merges in the PC's current list (a DHCP change, Tailscale
         * added). Best effort: when it fails the stored list stays, and the working relay is not ended for it.
         */
        private suspend fun refreshAddresses(pc: PcCredentials, endpoint: ServerEndpoint) = unlessCancelled {
            val fresh = dialer.addresses(pc, endpoint).orEmpty()
            if (fresh.isNotEmpty() || endpoint != pc.lastGood) memory.remember(pc.pcId, fresh, endpoint)
        }

        private suspend fun backOff(end: RoundEnd.Lost) {
            failures++
            val since = offlineSince ?: clock().also { offlineSince = it }
            val delay = timings.backoff.delayMillis(failures)
            current.value = LinkState.Offline(since, end.cause, end.details, end.address, failures, clock() + delay)
            sleep(delay)
            current.update { if (it is LinkState.Offline) it.copy(nextAttemptAtMillis = null) else it }
        }

        /** The PC answered: it is not offline (whatever Hermes said), and a later loss backs off from the start. */
        private fun reached() {
            offlineSince = null
            failures = 0
        }

        /** Waits [millis], or less when the network changes, the app comes back or the user asks. */
        private suspend fun sleep(millis: Long) {
            withTimeoutOrNull(millis) { wakes.receive() }
        }

        private suspend fun awaitUser() {
            while (wakes.receive() != Wake.User) Unit
        }

        private fun drainWakes() {
            while (wakes.tryReceive().isSuccess) Unit
        }
    }

    private data class Target(val pc: PcCredentials?, val stopped: Boolean)

    private sealed interface Start {
        data object Ready : Start
        data class Ended(val end: SocketEnd) : Start
        data class TimedOut(val opened: Boolean) : Start
    }

    private sealed interface Attempt {
        data class Missed(val miss: Miss) : Attempt
        data class Ended(val end: RoundEnd) : Attempt
    }

    private sealed interface SocketEnd {
        data class Closed(val code: Int, val reason: String) : SocketEnd
        data class Failed(val httpStatus: Int?, val problem: ConnectionProblem) : SocketEnd
    }

    private companion object {
        const val FRAME_BUFFER = 256
        const val ABNORMAL_CLOSURE = 1006

        fun seconds(millis: Long): String = if (millis % 1000 == 0L) "${millis / 1000} s" else "$millis ms"

        /** Runs [block] for its side effect; an error other than cancellation is dropped (best-effort work). */
        suspend fun unlessCancelled(block: suspend () -> Unit) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Best effort by design; the caller's state stays as it was.
            }
        }
    }
}
