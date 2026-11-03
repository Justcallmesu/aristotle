package io.github.justcallmesu.aristotle.data

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The HyperOS steps the app cannot read; the user ticks them (design.md §5 battery guide). */
enum class GuideStep(val key: String) { BatterySaver("guide_battery_saver"), Autostart("guide_autostart") }

/** The app's own settings (T10). Small and read at start; writes go through [store] (applied in the background). */
class AppSettings(private val store: KeyValueStore) {
    private val keep = MutableStateFlow(flag(KEEP_CONNECTED) ?: true)
    val keepConnected: StateFlow<Boolean> = keep.asStateFlow()

    private val marks = MutableStateFlow(GuideStep.entries.filterTo(mutableSetOf()) { flag(it.key) == true }.toSet())
    val guideMarks: StateFlow<Set<GuideStep>> = marks.asStateFlow()

    val batteryGuideShown: Boolean get() = flag(GUIDE_SHOWN) == true
    val notificationsAsked: Boolean get() = flag(NOTIFICATIONS_ASKED) == true

    fun setKeepConnected(on: Boolean) {
        store.put(KEEP_CONNECTED, on.toString())
        keep.value = on
    }

    fun markGuide(step: GuideStep, done: Boolean) {
        store.put(step.key, done.toString())
        marks.value = if (done) marks.value + step else marks.value - step
    }

    fun markBatteryGuideShown() = store.put(GUIDE_SHOWN, true.toString())

    fun markNotificationsAsked() = store.put(NOTIFICATIONS_ASKED, true.toString())

    private fun flag(key: String): Boolean? = store.get(key)?.toBooleanStrictOrNull()

    private companion object {
        const val KEEP_CONNECTED = "keep_connected"
        const val GUIDE_SHOWN = "battery_guide_shown"
        const val NOTIFICATIONS_ASKED = "notifications_asked"
    }
}

/** Writes in the background ([SharedPreferences.Editor.apply]): settings are flipped from the main thread. */
class ApplyingPreferencesStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String) = prefs.edit { putString(key, value) }
    override fun remove(key: String) = prefs.edit { remove(key) }
    override fun keys(): Set<String> = prefs.all.keys.toSet()
}
