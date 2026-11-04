package io.github.justcallmesu.aristotle.ui.running

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.RelayLink
import io.github.justcallmesu.aristotle.data.RpcException
import io.github.justcallmesu.aristotle.data.RunningWork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The ways into the Running page (TY1): Home's ⋮ menu (with [count] as its badge) and a chat's status stack ("See all").
 * Provided once over the paired pages, so neither screen needs a new parameter. [count]: running items as of the last
 * read (the menu's own or the page's); 0 = none or not read yet.
 */
class RunningNav internal constructor(
    val open: () -> Unit,
    private val link: RelayLink,
    private val scope: CoroutineScope,
    private val counted: MutableIntState,
) {
    private var reading: Job? = null

    val count: Int get() = counted.intValue

    /** The ⋮ menu opened: one read for its badge, only online and not while one is under way. Never polls. */
    fun countNow() {
        if (link.state.value !is LinkState.Online || reading?.isActive == true) return
        reading = scope.launch {
            try {
                counted.intValue = RunningWork(link).fetch().count
            } catch (_: RpcException) {
                // The badge keeps its last value; the page says what failed.
            } catch (_: IllegalArgumentException) {
                // An unreadable roster: the same.
            }
        }
    }

    /** The page's own reads keep the badge current. */
    fun report(count: Int) {
        counted.intValue = count
    }
}

val LocalRunningNav = staticCompositionLocalOf<RunningNav?> { null }

/** Gives [content] a [RunningNav] that opens the page with [onOpen]. */
@Composable
fun ProvideRunningNav(link: RelayLink, onOpen: () -> Unit, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val open by rememberUpdatedState(onOpen)
    val counted = remember { mutableIntStateOf(0) }
    val nav = remember(link, scope) { RunningNav({ open() }, link, scope, counted) }
    CompositionLocalProvider(LocalRunningNav provides nav, content = content)
}
