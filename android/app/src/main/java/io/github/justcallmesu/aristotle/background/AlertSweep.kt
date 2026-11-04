package io.github.justcallmesu.aristotle.background

/** Which notifications in the tray are session alerts (TS1b unpair sweep). Pure. */
object AlertSweep {
    /** The tags of [active] (tag, id) posted under [alertId]: the session alerts, never the link's ongoing one. */
    fun tags(active: List<Pair<String?, Int>>, alertId: Int): List<String> =
        active.mapNotNull { (tag, id) -> tag?.takeIf { id == alertId } }.distinct()
}
