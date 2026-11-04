package io.github.justcallmesu.aristotle.data.terminal

/**
 * Bytes for typed characters, with the Ctrl and Alt modifiers applied as a terminal does: Ctrl maps a letter to its
 * control code (Ctrl-C = 0x03), Alt prefixes ESC. Special keys (arrows, Home, F-keys) go through Termux's
 * `KeyHandler` instead, which knows the emulator's cursor and keypad modes. Pure.
 */
object KeyBytes {
    private const val ESC = 0x1b

    /** The code point Ctrl turns [codePoint] into, as xterm and Termux do; unchanged when Ctrl has no meaning for it. */
    fun ctrl(codePoint: Int): Int = when (codePoint) {
        in 'a'.code..'z'.code -> codePoint - 'a'.code + 1
        in 'A'.code..'Z'.code -> codePoint - 'A'.code + 1
        ' '.code, '2'.code, '@'.code -> 0
        '['.code, '3'.code -> ESC
        '\\'.code, '4'.code -> 28
        ']'.code, '5'.code -> 29
        '^'.code, '6'.code, '~'.code -> 30
        '_'.code, '7'.code, '/'.code, '-'.code -> 31
        '8'.code, '?'.code -> 127
        else -> codePoint
    }

    /** UTF-8 for [codePoint] after [ctrlOn]/[altOn]; empty for a surrogate or out-of-range value. */
    fun of(codePoint: Int, ctrlOn: Boolean, altOn: Boolean): ByteArray {
        if (codePoint !in 0..Character.MAX_CODE_POINT || codePoint in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code) return ByteArray(0)
        val mapped = if (ctrlOn) ctrl(codePoint) else codePoint
        val utf8 = String(Character.toChars(mapped)).toByteArray(Charsets.UTF_8)
        return if (altOn) byteArrayOf(ESC.toByte()) + utf8 else utf8
    }

    /** Text from the keyboard: newlines become CR (what Enter sends), the rest is UTF-8 unchanged. */
    fun text(text: CharSequence): ByteArray = text.toString().replace("\r\n", "\r").replace('\n', '\r').toByteArray(Charsets.UTF_8)
}
