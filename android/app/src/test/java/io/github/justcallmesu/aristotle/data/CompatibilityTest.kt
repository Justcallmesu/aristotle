package io.github.justcallmesu.aristotle.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CompatibilityTest {
    private fun rule(protocol: Int, min: Int, phone: Int = 1) = compatibilityOf(ServerInfo("1.0.0", protocol, min), phone)

    @Test
    fun matchingProtocolsAreCompatible() = assertEquals(Compatibility.Compatible, rule(protocol = 1, min = 1))

    @Test
    fun aNewerServerThatStillAcceptsThisAppIsCompatible() = assertEquals(Compatibility.Compatible, rule(protocol = 3, min = 1, phone = 2))

    @Test
    fun serverProtocolBelowThePhonesIsServerTooOld() = assertEquals(Compatibility.ServerTooOld, rule(protocol = 1, min = 1, phone = 2))

    @Test
    fun phoneBelowTheServersMinimumIsAppTooOld() = assertEquals(Compatibility.AppTooOld, rule(protocol = 3, min = 2, phone = 1))

    @Test
    fun anOldServerWithoutABlockIsCompatibleWithProtocolOne() = assertEquals(Compatibility.Compatible, compatibilityOf(ServerInfo.OLD))

    @Test
    fun thisAppSpeaksProtocolOne() = assertEquals(1, PHONE_PROTOCOL)
}
