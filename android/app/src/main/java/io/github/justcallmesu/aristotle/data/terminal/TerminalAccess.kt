package io.github.justcallmesu.aristotle.data.terminal

import io.github.justcallmesu.aristotle.data.AuthPurpose
import io.github.justcallmesu.aristotle.data.CredentialState
import io.github.justcallmesu.aristotle.data.FreshAuth
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.PcCredentials
import io.github.justcallmesu.aristotle.data.ServerEndpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The terminal's two calls to a PC address; [io.github.justcallmesu.aristotle.data.HttpLinkDialer] in the app. */
interface TerminalDialer {
    /** A new, unopened terminal socket to [endpoint] with the PC's pin and token. */
    fun openTerminal(pc: PcCredentials, endpoint: ServerEndpoint): TerminalChannel

    /** `GET /v1/me`: true when the terminal is enabled and this phone granted; null when the call failed. */
    suspend fun terminalAllowed(pc: PcCredentials, endpoint: ServerEndpoint): Boolean?
}

/** Where a terminal may be opened now: the live link's address ([generation] = its socket), same pin and token. */
data class TerminalTarget(val pc: PcCredentials, val endpoint: ServerEndpoint, val generation: Long)

/**
 * Whether the Terminal entry shows (protocol §14 Switch and grant): only while the link is online and `/v1/me`, read
 * again on every connect, said `terminal.enabled && granted`. A `4403` hides it until the next connect reads `/v1/me`
 * again ([turnedOff]). The entry follows the link's address, so a terminal opens where the relay is.
 */
class TerminalAccess(
    private val scope: CoroutineScope,
    private val link: StateFlow<LinkState>,
    private val credentials: StateFlow<CredentialState>,
    private val dialer: TerminalDialer,
) {
    private val current = MutableStateFlow<TerminalTarget?>(null)
    val target: StateFlow<TerminalTarget?> = current.asStateFlow()

    @Volatile
    private var hiddenGeneration: Long? = null

    fun start(): Job = scope.launch {
        link.map { it as? LinkState.Online }.distinctUntilChanged().collectLatest { online ->
            current.value = null
            val pc = (credentials.value as? CredentialState.Paired)?.credentials
            if (online == null || pc == null) return@collectLatest
            val allowed = try {
                dialer.terminalAllowed(pc, online.endpoint)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (allowed == true && hiddenGeneration != online.generation) {
                current.value = TerminalTarget(pc, online.endpoint, online.generation)
            }
        }
    }

    /** The PC said `4403` (switched off, or this phone's grant removed): hide the entry until the next connect. */
    fun turnedOff() {
        hiddenGeneration = current.value?.generation ?: (link.value as? LinkState.Online)?.generation
        current.value = null
    }

    companion object {
        /** The visibility rule on its own: both flags, never one. */
        fun visible(enabled: Boolean, granted: Boolean): Boolean = enabled && granted
    }
}

/** The Terminal entry in the session list's ⋮ menu (Q61, Q62, TS2). */
object TerminalEntry {
    /**
     * Shown only when the PC allows it ([TerminalAccess.target]) and "Show Terminal on this phone" is on: the phone's
     * switch hides it whatever the PC says, and never shows it when the PC does not.
     */
    fun shown(serverAllows: Boolean, showOnPhone: Boolean): Boolean = serverAllows && showOnPhone

    /**
     * Opens the terminal ([go]) after a fresh check ([auth]; at once with the app lock off). Nothing opens when the
     * check fails or is cancelled, or when the entry went away meanwhile ([shown] is read before and after).
     * Returns whether it opened.
     */
    suspend fun open(auth: FreshAuth, shown: () -> Boolean, go: () -> Unit): Boolean {
        if (!shown()) return false
        if (!auth.confirm(AuthPurpose.Terminal) || !shown()) return false
        go()
        return true
    }
}
