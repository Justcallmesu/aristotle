package io.github.justcallmesu.aristotle

/**
 * How the "App updates" notification opens Settings › About (UP2, Q132): like [OpenStock], only through the non-exported
 * alias, with its own action; a recents relaunch opens nothing. Pure.
 */
object OpenUpdate {
    const val ACTION = "io.github.justcallmesu.aristotle.OPEN_UPDATE"

    fun read(action: String?, component: String?, flags: Int = 0): Boolean =
        action == ACTION && component == OpenSession.ALIAS && flags and OpenSession.FLAG_LAUNCHED_FROM_HISTORY == 0
}
