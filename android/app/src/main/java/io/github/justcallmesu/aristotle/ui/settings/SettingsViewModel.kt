package io.github.justcallmesu.aristotle.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.justcallmesu.aristotle.data.AppSettings
import io.github.justcallmesu.aristotle.data.AuthPurpose
import io.github.justcallmesu.aristotle.data.AuthResult
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.LockTimeout
import io.github.justcallmesu.aristotle.data.TextSize
import io.github.justcallmesu.aristotle.data.ThemeChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The sheet or dialog open over Settings. */
enum class SettingsSheet { Theme, TextSize, Language, Unpair, LockTimeout }

/** Why the app lock switch did not move (TS2). */
enum class LockNotice {
    /** The phone has no PIN, pattern or password: the lock has nothing to ask for, so it stays off. */
    NoScreenLock,
}

/**
 * The Settings screen's state (Q60, TS1): the app settings and the link come straight from their owners; this holds
 * which picker or confirmation is open. Theme and text size apply as soon as they are picked ([AppSettings] flows).
 * The app lock (Q61, TS2) moves only after [prove] passed, both ways: off without a check would also drop the fresh
 * checks before the terminal, "Always allow" and sudo/secret answers. [scope]: the ViewModel's own unless a test
 * passes one.
 */
class SettingsViewModel(
    private val settings: AppSettings,
    val link: StateFlow<LinkState>,
    private val prove: suspend (AuthPurpose) -> AuthResult = { AuthResult.Failed },
    scope: CoroutineScope? = null,
) : ViewModel() {
    private val work: CoroutineScope = scope ?: viewModelScope
    val keepConnected: StateFlow<Boolean> = settings.keepConnected
    val theme: StateFlow<ThemeChoice> = settings.theme
    val textSize: StateFlow<TextSize> = settings.textSize
    val appLock: StateFlow<Boolean> = settings.appLock
    val lockTimeout: StateFlow<LockTimeout> = settings.lockTimeout
    val showTerminal: StateFlow<Boolean> = settings.showTerminal

    private val open = MutableStateFlow<SettingsSheet?>(null)
    val sheet: StateFlow<SettingsSheet?> = open.asStateFlow()

    private val notice = MutableStateFlow<LockNotice?>(null)
    val lockNotice: StateFlow<LockNotice?> = notice.asStateFlow()

    private var checking: Job? = null

    fun show(sheet: SettingsSheet) {
        open.value = sheet
    }

    fun close() {
        open.value = null
    }

    fun setKeepConnected(on: Boolean) = settings.setKeepConnected(on)

    fun pickTheme(choice: ThemeChoice) {
        settings.setTheme(choice)
        close()
    }

    fun pickTextSize(choice: TextSize) {
        settings.setTextSize(choice)
        close()
    }

    /**
     * The app lock switch. [screenLockSet] false: on is refused with [LockNotice.NoScreenLock] and nothing is asked.
     * Otherwise the switch moves only when the check passes; off also goes through when the phone's screen lock is
     * gone (nothing to check, as in [io.github.justcallmesu.aristotle.data.AuthBroker]).
     */
    fun setAppLock(on: Boolean, screenLockSet: Boolean) {
        if (on == settings.appLock.value || checking?.isActive == true) return
        if (on && !screenLockSet) {
            notice.value = LockNotice.NoScreenLock
            return
        }
        notice.value = null
        checking = work.launch {
            val result = prove(if (on) AuthPurpose.TurnOn else AuthPurpose.TurnOff)
            when {
                result == AuthResult.Passed -> settings.setAppLock(on)
                result == AuthResult.NoScreenLock && !on -> settings.setAppLock(false)
                result == AuthResult.NoScreenLock -> notice.value = LockNotice.NoScreenLock
                else -> Unit
            }
        }
    }

    fun pickLockTimeout(choice: LockTimeout) {
        settings.setLockTimeout(choice)
        close()
    }

    fun setShowTerminal(on: Boolean) = settings.setShowTerminal(on)
}
