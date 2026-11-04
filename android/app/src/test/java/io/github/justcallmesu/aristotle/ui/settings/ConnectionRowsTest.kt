package io.github.justcallmesu.aristotle.ui.settings

import io.github.justcallmesu.aristotle.data.AddressInfo
import io.github.justcallmesu.aristotle.data.AddressSource
import io.github.justcallmesu.aristotle.data.DeviceToken
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.Network
import io.github.justcallmesu.aristotle.data.PcCredentials
import io.github.justcallmesu.aristotle.data.ServerEndpoint
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionRowsTest {
    private val lanA = ServerEndpoint("192.168.1.61", 8760, Network.Lan)
    private val lanB = ServerEndpoint("192.168.1.23", 8760, Network.Lan)
    private val tailA = ServerEndpoint("100.74.61.96", 8760, Network.Tailscale)
    private val tailB = ServerEndpoint("100.101.102.103", 8760, Network.Tailscale)
    private val pc = PcCredentials(
        pcId = "dev_0123456789ab",
        deviceId = "dev_0123456789ab",
        // Stored order (PcAddresses.merge): kinds mixed, newest reported first.
        addresses = listOf(tailA, lanA, tailB, lanB),
        fingerprint = "7E54 D87B 19C0 A4F2 5B3E 0D96 C1A8 72FE 3E09 B6D4 8A15 F0C3 62D9 4E7A BCA7 EAA7",
        token = DeviceToken("t0k3n"),
        lastGood = tailB,
        lastGoodByKind = mapOf(Network.Lan to lanB, Network.Tailscale to tailB),
    )

    @Test
    fun lanFirstThenTailscaleEachLedByItsLastWorkingAddress() {
        val rows = ConnectionRows.addresses(pc, LinkState.Connecting)
        assertEquals(listOf(lanB, lanA, tailB, tailA), rows.map { it.endpoint })
        assertEquals(listOf(true, false, true, false), rows.map { it.lastWorked })
        assertEquals(listOf(false, false, false, false), rows.map { it.inUse })
    }

    @Test
    fun withoutALastWorkingAddressTheStoredOrderHolds() {
        val rows = ConnectionRows.addresses(pc.copy(lastGood = null, lastGoodByKind = emptyMap()), LinkState.Idle)
        assertEquals(listOf(lanA, lanB, tailA, tailB), rows.map { it.endpoint })
        assertEquals(List(4) { false }, rows.map { it.lastWorked })
    }

    @Test
    fun theAddressTheLinkUsesIsMarked() {
        val rows = ConnectionRows.addresses(pc, LinkState.Online(lanA, generation = 3))
        assertEquals(listOf(false, true, false, false), rows.map { it.inUse })
    }

    @Test
    fun thePcIsNamedByItsLastWorkingAddress() {
        assertEquals("100.101.102.103:8760", ConnectionRows.pcLabel(pc))
        assertEquals("100.74.61.96:8760", ConnectionRows.pcLabel(pc.copy(lastGood = null)))
    }

    @Test
    fun theRevokeCommandNamesThisPhonesDeviceIdNeverTheToken() {
        val command = ConnectionRows.revokeCommand(pc.deviceId)
        assertEquals("aristotle-server devices revoke dev_0123456789ab", command)
        assertEquals(false, command.contains(pc.token.value))
    }

    // Q73: each row says where the address came from and when it last worked; the only address cannot be deleted.
    @Test
    fun rowsCarrySourceLastSuccessAndWhetherTheyCanBeDeleted() {
        val info = mapOf(lanA to AddressInfo(AddressSource.Typed, 5_000L), tailB to AddressInfo(AddressSource.ServerList))
        val rows = ConnectionRows.addresses(pc.copy(addressInfo = info), LinkState.Idle).associateBy { it.endpoint }
        assertEquals(AddressSource.Typed, rows.getValue(lanA).source)
        assertEquals(5_000L, rows.getValue(lanA).lastSuccessAt)
        assertEquals(AddressSource.ServerList, rows.getValue(tailB).source)
        assertEquals(AddressSource.Earlier, rows.getValue(lanB).source)
        assertEquals(null, rows.getValue(lanB).lastSuccessAt)
        assertEquals(true, rows.values.all { it.deletable })
        val only = ConnectionRows.addresses(pc.copy(addresses = listOf(lanA)), LinkState.Idle).single()
        assertEquals(false, only.deletable)
    }

    // TH1b: the dialog says a deletion is remembered only when another address of the same kind stays.
    @Test
    fun aDeletionIsRememberedOnlyWhenAnotherAddressOfItsKindStays() {
        val rows = ConnectionRows.addresses(pc, LinkState.Idle).associateBy { it.endpoint }
        assertEquals(true, rows.getValue(lanA).remembered)
        assertEquals(true, rows.getValue(lanB).remembered)
        val one = ConnectionRows.addresses(pc.copy(addresses = listOf(lanA, tailB)), LinkState.Idle).associateBy { it.endpoint }
        assertEquals(false, one.getValue(tailB).remembered)
        assertEquals(false, one.getValue(lanA).remembered)
    }

    @Test
    fun workedJustNowUnderAMinute() {
        assertEquals(null, ConnectionRows.workedAgo(null, 100_000))
        assertEquals(ConnectionRows.Worked.JustNow, ConnectionRows.workedAgo(60_000, 100_000))
        assertEquals(ConnectionRows.Worked.Ago, ConnectionRows.workedAgo(40_000, 100_000))
    }
}
