package io.github.justcallmesu.aristotle.ui.settings

import io.github.justcallmesu.aristotle.data.AppSettings
import io.github.justcallmesu.aristotle.data.FindPhase
import io.github.justcallmesu.aristotle.data.FindResult
import io.github.justcallmesu.aristotle.data.FindState
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.MemoryStore
import io.github.justcallmesu.aristotle.data.Network
import io.github.justcallmesu.aristotle.data.PcSearch
import io.github.justcallmesu.aristotle.data.ServerEndpoint
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

/** TL1 Settings › Connection "Find PC on this Wi-Fi": progress, then the result; one search at a time. */
class FindPcTest {
    private val found = ServerEndpoint("192.168.1.70", 8760, Network.Lan)
    private var searches = 0

    private fun viewModel(search: PcSearch) = SettingsViewModel(
        AppSettings(MemoryStore()),
        MutableStateFlow<LinkState>(LinkState.Idle),
        search = search,
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    @Test
    fun showsEachPhaseThenWhereThePcIs() {
        val announce = CompletableDeferred<Unit>()
        val scan = CompletableDeferred<FindResult>()
        val vm = viewModel { onPhase ->
            searches++
            onPhase(FindPhase.Announcement)
            announce.await()
            onPhase(FindPhase.Scan)
            scan.await()
        }
        assertEquals(FindState.Idle, vm.find.value)
        vm.findPc()
        assertEquals(FindState.Looking(FindPhase.Announcement), vm.find.value)
        vm.findPc() // a second tap while looking does nothing
        announce.complete(Unit)
        assertEquals(FindState.Looking(FindPhase.Scan), vm.find.value)
        scan.complete(FindState.Found(found))
        assertEquals(FindState.Found(found), vm.find.value)
        assertEquals(1, searches)
    }

    @Test
    fun nothingOrAFailureIsNotFound() {
        val none = viewModel { FindState.NotFound }
        none.findPc()
        assertEquals(FindState.NotFound, none.find.value)
        val broken = viewModel { throw IOException("no Wi-Fi") }
        broken.findPc()
        assertEquals(FindState.NotFound, broken.find.value)
        none.findPc() // tapping again searches again
        assertEquals(FindState.NotFound, none.find.value)
    }

    @Test
    fun noWifiToBindToSaysSo() {
        val vm = viewModel { FindState.CantScan }
        vm.findPc()
        assertEquals(FindState.CantScan, vm.find.value)
    }
}
