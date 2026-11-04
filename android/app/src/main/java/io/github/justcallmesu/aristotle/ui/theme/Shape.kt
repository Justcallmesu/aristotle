package io.github.justcallmesu.aristotle.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// docs/design.md §3 (Q75): the Soft scale. Each radius stays below half its control's height: no pills.
internal val AristotleShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Every Button / FilledTonalButton / OutlinedButton / TextButton passes this shape explicitly. */
val ButtonShape = RoundedCornerShape(12.dp)

/** Search fields (home, model sheet). */
val FieldShape = RoundedCornerShape(16.dp)

/** The floating composer field: all four corners. */
val ComposerShape = RoundedCornerShape(24.dp)

/** The 40 dp Send / Stop square icon buttons. */
val SquareIconButtonShape = RoundedCornerShape(12.dp)
