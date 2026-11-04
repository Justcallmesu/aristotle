package io.github.justcallmesu.aristotle.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TS2 (Q61): when the app lock covers the app, and the fresh check broker. */
class AppLockTest {
    private var on = true
    private var timeout = LockTimeout.Default
    private var now = 1_000_000L
    private fun lock() = AppLock(enabled = { on }, timeout = { timeout }, now = { now })

    /** On screen, then [awayMillis] in the background, then back. */
    private fun AppLock.away(awayMillis: Long) {
        onStopped(changingConfigurations = false)
        now += awayMillis
        onStarted()
    }

    @Test
    fun timeoutChoicesAndTheirStoredKeys() {
        assertEquals(listOf(0L, 60_000L, 300_000L, 900_000L), LockTimeout.entries.map { it.millis })
        assertEquals(listOf("0", "1", "5", "15"), LockTimeout.entries.map { it.key })
        assertEquals(LockTimeout.OneMinute, LockTimeout.Default)
    }

    @Test
    fun aNewProcessStartsLockedWhileTheLockIsOn() {
        assertTrue(lock().locked.value)
        on = false
        assertFalse(lock().locked.value)
    }

    @Test
    fun backBeforeTheTimeoutStaysUnlockedAndAfterItLocks() {
        val lock = lock()
        lock.onStarted()
        lock.unlocked()
        lock.away(59_999)
        assertFalse(lock.locked.value)
        lock.away(60_000)
        assertTrue(lock.locked.value)
    }

    @Test
    fun eachChoiceLocksAtItsOwnTime() {
        for (choice in LockTimeout.entries) {
            timeout = choice
            val lock = lock()
            lock.onStarted()
            lock.unlocked()
            if (choice.millis > 0) {
                lock.away(choice.millis - 1)
                assertFalse(choice.name, lock.locked.value)
            }
            lock.away(choice.millis)
            assertTrue(choice.name, lock.locked.value)
        }
    }

    @Test
    fun immediatelyLocksOnAnyReturnButNotOnARotation() {
        timeout = LockTimeout.Immediately
        val lock = lock()
        lock.onStarted()
        lock.unlocked()
        lock.onStopped(changingConfigurations = true)
        lock.onStarted()
        assertFalse(lock.locked.value)
        lock.away(0)
        assertTrue(lock.locked.value)
    }

    @Test
    fun timeOnScreenNeverLocks() {
        val lock = lock()
        lock.onStarted()
        lock.unlocked()
        now += 24 * 60 * 60_000L
        assertFalse(lock.locked.value)
    }

    @Test
    fun anotherActivityOfTheAppIsNotTimeAway() {
        timeout = LockTimeout.Immediately
        val lock = lock()
        lock.onStarted()
        lock.unlocked()
        lock.onStarted() // e.g. the code scanner's delegate activity
        lock.onStopped(changingConfigurations = false)
        lock.onStarted()
        assertFalse(lock.locked.value)
    }

    @Test
    fun withTheLockOffNothingLocks() {
        on = false
        timeout = LockTimeout.Immediately
        val lock = lock()
        lock.onStarted()
        lock.away(3_600_000)
        assertFalse(lock.locked.value)
    }

    @Test
    fun theSystemPinScreenDuringACheckIsNotTimeAway() {
        timeout = LockTimeout.Immediately
        val lock = lock()
        lock.onStarted()
        lock.unlocked()
        lock.authStarted()
        lock.away(5_000) // Android 10's PIN activity stops ours
        lock.authEnded(passed = true)
        assertFalse(lock.locked.value)
    }

    @Test
    fun aPassedCheckThatEndsBeforeTheReturnAlsoForgivesTheTimeAway() {
        timeout = LockTimeout.Immediately
        val lock = lock()
        lock.onStarted()
        lock.unlocked()
        lock.authStarted()
        lock.onStopped(changingConfigurations = false)
        now += 5_000
        lock.authEnded(passed = true) // the result arrives before onStart
        lock.onStarted()
        assertFalse(lock.locked.value)
    }

    @Test
    fun aCancelledCheckOnReturnStillCountsTheTimeAwayFromTheStop() {
        timeout = LockTimeout.Immediately
        val lock = lock()
        lock.onStarted()
        lock.unlocked()
        lock.authStarted()
        lock.away(5_000) // PIN screen left on the task, then back
        assertFalse(lock.locked.value) // judged when the check ends
        lock.authEnded(passed = false)
        assertTrue(lock.locked.value)
    }

    @Test
    fun aCheckThatNeverEndsDoesNotKeepTheLockOffAfterTheUserReturnsLater() {
        val lock = lock()
        lock.onStarted()
        lock.unlocked()
        lock.authStarted() // never calls back
        lock.onStopped(changingConfigurations = false)
        now += 10 * 60_000L
        lock.authEnded(passed = false) // e.g. the host cancelled it on destroy
        lock.onStarted()
        assertTrue(lock.locked.value)
    }

    @Test
    fun leavingTheAppDuringACheckCountsFromTheStop() {
        val lock = lock()
        lock.onStarted()
        lock.unlocked()
        lock.authStarted()
        lock.onStopped(changingConfigurations = false)
        now += 10 * 60_000L
        lock.authEnded(passed = false) // the user went home; the prompt was cancelled
        now += 60_000
        lock.onStarted()
        assertTrue(lock.locked.value)
    }

    // ── broker ──────────────────────────────────────────────────────────────────────────────────

    private fun broker(lock: AppLock): Pair<AuthBroker, () -> Boolean> {
        var turnedOff = false
        return AuthBroker(lock, lockOn = { on }, turnOff = { turnedOff = true; on = false }) to { turnedOff }
    }

    @Test
    fun withTheLockOffAFreshCheckPassesWithoutAsking() = runBlocking {
        on = false
        val (broker, _) = broker(lock())
        var asked = 0
        broker.attach { asked++; AuthResult.Failed }
        assertTrue(broker.confirm(AuthPurpose.Terminal))
        assertEquals(0, asked)
    }

    @Test
    fun aPassedCheckUnlocksAndAFailedOneDoesNot() = runBlocking {
        val lock = lock()
        val (broker, _) = broker(lock)
        broker.attach { AuthResult.Failed }
        assertFalse(broker.confirm(AuthPurpose.Unlock))
        assertTrue(lock.locked.value)
        broker.attach { AuthResult.Passed }
        assertTrue(broker.confirm(AuthPurpose.Unlock))
        assertFalse(lock.locked.value)
    }

    @Test
    fun noActivityToAskMeansNo() = runBlocking {
        val lock = lock()
        val (broker, _) = broker(lock)
        val host = AuthHost { AuthResult.Passed }
        broker.attach(host)
        broker.detach(host)
        assertFalse(broker.confirm(AuthPurpose.Terminal))
        assertTrue(lock.locked.value)
    }

    @Test
    fun aClosedSecondWindowDoesNotBreakTheFirstOnesUnlock() = runBlocking {
        val lock = lock()
        val (broker, _) = broker(lock)
        val first = AuthHost { AuthResult.Passed }
        val second = AuthHost { AuthResult.Failed }
        broker.attach(first)
        broker.attach(second)
        assertFalse(broker.confirm(AuthPurpose.Unlock)) // the newest window asks
        broker.detach(second)
        assertTrue(broker.confirm(AuthPurpose.Unlock))
        assertFalse(lock.locked.value)
        broker.detach(second) // detaching twice or a stranger changes nothing
        assertTrue(broker.confirm(AuthPurpose.Unlock))
    }

    @Test
    fun oneCheckAtATime() = runBlocking {
        val (broker, _) = broker(lock())
        val gate = CompletableDeferred<AuthResult>()
        broker.attach { gate.await() }
        val first = CompletableDeferred<Boolean>()
        CoroutineScope(Dispatchers.Unconfined).launch { first.complete(broker.confirm(AuthPurpose.SecretValue)) }
        assertFalse(broker.confirm(AuthPurpose.AlwaysAllow))
        gate.complete(AuthResult.Passed)
        assertTrue(first.await())
    }

    @Test
    fun aRemovedScreenLockTurnsTheAppLockOffInsteadOfLockingTheUserOut() = runBlocking {
        val lock = lock()
        val (broker, turnedOff) = broker(lock)
        broker.attach { AuthResult.NoScreenLock }
        assertTrue(broker.confirm(AuthPurpose.Unlock))
        assertTrue(turnedOff())
        assertFalse(lock.locked.value)
    }

    @Test
    fun proveAsksEvenWithTheLockOff() = runBlocking {
        on = false
        val (broker, _) = broker(lock())
        broker.attach { AuthResult.Failed }
        assertEquals(AuthResult.Failed, broker.prove(AuthPurpose.TurnOn))
    }
}
