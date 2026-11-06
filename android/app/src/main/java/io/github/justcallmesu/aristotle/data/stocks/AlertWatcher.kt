package io.github.justcallmesu.aristotle.data.stocks

import io.github.justcallmesu.aristotle.data.Backoff
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The stock alert long-poll (TSC0 §2, protocol §20.4): `GET /v1/stocks/alerts?after&wait=50` in a loop while [target]
 * names a PC to poll (Q109: [AlertPolicy]), nothing otherwise. A new target (another address, the app back on screen)
 * cancels the call in flight and starts again; null stops it (unpaired, app closed with "keep connected" off, the PC out
 * of reach). The cursor lives in [store] (first read: the newest seq, nothing notified). Errors back off as the relay
 * does (1-30 s); an answer that came back early with nothing new waits [MIN_GAP_MILLIS] so a server that does not hold
 * the call is not hammered.
 */
class StockAlertWatcher<T : Any>(
    private val scope: CoroutineScope,
    private val target: Flow<T?>,
    private val pcIdOf: (T) -> String,
    private val poll: suspend (target: T, after: Long?, waitSeconds: Int) -> StocksResult<AlertsBody>,
    private val store: StockAlertStore,
    private val notify: (List<StockAlert>) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val backoff: Backoff = Backoff(),
) {
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch { target.distinctUntilChanged().collectLatest { t -> if (t != null) run(t) } }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun run(target: T) {
        val pcId = pcIdOf(target)
        var failures = 0
        while (true) {
            val generation = store.generation
            val after = store.cursor(pcId)
            val wait = if (after == null) 0 else AlertFeeds.WAIT_SECONDS
            val began = clock()
            when (val result = poll(target, after, wait)) {
                is StocksResult.Ok -> {
                    failures = 0
                    val fresh = store.take(pcId, after, result.value, clock(), generation)
                    if (fresh.isNotEmpty()) notify(fresh)
                    val early = after != null && result.value.items.none { it.seq > after } && clock() - began < MIN_GAP_MILLIS
                    if (early) pause(MIN_GAP_MILLIS)
                }
                is StocksResult.Failed -> {
                    failures++
                    pause(backoff.delayMillis(failures))
                }
            }
        }
    }

    companion object {
        const val MIN_GAP_MILLIS = 5_000L

        /** The per-call read timeout: the server holds up to 50 s; 70 s leaves room for a slow answer (TSC0 §2). */
        const val READ_TIMEOUT_SECONDS = 70
    }
}
