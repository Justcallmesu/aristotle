package io.github.justcallmesu.aristotle.data

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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

    /**
     * [call] that waits up to [timeoutMillis] for the answer (a staging upload of about 1 MB, T16b). The default is a
     * plain [call], so a link that does not know the difference keeps its own timeout.
     */
    suspend fun callWithin(method: String, params: JsonObject, timeoutMillis: Long): JsonElement = call(method, params)

    /**
     * Answers a Hermes -> client request (`srq-…`) on the live relay; false when there is none. Used only for the
     * prompts the user answers (sudo, secret): a response settles the request for every viewer (protocol §6).
     */
    fun respond(requestId: String, result: JsonObject): Boolean

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
 * into the stored ones (never replacing them, T09b), and [lastGood], the one that worked. [found]: [lastGood] when it
 * was not stored and came from LAN discovery (Q65), so it is saved as found on Wi-Fi (Q73).
 */
fun interface LinkMemory {
    suspend fun remember(pcId: String, fresh: List<ServerEndpoint>, lastGood: ServerEndpoint, found: ServerEndpoint?)
}

data class LinkTimings(
    val readyTimeoutMillis: Long = 20_000,
    val hermesDownRetryMillis: Long = 10_000,
    /** The slow backstop after a `4409` (D36): a stable background network sends no other wake. */
    val supersededRetryMillis: Long = 60_000,
    val rpcTimeoutMillis: Long = 15_000,
    val backoff: Backoff = Backoff(),
    /** TL1e: this many drops after `gateway.ready` within [flapWindowMillis] demote a LAN address ([LanFlaps]). */
    val flapDrops: Int = 3,
    val flapWindowMillis: Long = 5 * 60_000L,
    val flapDemoteMillis: Long = 15 * 60_000L,
)

/**
 * The process-wide link to the paired PC (T09), owned by the Application: one relay socket at a time.
 * Tries the PC's addresses in [EndpointChoice] order, reconnects after a loss with [Backoff] (reset once a
 * relay delivers `gateway.ready`, unless it is a LAN address that keeps dropping after that, [LanFlaps]), and at once when the phone's network changes, the app returns to the
 * foreground or the user asks. No retry after `4401` (wipe) or `4505`; `4503` is retried every 10 s; after `4409`
 * a network change, the foreground or the user starts the next round at once, else a slow backstop (60 s) does (D36,
 * T13b), counted down in [LinkState.Offline.nextAttemptAtMillis] (D39).
 * [stop] (the background notification's Stop, T10) closes the relay until [onForeground].
 * Frames are handed off OkHttp's reader thread at once ([RelaySocket]) and parsed here (protocol §12).
 * An unexpected error in a round shows as [OfflineCause.AppError] and the next round runs: the link never dies.
 * What happens (connected, switched, offline and why, a LAN address demoted) goes to [history], the connection
 * history (Q73), which only queues it.
 */
class LinkManager(
    private val scope: CoroutineScope,
    private val credentials: StateFlow<CredentialState>,
    private val dialer: LinkDialer,
    private val network: NetworkWatch,
    private val memory: LinkMemory,
    private val timings: LinkTimings = LinkTimings(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val history: ConnectionEvents = ConnectionEvents.None,
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

    /**
     * A LAN address discovery just verified (Q65, [useFound]): a one-shot candidate, tried first by the next round on a
     * LAN-first network or beside a Tailscale relay. Stored only once a relay connects through it ([refreshAddresses]).
     */
    @Volatile
    private var found: ServerEndpoint? = null

    /** LAN addresses that keep dropping after `gateway.ready` (TL1e); kept across stops and restarts of the session. */
    private val flaps = LanFlaps(timings.flapDrops, timings.flapWindowMillis, timings.flapDemoteMillis)

    /**
     * Addresses the user deleted while this session runs (Q73, [addressRemoved]): a round already ordered skips them,
     * and a relay on one ends at once. Cleared when a session starts (the stored list no longer holds them then).
     */
    @Volatile
    private var removed: Set<ServerEndpoint> = emptySet()

    private enum class Wake { User, Network, Foreground, Found, Removed }

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

    /**
     * LAN discovery verified [endpoint] with the pin (Q65, [PcFinder]); nothing is stored yet. On a LAN-first network the
     * next round tries it first and one waiting to retry tries at once; a link online over Tailscale opens a relay to it
     * beside its own and moves only when that one is ready. A link already online on a LAN address stays where it is.
     */
    fun useFound(endpoint: ServerEndpoint) {
        removed = removed - endpoint
        found = endpoint
        wakes.trySend(Wake.Found)
    }

    /**
     * The user deleted [endpoint] from the stored addresses (Q73); the store has it gone already. A relay on it, or
     * one connecting to it, ends and the next round starts at once on the other addresses: a switch, not a loss, so
     * no backoff and no 4409 stand-down.
     */
    fun addressRemoved(endpoint: ServerEndpoint) {
        removed = removed + endpoint
        if (found == endpoint) found = null
        wakes.trySend(Wake.Removed)
    }

    override suspend fun call(method: String, params: JsonObject): JsonElement =
        (live ?: throw RpcException(null, "$method: not connected", linkClosed = true, written = false)).call(method, params)

    override suspend fun callWithin(method: String, params: JsonObject, timeoutMillis: Long): JsonElement =
        (live ?: throw RpcException(null, "$method: not connected", linkClosed = true, written = false)).call(method, params, timeoutMillis)

    override fun respond(requestId: String, result: JsonObject): Boolean = live?.respond(requestId, result) ?: false

    /** One paired PC, from pairing (or start) until it is forgotten. */
    private inner class PcSession(private val paired: PcCredentials) {
        private var failures = 0
        private var offlineSince: Long? = null

        /** For the history (Q73): the address last online with no offline since, and the offline reason last told. */
        private var lastOnline: ServerEndpoint? = null
        private var lastOffline: OfflineReason? = null

        suspend fun run() {
            removed = emptySet()
            current.value = LinkState.Connecting
            while (true) {
                val end = nextRound()
                LinkHistory.reasonOf(end)?.let(::wentOffline)
                when (end) {
                    RoundEnd.Revoked -> {
                        current.value = LinkState.Revoked
                        return
                    }
                    is RoundEnd.HermesUpdated -> {
                        reached()
                        current.value = LinkState.HermesUpdated(end.details, end.address)
                        awaitUser()
                    }
                    is RoundEnd.HermesDown -> {
                        reached()
                        current.value = LinkState.HermesDown(end.details, end.address)
                        sleep(timings.hermesDownRetryMillis)
                    }
                    RoundEnd.Switch -> current.value = LinkState.Connecting
                    is RoundEnd.Lost -> backOff(end)
                    is RoundEnd.Superseded -> standDown(end)
                }
            }
        }

        /** One history line per stretch offline, and again only when the reason changes (no line per retry). */
        private fun wentOffline(reason: OfflineReason) {
            lastOnline = null
            if (reason == lastOffline) return
            lastOffline = reason
            history.record(ConnectionEvent.Offline(reason))
        }

        private fun cameOnline(endpoint: ServerEndpoint) {
            val before = lastOnline
            history.record(if (before != null && before != endpoint) ConnectionEvent.Switched(before, endpoint) else ConnectionEvent.Connected(endpoint))
            lastOnline = endpoint
            lastOffline = null
        }

        /** One round over the PC's addresses, best first; an unexpected error ends it like a loss. */
        private suspend fun nextRound(): RoundEnd = try {
            drainWakes()
            val pc = latest()
            val preferLan = network.prefersLan()
            val hint = found.also { found = null }?.takeIf { preferLan }
            val order = EndpointChoice.withFound(EndpointChoice.order(pc, preferLan), hint)
            round(pc, flaps.order(order, network.localNetworks(), clock()))
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
                if (endpoint in removed) continue
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
            val dial = openDial(pc, endpoint, onTrial = false)
            val start: Start = withTimeoutOrNull(timings.readyTimeoutMillis) {
                var started: Start? = null
                while (started == null) {
                    started = select<Start?> {
                        dial.ready.onAwait { Start.Ready }
                        dial.ended.onAwait { Start.Ended(it) }
                        // The network changed while connecting: this address may now be the wrong one.
                        wakes.onReceive { wake -> if (wake == Wake.Network || connectsToFound(wake, endpoint) || isRemoved(wake, endpoint)) Start.Switched else null }
                    }
                }
                started
            } ?: Start.TimedOut(dial.opened.get())
            val result = when (start) {
                Start.Ready -> {
                    // D29: a network change raised while this connect was in flight and not taken by the select
                    // above (it lost the race to `gateway.ready`) is about the network this relay just came up on.
                    drainWakes()
                    Attempt.Ended(online(this, pc, dial))
                }
                Start.Switched -> Attempt.Ended(RoundEnd.Switch)
                is Start.Ended -> notReady(endpoint, start.end)
                is Start.TimedOut -> timedOut(endpoint, start.opened)
            }
            // Before the next socket opens, whatever the reason this one ends (D33): an open socket is closed
            // with 1000, so the PC drops it at once when that still reaches it; one still connecting is dropped.
            // Never left for the server to reap after its ping timeout, nor counted against the device's sockets.
            dial.pump.cancel()
            result
        }

        /**
         * Opens a relay to [endpoint] and starts reading it in [this] scope. [onTrial]: a found address tried while
         * another relay is online ([online]); its events reach the screens only once it takes over ([Dial.live]).
         */
        private fun CoroutineScope.openDial(pc: PcCredentials, endpoint: ServerEndpoint, onTrial: Boolean): Dial {
            val socket = dialer.openRelay(pc, endpoint)
            val rpc = RelayRpc(socket::send, timings.rpcTimeoutMillis)
            val ready = CompletableDeferred<Unit>()
            val ended = CompletableDeferred<SocketEnd>()
            val live = CompletableDeferred<Unit>().apply { if (!onTrial) complete(Unit) }
            val opened = AtomicBoolean(false)
            val pump = launch {
                socket.events().collect { event ->
                    when (event) {
                        RelayEvent.Opened -> opened.set(true)
                        is RelayEvent.Text -> route(RpcFrames.parse(event.text), rpc, ready, live)
                        is RelayEvent.Closed -> ended.complete(SocketEnd.Closed(event.code, event.reason))
                        is RelayEvent.Failed -> ended.complete(SocketEnd.Failed(event.httpStatus, event.problem))
                    }
                }
                ended.complete(SocketEnd.Closed(ABNORMAL_CLOSURE, ""))
            }
            return Dial(endpoint, rpc, ready, ended, live, opened, pump)
        }

        private suspend fun route(frame: InboundFrame, rpc: RelayRpc, ready: CompletableDeferred<Unit>, live: CompletableDeferred<Unit>) {
            when (frame) {
                is InboundFrame.Response -> rpc.onResponse(frame)
                is InboundFrame.Event -> {
                    if (frame.type == RpcFrames.GATEWAY_READY) ready.complete(Unit)
                    live.await()
                    frames.emit(frame)
                }
                is InboundFrame.ServerRequest -> {
                    live.await()
                    frames.emit(frame)
                }
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

        /**
         * Online on [first] until a relay ends. A found LAN address (Q65) is tried beside a Tailscale relay ([trial]) and
         * takes over only once it delivers `gateway.ready`; a failed one leaves the working relay alone. Every relay
         * taken over is read in [within], the attempt's scope, and closed when it is left.
         */
        private suspend fun online(within: CoroutineScope, pc: PcCredentials, first: Dial): RoundEnd {
            var dial = first
            try {
                while (true) {
                    when (val next = onlineOn(within, pc, dial)) {
                        is Watch.Over -> {
                            if (next.end is RoundEnd.Lost && flaps.dropped(dial.endpoint, network.localNetworks(), clock())) {
                                history.record(ConnectionEvent.Demoted(dial.endpoint))
                            }
                            return next.end
                        }
                        is Watch.TakeOver -> {
                            dial.pump.cancel() // closed with 1000; the server superseded it already (4409)
                            dial = next.dial
                        }
                    }
                }
            } finally {
                dial.pump.cancel()
            }
        }

        private suspend fun onlineOn(within: CoroutineScope, pc: PcCredentials, dial: Dial): Watch {
            reachedOn(dial.endpoint)
            cameOnline(dial.endpoint)
            live = dial.rpc
            current.value = LinkState.Online(dial.endpoint, ++generation)
            dial.live.complete(Unit)
            // Not joined: the link moves on the moment the relay ends, whatever the refresh is doing.
            val refresh = scope.launch { refreshAddresses(pc, dial.endpoint) }
            var trial: Trial? = null
            var trialFailedAt: Long? = null
            var out: Watch? = null
            try {
                while (out == null) {
                    out = select<Watch?> {
                        dial.ended.onAwait { socketEnd -> endWhileTrying(endOf(socketEnd, dial.endpoint), trial, trialFailedAt) }
                        wakes.onReceive { wake ->
                            when {
                                wake == Wake.Network || isRemoved(wake, dial.endpoint) -> Watch.Over(RoundEnd.Switch)
                                wake == Wake.Removed && trial?.let { it.dial.endpoint in removed } == true -> {
                                    trial?.cancel()
                                    trial = null
                                    null
                                }
                                trial == null && movesToFound(wake, dial.endpoint) -> {
                                    trial = found.also { found = null }
                                        ?.takeUnless { flaps.demoted(it, network.localNetworks(), clock()) }
                                        ?.let { within.trial(pc, it) }
                                    null
                                }
                                else -> null
                            }
                        }
                        trial?.let { tried ->
                            tried.outcome.onAwait { ok ->
                                if (ok) {
                                    Watch.TakeOver(tried.dial)
                                } else {
                                    tried.cancel()
                                    trial = null
                                    trialFailedAt = clock()
                                    null
                                }
                            }
                        }
                    }
                }
                return out
            } finally {
                refresh.cancel()
                live = null
                dial.rpc.failAll("relay closed")
                if (out !is Watch.TakeOver) trial?.cancel()
            }
        }

        /**
         * The online relay ended while [trial] was being tried, or shortly after one failed ([trialFailedAt]): the
         * found address may take over. Opening it is what made the server close the Tailscale relay (4409, protocol
         * §12), so when it fails, before or after that close arrives, the next round starts at once instead of standing
         * down. A network change while the trial is still pending ends the wait at once (a new round), not after the
         * trial's ready timeout.
         */
        private suspend fun endWhileTrying(end: RoundEnd, trial: Trial?, trialFailedAt: Long?): Watch {
            val superseded = if (end is RoundEnd.Superseded) RoundEnd.Switch else end
            if (trial == null) {
                val justTried = trialFailedAt != null && clock() - trialFailedAt <= timings.readyTimeoutMillis
                return Watch.Over(if (justTried) superseded else end)
            }
            while (true) {
                select<Watch?> {
                    trial.outcome.onAwait { ok -> if (ok) Watch.TakeOver(trial.dial) else Watch.Over(superseded) }
                    wakes.onReceive { wake -> if (wake == Wake.Network) Watch.Over(RoundEnd.Switch) else null }
                }?.let { return it }
            }
        }

        /** A relay to [candidate] beside the online one: true once it delivers `gateway.ready` within the ready timeout. */
        private fun CoroutineScope.trial(pc: PcCredentials, candidate: ServerEndpoint): Trial {
            val dial = openDial(pc, candidate, onTrial = true)
            val outcome = async {
                withTimeoutOrNull(timings.readyTimeoutMillis) {
                    select<Boolean> {
                        dial.ready.onAwait { true }
                        dial.ended.onAwait { false }
                    }
                } ?: false
            }
            return Trial(dial, outcome)
        }

        /** Online on a non-LAN address: a found LAN address (Q65) is worth a try on a LAN-first network. */
        private fun movesToFound(wake: Wake, endpoint: ServerEndpoint): Boolean =
            wake == Wake.Found && endpoint.network != Network.Lan && network.prefersLan()

        /** The user deleted [endpoint] (Q73): leave it now. */
        private fun isRemoved(wake: Wake, endpoint: ServerEndpoint): Boolean = wake == Wake.Removed && endpoint in removed

        /** Still connecting: on a LAN-first network the verified address goes before any other being tried. */
        private fun connectsToFound(wake: Wake, endpoint: ServerEndpoint): Boolean =
            wake == Wake.Found && network.prefersLan() && found.let { it != null && it != endpoint }

        private fun endOf(end: SocketEnd, endpoint: ServerEndpoint): RoundEnd = when (end) {
            is SocketEnd.Closed -> LinkEnds.ofClose(end.code, end.reason, endpoint)
            is SocketEnd.Failed -> LinkEnds.ofFailure(end.httpStatus, end.problem, endpoint)
        }

        /**
         * Remembers the address that worked and merges in the PC's current list (a DHCP change, Tailscale
         * added). Best effort: when it fails the stored list stays, and the working relay is not ended for it.
         * A found address (Q65) that is not stored yet goes in first: a relay has now connected through it.
         */
        private suspend fun refreshAddresses(pc: PcCredentials, endpoint: ServerEndpoint) = unlessCancelled {
            val served = dialer.addresses(pc, endpoint).orEmpty()
            val stored = endpoint in pc.addresses
            val fresh = if (stored) served else listOf(endpoint) + served
            // Every time, even with nothing new: the address's last success is stored too (Q73).
            memory.remember(pc.pcId, fresh, endpoint, found = endpoint.takeUnless { stored })
        }

        private suspend fun backOff(end: RoundEnd.Lost) {
            failures++
            val since = offlineSince ?: clock().also { offlineSince = it }
            val delay = timings.backoff.delayMillis(failures)
            current.value = LinkState.Offline(since, end.cause, end.details, end.address, failures, clock() + delay)
            sleep(delay)
            current.update { if (it is LinkState.Offline) it.copy(nextAttemptAtMillis = null) else it }
        }

        /**
         * `4409` (D36): the server replaced this socket with a newer one of this device on its other listener. No
         * quick retry, or the two would keep closing each other: the next round starts on a network change, the app
         * coming to the front or Retry now, else after the slow backstop ([LinkTimings.supersededRetryMillis], T13b),
         * whose time the banner counts down to (D39).
         */
        private suspend fun standDown(end: RoundEnd.Superseded) {
            reached()
            val since = clock().also { offlineSince = it }
            val next = since + timings.supersededRetryMillis
            current.value = LinkState.Offline(since, OfflineCause.Superseded, end.details, end.address, attempt = 0, nextAttemptAtMillis = next, waiting = true)
            sleep(timings.supersededRetryMillis)
            current.update { if (it is LinkState.Offline) it.copy(nextAttemptAtMillis = null, waiting = false) else it }
        }

        /**
         * A relay came up on [endpoint]: [reached], except that a LAN address that dropped after `gateway.ready` within
         * the flap window (TL1e) does not reset the backoff, so a relay that keeps dying there backs off further.
         */
        private fun reachedOn(endpoint: ServerEndpoint) {
            if (flaps.unsettled(endpoint, network.localNetworks(), clock())) offlineSince = null else reached()
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

    /** One relay socket, opening or open. [live]: complete once its events may reach the screens. */
    private class Dial(
        val endpoint: ServerEndpoint,
        val rpc: RelayRpc,
        val ready: CompletableDeferred<Unit>,
        val ended: CompletableDeferred<SocketEnd>,
        val live: CompletableDeferred<Unit>,
        val opened: AtomicBoolean,
        val pump: Job,
    )

    /** A found address tried beside the online relay; [outcome] true once it is ready. */
    private class Trial(val dial: Dial, val outcome: Deferred<Boolean>) {
        /** Closes the tried relay (1000, or dropped while connecting) and stops waiting for it. */
        fun cancel() {
            dial.pump.cancel()
            outcome.cancel()
        }
    }

    /** How a stretch online on one relay ends: the round is over, or a tried relay takes over. */
    private sealed interface Watch {
        data class Over(val end: RoundEnd) : Watch
        data class TakeOver(val dial: Dial) : Watch
    }

    private sealed interface Start {
        data object Ready : Start
        data object Switched : Start
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

/** How a round's end reads in the connection history (Q73). Pure. */
object LinkHistory {
    /** The offline reason of [end]; null for a switch (the link goes on at once). */
    fun reasonOf(end: RoundEnd): OfflineReason? = when (end) {
        RoundEnd.Revoked -> OfflineReason.Revoked
        is RoundEnd.HermesDown -> OfflineReason.HermesNotRunning
        is RoundEnd.HermesUpdated -> OfflineReason.HermesUpdated
        RoundEnd.Switch -> null
        is RoundEnd.Superseded -> OfflineReason.Superseded
        is RoundEnd.Lost -> when (end.cause) {
            OfflineCause.Unreachable -> OfflineReason.PcUnreachable
            OfflineCause.TailscaleMayBeOff -> OfflineReason.TailscaleMayBeOff
            OfflineCause.CertificateChanged -> OfflineReason.CertificateChanged
            OfflineCause.ConnectionLost -> OfflineReason.ConnectionLost
            OfflineCause.AppError -> OfflineReason.AppError
            OfflineCause.Superseded -> OfflineReason.Superseded
        }
    }
}
