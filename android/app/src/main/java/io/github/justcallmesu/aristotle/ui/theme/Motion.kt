package io.github.justcallmesu.aristotle.ui.theme

import androidx.compose.animation.core.CubicBezierEasing

/** The motion curves of design.md §4. */
object Motion {
    /** Things entering: expand, fade in. */
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** Things leaving. */
    val StandardAccelerate = CubicBezierEasing(0.3f, 0f, 1f, 1f)
}
