package io.github.justcallmesu.aristotle.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The one-time "What's new" card after an update (Q64, TV2). The stored last-seen versionCode below [current] shows
 * it; a first install has none stored, counts as seen and shows nothing. Opening the page or dismissing records
 * [current], so the card does not come back.
 */
class WhatsNewCard(private val settings: AppSettings, private val current: Int) {
    private val shown = MutableStateFlow(due(settings.lastSeenVersionCode, current))
    val visible: StateFlow<Boolean> = shown.asStateFlow()

    init {
        if (settings.lastSeenVersionCode == null) settings.lastSeenVersionCode = current
    }

    fun dismiss() {
        settings.lastSeenVersionCode = current
        shown.value = false
    }

    companion object {
        /** A stored version older than this build; nothing stored (fresh install) is not due. */
        fun due(lastSeen: Int?, current: Int): Boolean = lastSeen != null && lastSeen < current
    }
}
