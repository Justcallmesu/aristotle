package io.github.justcallmesu.aristotle.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingCodeTest {
    @Test
    fun normalizesLikeTheServer() {
        assertEquals("K7Q2M9XD", PairingCode.normalize("k7q2-m9xd"))
        assertEquals("K7Q2M9XD", PairingCode.normalize("K7Q2 M9XD"))
        assertEquals("0111ABCD", PairingCode.normalize("O1Ilabcd"))
        assertEquals("01110", PairingCode.normalize("oIlLO"))
    }

    @Test
    fun sanitizeKeepsOnlyAlphabetAndCapsAtEight() {
        assertEquals("K7Q2M9XD", PairingCode.sanitize("k7q2-m9xd-extra"))
        assertEquals("AB", PairingCode.sanitize("a!b?u"))
    }

    @Test
    fun completeMeansEightAlphabetCharacters() {
        assertTrue(PairingCode.isComplete("k7q2 m9xd"))
        assertTrue(PairingCode.isComplete("K7Q2-M9XO"))
        assertFalse(PairingCode.isComplete("K7Q2M9X"))
        assertFalse(PairingCode.isComplete("K7Q2M9XU"))
        assertFalse(PairingCode.isComplete("K7Q2M9XDD"))
    }

    @Test
    fun groupedIsTwoGroupsOfFour() {
        assertEquals("K7Q2 M9XD", PairingCode.grouped("k7q2m9xd"))
        assertEquals("K7Q2 M", PairingCode.grouped("K7Q2M"))
        assertEquals("K7Q2", PairingCode.grouped("k7q2"))
    }
}
