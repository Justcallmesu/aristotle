package io.github.justcallmesu.aristotle.ui.settings

import androidx.lifecycle.ViewModel
import io.github.justcallmesu.aristotle.data.AppSettings
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.TextSize
import io.github.justcallmesu.aristotle.data.ThemeChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The sheet or dialog open over Settings. */
enum class SettingsSheet { Theme, TextSize, Language, Unpair }

/**
 * The Settings screen's state (Q60, TS1): the app settings and the link come straight from their owners; this holds
 * which picker or confirmation is open. Theme and text size apply as soon as they are picked ([AppSettings] flows).
 */
class SettingsViewModel(private val settings: AppSettings, val link: StateFlow<LinkState>) : ViewModel() {
    val keepConnected: StateFlow<Boolean> = settings.keepConnected
    val theme: StateFlow<ThemeChoice> = settings.theme
    val textSize: StateFlow<TextSize> = settings.textSize

    private val open = MutableStateFlow<SettingsSheet?>(null)
    val sheet: StateFlow<SettingsSheet?> = open.asStateFlow()

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
}
