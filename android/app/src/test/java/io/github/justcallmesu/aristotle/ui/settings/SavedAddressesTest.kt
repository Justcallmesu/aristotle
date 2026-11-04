package io.github.justcallmesu.aristotle.ui.settings

import io.github.justcallmesu.aristotle.data.AppSettings
import io.github.justcallmesu.aristotle.data.ConnectionHistory
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.LogEntry
import io.github.justcallmesu.aristotle.data.LogKind
import io.github.justcallmesu.aristotle.data.MemoryStore
import io.github.justcallmesu.aristotle.data.Network
import io.github.justcallmesu.aristotle.data.ServerEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Q73 Settings › Connection: a saved address is deleted only after the confirmation; the history can be cleared. */
class SavedAddressesTest {
    private val lan = ServerEndpoint("192.168.1.61", 8760, Network.Lan)
    private val deleted = mutableListOf<ServerEndpoint>()

    private val history = object : ConnectionHistory {
        val lines = MutableStateFlow(listOf(LogEntry(1, LogKind.CantScan)))
        override val entries: StateFlow<List<LogEntry>> = lines
        override suspend fun clear() {
            lines.value = emptyList()
        }
    }

    private val viewModel = SettingsViewModel(
        AppSettings(MemoryStore()),
        MutableStateFlow<LinkState>(LinkState.Idle),
        scope = CoroutineScope(Dispatchers.Unconfined),
        history = history,
        deleteAddress = { deleted += it; true },
    )

    @Test
    fun deleteWaitsForTheConfirmation() {
        viewModel.askDelete(lan)
        assertEquals(lan, viewModel.pendingDelete.value)
        assertTrue(deleted.isEmpty())
        viewModel.cancelDelete()
        assertNull(viewModel.pendingDelete.value)
        assertTrue(deleted.isEmpty())

        viewModel.askDelete(lan)
        viewModel.confirmDelete()
        assertEquals(listOf(lan), deleted)
        assertNull(viewModel.pendingDelete.value)
        viewModel.confirmDelete() // nothing pending: nothing more
        assertEquals(listOf(lan), deleted)
    }

    @Test
    fun leavingSettingsDropsAPendingDelete() {
        viewModel.askDelete(lan)
        viewModel.close()
        assertNull(viewModel.pendingDelete.value)
    }

    @Test
    fun clearHistoryEmptiesIt() {
        assertEquals(1, viewModel.historyEntries.value.size)
        viewModel.clearHistory()
        assertTrue(viewModel.historyEntries.value.isEmpty())
    }
}
