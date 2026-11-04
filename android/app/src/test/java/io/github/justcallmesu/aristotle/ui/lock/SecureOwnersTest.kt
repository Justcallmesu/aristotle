package io.github.justcallmesu.aristotle.ui.lock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TS2b: FLAG_SECURE owners do not clear each other's flag. */
class SecureOwnersTest {
    private val lockScreen = Any()
    private val paused = Any()

    @Test
    fun theFlagIsOnWhileAnyOwnerHoldsIt() {
        val owners = SecureOwners()
        assertFalse(owners.secure)
        assertTrue(owners.acquire(lockScreen))
        assertFalse(owners.acquire(paused)) // already secure: no change
        assertFalse(owners.release(lockScreen)) // paused still holds it
        assertTrue(owners.secure)
        assertTrue(owners.release(paused))
        assertFalse(owners.secure)
    }

    @Test
    fun anOwnerThatTookItTwiceNeedsToReleaseTwice() {
        val owners = SecureOwners()
        owners.acquire(lockScreen)
        owners.acquire(lockScreen)
        assertFalse(owners.release(lockScreen))
        assertTrue(owners.release(lockScreen))
    }

    @Test
    fun releasingWhatWasNeverHeldChangesNothing() {
        val owners = SecureOwners()
        owners.acquire(lockScreen)
        assertFalse(owners.release(paused))
        assertTrue(owners.secure)
    }
}
