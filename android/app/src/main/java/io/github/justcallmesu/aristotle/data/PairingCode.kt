package io.github.justcallmesu.aristotle.data

/** Pairing code rules, matching the server (server/internal/pairing: Normalize, Alphabet, CodeLength). */
object PairingCode {
    const val LENGTH = 8
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val GROUP = 4

    /** Case-insensitive; spaces and hyphens ignored; O reads as 0, I and L read as 1. */
    fun normalize(input: String): String = buildString {
        for (c in input) {
            when (c) {
                '-', ' ' -> Unit
                'o', 'O' -> append('0')
                'i', 'I', 'l', 'L' -> append('1')
                in 'a'..'z' -> append(c.uppercaseChar())
                else -> append(c)
            }
        }
    }

    /** What the code field keeps while typing: normalised, only alphabet characters, at most 8. */
    fun sanitize(input: String): String = normalize(input).filter { it in ALPHABET }.take(LENGTH)

    fun isComplete(input: String): Boolean {
        val code = normalize(input)
        return code.length == LENGTH && code.all { it in ALPHABET }
    }

    /** Exactly as the server issues a code: 8 alphabet characters, uppercase, nothing to normalise (the QR path). */
    fun isIssued(code: String): Boolean = code.length == LENGTH && code.all { it in ALPHABET }

    /** Two groups of four, e.g. "K7Q2 M9XD". */
    fun grouped(input: String): String = sanitize(input).chunked(GROUP).joinToString(" ")
}
