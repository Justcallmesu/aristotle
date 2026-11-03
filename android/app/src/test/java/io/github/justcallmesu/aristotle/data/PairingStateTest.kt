package io.github.justcallmesu.aristotle.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PairingStateTest {
    @Test
    fun missingCredentialIsNotPaired() {
        assertEquals(PairingState.NotPaired, pairingStateOf(null))
    }

    @Test
    fun blankCredentialIsNotPaired() {
        assertEquals(PairingState.NotPaired, pairingStateOf("  "))
    }

    @Test
    fun nonBlankCredentialIsPaired() {
        assertEquals(PairingState.Paired, pairingStateOf("token"))
    }
}
