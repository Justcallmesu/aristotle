package io.github.justcallmesu.aristotle.data

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The HyperOS steps the app cannot read; the user ticks them (design.md §5 battery guide). */
enum class GuideStep(val key: String) { BatterySaver("guide_battery_saver"), Autostart("guide_autostart") }

/** Light, dark or the phone's mode (Q60, TS1). Palette A either way; never dynamic colour (Q38). */
enum class ThemeChoice(val key: String) {
    System("system"), Light("light"), Dark("dark");

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        System -> systemDark
        Light -> false
        Dark -> true
    }
}

/** The app's text size (Q60). Only the choice lives here; AristotleTheme turns it into type sizes (11 sp floor kept). */
enum class TextSize(val key: String) { Small("small"), Default("default"), Large("large") }

/**
 * The app's language (Q60): the phone's, or English / Indonesian whatever the phone uses. [tag] is the BCP 47 tag
 * handed to the platform; Android itself calls Indonesian `in` (its legacy code, `values-in/`), the same language.
 */
enum class AppLanguage(val key: String, val tag: String?) {
    System("system", null), English("en", "en"), Indonesian("id", "id");

    companion object {
        /** The choice a locale's language code stands for; anything this app has no strings for is the phone's. */
        fun ofLanguage(language: String?): AppLanguage = when (language?.lowercase()) {
            "en" -> English
            "id", "in" -> Indonesian
            else -> System
        }
    }
}

/** The app's own settings (T10). Small and read at start; writes go through [store] (applied in the background). */
class AppSettings(private val store: KeyValueStore) {
    private val keep = MutableStateFlow(flag(KEEP_CONNECTED) ?: true)
    val keepConnected: StateFlow<Boolean> = keep.asStateFlow()

    private val marks = MutableStateFlow(GuideStep.entries.filterTo(mutableSetOf()) { flag(it.key) == true }.toSet())
    val guideMarks: StateFlow<Set<GuideStep>> = marks.asStateFlow()

    private val themeChoice = MutableStateFlow(choice(THEME, ThemeChoice.entries, ThemeChoice::key) ?: ThemeChoice.System)
    val theme: StateFlow<ThemeChoice> = themeChoice.asStateFlow()

    private val size = MutableStateFlow(choice(TEXT_SIZE, TextSize.entries, TextSize::key) ?: TextSize.Default)
    val textSize: StateFlow<TextSize> = size.asStateFlow()

    private val lang = MutableStateFlow(choice(LANGUAGE, AppLanguage.entries, AppLanguage::key) ?: AppLanguage.System)

    /** The language picked in the app. On Android 13+ the platform keeps the live value ([AppLanguage] via LocaleManager). */
    val language: StateFlow<AppLanguage> = lang.asStateFlow()

    val batteryGuideShown: Boolean get() = flag(GUIDE_SHOWN) == true
    val notificationsAsked: Boolean get() = flag(NOTIFICATIONS_ASKED) == true

    /** The id of the last loading-screen quote shown, so the next one differs. */
    var lastQuoteId: Int?
        get() = store.get(LAST_QUOTE)?.toIntOrNull()
        set(value) = if (value == null) store.remove(LAST_QUOTE) else store.put(LAST_QUOTE, value.toString())

    fun setKeepConnected(on: Boolean) {
        store.put(KEEP_CONNECTED, on.toString())
        keep.value = on
    }

    fun setTheme(choice: ThemeChoice) {
        store.put(THEME, choice.key)
        themeChoice.value = choice
    }

    fun setTextSize(choice: TextSize) {
        store.put(TEXT_SIZE, choice.key)
        size.value = choice
    }

    fun setLanguage(choice: AppLanguage) {
        store.put(LANGUAGE, choice.key)
        lang.value = choice
    }

    fun markGuide(step: GuideStep, done: Boolean) {
        store.put(step.key, done.toString())
        marks.value = if (done) marks.value + step else marks.value - step
    }

    fun markBatteryGuideShown() = store.put(GUIDE_SHOWN, true.toString())

    fun markNotificationsAsked() = store.put(NOTIFICATIONS_ASKED, true.toString())

    private fun flag(key: String): Boolean? = store.get(key)?.toBooleanStrictOrNull()

    /** The stored choice, or null when none is stored or it is not one of [entries] (a damaged value). */
    private fun <T> choice(key: String, entries: List<T>, keyOf: (T) -> String): T? = store.get(key)?.let { stored -> entries.firstOrNull { keyOf(it) == stored } }

    private companion object {
        const val KEEP_CONNECTED = "keep_connected"
        const val GUIDE_SHOWN = "battery_guide_shown"
        const val NOTIFICATIONS_ASKED = "notifications_asked"
        const val LAST_QUOTE = "last_quote_id"
        const val THEME = "theme"
        const val TEXT_SIZE = "text_size"
        const val LANGUAGE = "language"
    }
}

/** Writes in the background ([SharedPreferences.Editor.apply]): settings are flipped from the main thread. */
class ApplyingPreferencesStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String) = prefs.edit { putString(key, value) }
    override fun remove(key: String) = prefs.edit { remove(key) }
    override fun keys(): Set<String> = prefs.all.keys.toSet()
}
