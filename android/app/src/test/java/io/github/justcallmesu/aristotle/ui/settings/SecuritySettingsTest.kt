package io.github.justcallmesu.aristotle.ui.settings

import io.github.justcallmesu.aristotle.data.AppSettings
import io.github.justcallmesu.aristotle.data.AuthPurpose
import io.github.justcallmesu.aristotle.data.AuthResult
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.LockTimeout
import io.github.justcallmesu.aristotle.data.MemoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TS2 Settings › Security: the app lock switch moves only after a passed check; the phone's Terminal switch. */
class SecuritySettingsTest {
    private val store = MemoryStore()
    private val settings = AppSettings(store)
    private val asked = mutableListOf<AuthPurpose>()
    private var result = AuthResult.Passed
    private val viewModel = SettingsViewModel(
        settings,
        MutableStateFlow<LinkState>(LinkState.Idle),
        prove = { asked += it; result },
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    @Test
    fun defaultsAreLockOffOneMinuteAndTerminalShown() {
        assertFalse(settings.appLock.value)
        assertEquals(LockTimeout.OneMinute, settings.lockTimeout.value)
        assertTrue(settings.showTerminal.value)
    }

    @Test
    fun turningTheLockOnNeedsAPassedCheck() {
        result = AuthResult.Failed
        viewModel.setAppLock(on = true, screenLockSet = true)
        assertFalse(settings.appLock.value)
        result = AuthResult.Passed
        viewModel.setAppLock(on = true, screenLockSet = true)
        assertTrue(settings.appLock.value)
        assertEquals(listOf(AuthPurpose.TurnOn, AuthPurpose.TurnOn), asked)
        assertTrue(AppSettings(store).appLock.value) // stored
    }

    @Test
    fun withoutAScreenLockTheSwitchExplainsAndStaysOff() {
        viewModel.setAppLock(on = true, screenLockSet = false)
        assertFalse(settings.appLock.value)
        assertEquals(LockNotice.NoScreenLock, viewModel.lockNotice.value)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun turningTheLockOffNeedsACheckToo() {
        settings.setAppLock(true)
        result = AuthResult.Failed
        viewModel.setAppLock(on = false, screenLockSet = true)
        assertTrue(settings.appLock.value)
        result = AuthResult.Passed
        viewModel.setAppLock(on = false, screenLockSet = true)
        assertFalse(settings.appLock.value)
        assertNull(viewModel.lockNotice.value)
    }

    @Test
    fun timeoutAndTerminalSwitchAreStored() {
        viewModel.pickLockTimeout(LockTimeout.FifteenMinutes)
        viewModel.setShowTerminal(false)
        val reread = AppSettings(store)
        assertEquals(LockTimeout.FifteenMinutes, reread.lockTimeout.value)
        assertFalse(reread.showTerminal.value)
    }

    @Test
    fun aDamagedTimeoutFallsBackToTheDefault() {
        store.put("lock_timeout", "7")
        assertEquals(LockTimeout.OneMinute, AppSettings(store).lockTimeout.value)
    }
}
