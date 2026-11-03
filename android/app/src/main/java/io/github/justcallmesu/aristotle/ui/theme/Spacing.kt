package io.github.justcallmesu.aristotle.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** docs/design.md §4: 4 dp grid, gutters and fixed row heights. */
object Spacing {
    val xs: Dp = 4.dp
    val s: Dp = 8.dp
    val m: Dp = 12.dp
    val l: Dp = 16.dp
    val xl: Dp = 20.dp
    val xxl: Dp = 24.dp
    val xxxl: Dp = 32.dp

    val listGutter: Dp = l
    val formGutter: Dp = xl
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
}
