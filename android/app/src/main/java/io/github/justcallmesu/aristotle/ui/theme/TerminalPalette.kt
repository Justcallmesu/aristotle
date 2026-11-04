package io.github.justcallmesu.aristotle.ui.theme

/**
 * The terminal's colours (T19b), as Termux colour-scheme properties (`#rrggbb`). Default foreground, background and
 * cursor are design.md palette A (onSurface, surface, primary). The 16 ANSI colours: on the dark surface Termux's own
 * xterm defaults read well and are kept; on the light parchment they are replaced by darker inks so `white`,
 * `yellow` and `cyan` text stays readable (all ≥ 5.2:1 on #F5EEDF, computed WCAG ratios).
 */
object TerminalPalette {
    val light: Map<String, String> = mapOf(
        "foreground" to "#2B2119",
        "background" to "#F5EEDF",
        "cursor" to "#3B2E23",
        "color0" to "#2B2119",
        "color1" to "#A32019",
        "color2" to "#2E6330",
        "color3" to "#6E5200",
        "color4" to "#2D5781",
        "color5" to "#723A71",
        "color6" to "#245C50",
        "color7" to "#6A5948",
        "color8" to "#6E5E4E",
        "color9" to "#B3261E",
        "color10" to "#356F37",
        "color11" to "#7A5B00",
        "color12" to "#33628F",
        "color13" to "#80427F",
        "color14" to "#2C6A5C",
        "color15" to "#3A3029",
    )

    val dark: Map<String, String> = mapOf(
        "foreground" to "#EDE3D1",
        "background" to "#17130F",
        "cursor" to "#E8D6B8",
    )

    fun of(dark: Boolean): Map<String, String> = if (dark) this.dark else light
}
