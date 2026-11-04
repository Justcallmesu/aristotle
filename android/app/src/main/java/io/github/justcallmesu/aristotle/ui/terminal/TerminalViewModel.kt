package io.github.justcallmesu.aristotle.ui.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.justcallmesu.aristotle.data.NetworkWatch
import io.github.justcallmesu.aristotle.data.ServerEndpoint
import io.github.justcallmesu.aristotle.data.terminal.TerminalAccess
import io.github.justcallmesu.aristotle.data.terminal.TerminalConnection
import io.github.justcallmesu.aristotle.data.terminal.TerminalDialer
import io.github.justcallmesu.aristotle.data.terminal.TerminalState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the terminal screen shows: its tabs, the one in front, the PC's address and the font size. */
data class TerminalUiState(
    val tabs: List<TerminalTab> = emptyList(),
    val selected: Int = 0,
    /** The address the terminals use (the link's, when the first one opened). */
    val address: ServerEndpoint? = null,
    val textSizeSp: Float = TerminalViewModel.DEFAULT_TEXT_SP,
    /** No terminal could be opened: the entry is gone (link down, switched off, no grant). */
    val unavailable: Boolean = false,
) {
    val current: TerminalTab? get() = tabs.getOrNull(selected)
    val canAddTab: Boolean get() = tabs.size < TerminalViewModel.MAX_TABS
}

/**
 * The terminal screen of the paired PC (T19b): up to two tabs (the server's per-device cap), each its own `/v1/terminal`
 * socket. Opens one tab at start. **Nothing reconnects (Q55):** an ended tab shows why and offers Reopen, which opens a
 * new shell in its place. A network change ends every tab; [leave] (the screen left, or the app went to the background)
 * and [onCleared] close them, so the phone never keeps a shell open in the background.
 */
class TerminalViewModel(
    private val access: TerminalAccess,
    private val dialer: TerminalDialer,
    network: NetworkWatch,
    private val skippedMarker: (Long) -> String,
) : ViewModel() {
    private val ui = MutableStateFlow(TerminalUiState())
    val uiState: StateFlow<TerminalUiState> = ui.asStateFlow()
    private var nextId = 1

    init {
        viewModelScope.launch { network.changes.collect { ui.value.tabs.forEach { it.connection.networkChanged() } } }
        addTab()
    }

    /** A new tab, while fewer than two are open and the PC lets this phone open one. */
    fun addTab() {
        if (!ui.value.canAddTab) return
        val tab = newTab() ?: return
        ui.update { it.copy(tabs = it.tabs + tab, selected = it.tabs.size, unavailable = false) }
    }

    fun select(index: Int) = ui.update { if (index in it.tabs.indices) it.copy(selected = index) else it }

    /** Reopen: a new shell in the place of an ended tab (never the old one: there is no reattach). */
    fun reopen(tab: TerminalTab) {
        if (tab.state.value !is TerminalState.Ended) return
        val fresh = newTab() ?: return
        ui.update { state -> state.copy(tabs = state.tabs.map { if (it === tab) fresh else it }) }
    }

    /** Close: ends the shell of the tab in front and removes the tab; true when no tab is left. */
    fun closeCurrent(): Boolean {
        val tab = ui.value.current ?: return true
        tab.connection.close()
        tab.onScreenUpdated = null
        ui.update { state ->
            val left = state.tabs.filterNot { it === tab }
            state.copy(tabs = left, selected = state.selected.coerceAtMost(maxOf(0, left.size - 1)))
        }
        return ui.value.tabs.isEmpty()
    }

    fun zoom(factor: Float) = ui.update { it.copy(textSizeSp = (it.textSizeSp * factor).coerceIn(MIN_TEXT_SP, MAX_TEXT_SP)) }

    /** The screen left or the app went to the background: every shell ends (no background shells from the phone). */
    fun leave() = ui.value.tabs.forEach { it.connection.close() }

    override fun onCleared() = leave()

    private fun newTab(): TerminalTab? {
        val target = access.target.value
        if (target == null) {
            ui.update { it.copy(unavailable = true) }
            return null
        }
        val connection = TerminalConnection(dialer.openTerminal(target.pc, target.endpoint))
        val tab = TerminalTab(nextId++, connection, viewModelScope, skippedMarker)
        ui.update { it.copy(address = target.endpoint) }
        viewModelScope.launch {
            connection.state.collect { state ->
                if (state is TerminalState.Ended && state.end.hidesEntry) access.turnedOff()
            }
        }
        tab.start()
        return tab
    }

    companion object {
        const val MAX_TABS = 2

        /** design.md: never below 11 sp. */
        const val MIN_TEXT_SP = 11f
        const val MAX_TEXT_SP = 24f
        const val DEFAULT_TEXT_SP = 13f
    }
}
