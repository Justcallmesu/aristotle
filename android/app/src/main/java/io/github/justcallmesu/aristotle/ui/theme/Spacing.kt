package io.github.justcallmesu.aristotle.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** docs/design.md §4: 4 dp grid, gutters and fixed row heights. */
object Spacing {
    /** Off-grid hairline gap, only for Markdown list items (TU1 C5); everything else stays on the 4 dp grid. */
    val xxs: Dp = 2.dp
    val xs: Dp = 4.dp
    val s: Dp = 8.dp
    val m: Dp = 12.dp
    val l: Dp = 16.dp
    val xl: Dp = 20.dp
    val xxl: Dp = 24.dp
    val xxxl: Dp = 32.dp

    val listGutter: Dp = l
    val formGutter: Dp = xl

    // Chat rhythm (Q67, design.md §4): the gap above a transcript item, chosen by the item before it.
    /** Between consecutive work items: tool calls, reasoning rows, notices. */
    val chatWorkGap: Dp = xs

    /** Between a message and a work item, either way round. */
    val chatMessageGap: Dp = m

    /** Above a user bubble: a new turn starts. */
    val chatTurnGap: Dp = xl
}

object Sizes {
    val sessionRowHeight: Dp = 72.dp
    val listRowHeight: Dp = 56.dp
    val topBarHeight: Dp = 64.dp
    val minTouchTarget: Dp = 48.dp
    val runningSpinner: Dp = 14.dp
    val runningSpinnerStroke: Dp = 2.dp
    val fieldIcon: Dp = 24.dp
    val lineIcon: Dp = 14.dp
    val progressLine: Dp = 4.dp
    val divider: Dp = 1.dp
    val stepBadge: Dp = 28.dp
    val stepIcon: Dp = 18.dp
    val toolIcon: Dp = 20.dp
    val chipIcon: Dp = 18.dp
    val chipHeight: Dp = 36.dp
    val reasoningRow: Dp = 32.dp
}
