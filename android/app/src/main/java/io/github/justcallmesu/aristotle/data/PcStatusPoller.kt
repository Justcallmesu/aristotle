package io.github.justcallmesu.aristotle.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Reads the PC's status every [intervalMillis] (Q78, protocol §16) only while Home is [visible] **and** the link is
 * online; it stops the moment either ends and starts with a read when both are back. The last reading is kept
 * ([view]); a failed read changes nothing. [pause] and [clock] are seams: tests pass a fake clock.
 */
class PcStatusPoller(
    private val scope: CoroutineScope,
    private val link: StateFlow<LinkState>,
    private val visible: StateFlow<Boolean>,
    private val read: suspend (ServerEndpoint) -> PcStatus?,
    private val intervalMillis: Long = 10_000,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val current = MutableStateFlow(PcStatusView.None)
    val view: StateFlow<PcStatusView> = current.asStateFlow()

    fun start(): Job = scope.launch {
        combine(link, visible) { state, shown -> (state as? LinkState.Online)?.takeIf { shown } }
            .distinctUntilChanged()
            .collectLatest { online ->
                if (online == null) return@collectLatest
                while (true) {
                    val status = try {
                        read(online.endpoint)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    if (status != null) current.value = PcStatusView(status, clock())
                    pause(intervalMillis)
                }
            }
    }
}
