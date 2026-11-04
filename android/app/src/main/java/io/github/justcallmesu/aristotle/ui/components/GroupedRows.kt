package io.github.justcallmesu.aristotle.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Gap between the rows of one grouped section (design.md §3). */
val GroupedRowGap: Dp = 2.dp

enum class GroupedRowPosition { Single, First, Middle, Last }

/** Pure: where row [index] of [count] sits in its section. */
fun groupedRowPosition(index: Int, count: Int): GroupedRowPosition = when {
    count <= 1 -> GroupedRowPosition.Single
    index <= 0 -> GroupedRowPosition.First
    index >= count - 1 -> GroupedRowPosition.Last
    else -> GroupedRowPosition.Middle
}

/** Outer corners and inner corners of a grouped row, in dp. Outer = `large` (20), inner = `extraSmall` (4). */
internal const val GROUPED_OUTER_DP = 20
internal const val GROUPED_INNER_DP = 4

/** Corner radii (topStart/topEnd, bottomStart/bottomEnd) in dp for a position: top pair and bottom pair. */
internal fun groupedCornerDp(position: GroupedRowPosition): Pair<Int, Int> = when (position) {
    GroupedRowPosition.Single -> GROUPED_OUTER_DP to GROUPED_OUTER_DP
    GroupedRowPosition.First -> GROUPED_OUTER_DP to GROUPED_INNER_DP
    GroupedRowPosition.Middle -> GROUPED_INNER_DP to GROUPED_INNER_DP
    GroupedRowPosition.Last -> GROUPED_INNER_DP to GROUPED_OUTER_DP
}

/** index/count -> shape: outer corners `large`, inner corners `extraSmall`. */
fun groupedRowShape(index: Int, count: Int): Shape {
    val (top, bottom) = groupedCornerDp(groupedRowPosition(index, count))
    return RoundedCornerShape(
        topStart = CornerSize(top.dp), topEnd = CornerSize(top.dp),
        bottomEnd = CornerSize(bottom.dp), bottomStart = CornerSize(bottom.dp),
    )
}

/**
 * One grouped section: a column of `surfaceContainerLow` [Surface] rows 2 dp apart. Each row's [content] gets the row
 * index; put a `ListItem(colors = containerColor = Transparent)` or any row inside it.
 */
@Composable
fun GroupedRows(
    count: Int,
    modifier: Modifier = Modifier,
    row: @Composable (index: Int) -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(GroupedRowGap)) {
        repeat(count) { index ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = groupedRowShape(index, count),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) { row(index) }
        }
    }
}
