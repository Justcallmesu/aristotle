package io.github.justcallmesu.aristotle.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// docs/design.md §3 (Q39): slightly rounded, no pills.
internal val AristotleShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/** Every Button / FilledTonalButton / OutlinedButton / TextButton passes this shape explicitly. */
val ButtonShape = RoundedCornerShape(8.dp)
