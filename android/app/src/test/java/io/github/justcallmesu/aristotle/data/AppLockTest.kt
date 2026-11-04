package io.github.justcallmesu.aristotle.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
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
    /** The cap timers [AppLock] asked for: (due at, action). [runTimers] fires the due ones. */
    private val timers = mutableListOf<Pair<Long, () -> Unit>>()
    private fun lock() = AppLock(enabled = { on }, timeout = { timeout }, now = { now }, schedule = { delay, action -> timers += (now + delay) to action })
    private fun runTimers() {
        val due = timers.filter { it.first <= now }
        timers.removeAll(due)
        due.forEach { it.second() }
    }

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
        val check = lock.authStarted()
        lock.away(5_000) // Android 10's PIN activity stops ours
        lock.authEnded(check, passed = true)
        assertFalse(lock.locked.value)
    }

    @Test
    fun aPassedCheckThatEndsBeforeTheReturnAlsoForgivesTheTimeAway() {
        timeout = LockTimeout.Immediately
        val lock = lock()
        lock.onStarted()
        lock.unlocked()
        val check = lock.authStarted()
        lock.onStopped(changingConfigurations = false)
        now += 5_000
        lock.authEnded(check, passed = true) // the result arrives before onStart
        lock.onStarted()
        assertFalse(lock.locked.value)
    }

    @Test
    fun aCancelledCheckOnReturnStillCountsTheTimeAwayFromTheStop() {
        timeout = LockTimeout.Immediately
        val lock = lock()
        lock.onStarted()
        lock.unlocked()
        val check = lock.authStarted()
        lock.away(5_000) // PIN screen left on the task, then back
        assertFalse(lock.locked.value) // judged when the check ends
        lock.authEnded(check, passed = false)
        assertTrue(lock.locked.value)
    }

    @Test
    fun aCheckThatEndsCancelledAfterALongAbsenceLocksOnReturn() {
        val lock = lock()
        lock.onStarted()
        lock.unlocked()
        val check = lock.authStarted()
        lock.onStopped(changingConfigurations = false)
        now += 10 * 60_000L
        lock.authEnded(check, passed = false) // e.g. the host cancelled it on destroy
        lock.onStarted()
        assertTrue(lock.locked.value)
    }

    @Test
    fun aCheckThatNeverEndsIsCappedAndTheTimeAwayCounts() {
        val lock = lock()
        lock.onStarted()
        lock.unlocked()
        lock.authStarted() // never calls back, and authEnded is never called
        lock.onStopped(changingConfigurations = false)
        now += 10 * 60_000L
        lock.onStarted()
        assertTrue(lock.locked.value)
    }

    @Test
    fun aCheckStillWithinTheCapKeepsHoldingTheReturnBack() {
        val lock = lock()
        lock.onStarted()
        lock.unlocked()
        lock.authStarted()
        lock.onStopped(changingConfigurations = false)
        now += AppLock.CHECK_CAP_MILLIS - 1
        lock.onStarted()
        assertFalse(lock.locked.value)
    }

    // TL1c (TM1 follow-up): the cap is judged when it expires, also while the app stays in front.
    @Test
    fun theCapExpiringWithTheAppInFrontJudgesTheReturnItHeldBack() {
        val lock = lock()
        lock.onStarted()
        lock.unlocked()
        lock.authStarted() // never calls back
        lock.onStopped(changingConfigurations = false)
        now += 90_000 // away longer than the 1 min timeout
        lock.onStarted() // within the cap: held back
        assertFalse(lock.locked.value)
        now = timers.single().first
        runTimers() // no lifecycle event comes: the app stays in front
        assertTrue(lock.locked.value)
    }

    @Test
    fun theCapExpiringAfterAShortAbsenceDoesNotLock() {
        val lock = lock()
        lock.onStarted()
        lock.unlocked()
        lock.authStarted()
        lock.onStopped(changingConfigurations = false)
        now += 30_000 // under the timeout
        lock.onStarted()
        now += AppLock.CHECK_CAP_MILLIS
        runTimers()
        assertFalse(lock.locked.value)
    }

    @Test
    fun anEarlierChecksTimerDoesNotCutANewerCheckShort() {
        val lock = lock()
        lock.onStarted()
        lock.unlocked()
        val check1 = lock.authStarted()
        lock.authEnded(check1, passed = true)
        now += AppLock.CHECK_CAP_MILLIS - 1_000
        lock.authStarted() // a second check; the first one's timer fires during it
        lock.onStopped(changingConfigurations = false)
        now += 1_000
        runTimers()
        assertTrue("still running", lock.checkRunning())
        now += 60_000
        lock.onStarted()
        assertFalse(lock.locked.value) // the newer check is within its own cap: held back
    }

    @Test
    fun theLateEndOfAnAbandonedCheckDoesNotEndANewOne() {
        val lock = lock()
        lock.onStarted()
        lock.unlocked()
        val check1 = lock.authStarted()
        lock.onStopped(changingConfigurations = false)
        now += 10 * 60_000L
        lock.onStarted() // capped: locked
        lock.unlocked()
        lock.authEnded(check1, passed = false) // the abandoned one finally ends
        lock.authStarted() // a new check
        lock.onStopped(changingConfigurations = false)
        now += 60_000L
        lock.onStarted() // within the cap of the new check: held back
        assertFalse(lock.locked.value)
    }

    @Test
    fun leavingTheAppDuringACheckCountsFromTheStop() {
        val lock = lock()
        lock.onStarted()
        lock.unlocked()
        val check = lock.authStarted()
        lock.onStopped(changingConfigurations = false)
        now += 10 * 60_000L
        lock.authEnded(check, passed = false) // the user went home; the prompt was cancelled
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

    // TL1c (TM1 follow-up): a check that never calls back stops blocking Unlock at the cap.
    @Test
    fun aStuckCheckIsReleasedByTheCapSoUnlockWorksAgain() = runBlocking {
        val lock = lock()
        val (broker, _) = broker(lock)
        val stuck = CompletableDeferred<AuthResult>()
        broker.attach { stuck.await() }
        val first = CompletableDeferred<Boolean>()
        CoroutineScope(Dispatchers.Unconfined).launch { first.complete(broker.confirm(AuthPurpose.Unlock)) }
        broker.attach { AuthResult.Passed }
        now += AppLock.CHECK_CAP_MILLIS - 1
        assertFalse("within the cap: one check at a time", broker.confirm(AuthPurpose.Unlock))
        assertTrue(lock.locked.value)
        now += 1
        assertTrue("past the cap: Unlock works again", broker.confirm(AuthPurpose.Unlock))
        assertFalse(lock.locked.value)
        // The stuck one ends late: it frees nothing a newer check holds, and the broker still takes one at a time.
        stuck.complete(AuthResult.Failed)
        assertFalse(first.await())
        val gate = CompletableDeferred<AuthResult>()
        broker.attach { gate.await() }
        val third = CompletableDeferred<Boolean>()
        CoroutineScope(Dispatchers.Unconfined).launch { third.complete(broker.confirm(AuthPurpose.Terminal)) }
        assertFalse(broker.confirm(AuthPurpose.AlwaysAllow))
        gate.complete(AuthResult.Passed)
        assertTrue(third.await())
    }

    @Test
    fun theCapTimerAloneFreesAStuckCheck() = runBlocking {
        val lock = lock()
        val (broker, _) = broker(lock)
        broker.attach { awaitCancellation() }
        CoroutineScope(Dispatchers.Unconfined).launch { broker.confirm(AuthPurpose.Unlock) }
        assertTrue(lock.checkRunning())
        now += AppLock.CHECK_CAP_MILLIS
        runTimers()
        broker.attach { AuthResult.Passed }
        assertTrue(broker.confirm(AuthPurpose.Unlock))
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
