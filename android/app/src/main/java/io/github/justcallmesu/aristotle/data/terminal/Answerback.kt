package io.github.justcallmesu.aristotle.data.terminal

/**
 * Which replies the emulator may send back to the PC on its own (protocol §14: "does not answer terminal queries that
 * echo data back"; T19 red-team m3). Only fixed-form numeric replies pass: device attributes and status (`CSI … c`,
 * `CSI … n`), cursor position (`CSI … R`), mode reports (`CSI ? … $ y`), window size reports (`CSI … t`) and colour
 * reports (`OSC N;rgb:…`). Every DCS reply (DECRQSS, XTGETTCAP, which echo the query's text) and the window label
 * reports are dropped, so output from the PC can never type text of its choosing into the shell. Pure.
 */
object Answerback {
    private val csiReply = Regex("""\u001b\[[?>]?[0-9;]*\$?[cnRty]""")
    private val colourReply = Regex("""\u001b\][0-9;]+;rgb:[0-9a-f]{4}/[0-9a-f]{4}/[0-9a-f]{4}(\u0007|\u001b\\)""")

    fun allowed(reply: ByteArray, offset: Int = 0, count: Int = reply.size): Boolean {
        if (count <= 0 || count > MAX_REPLY) return false
        val text = String(reply, offset, count, Charsets.ISO_8859_1)
        return csiReply.matches(text) || colourReply.matches(text)
    }

    /** No allowed reply is longer than this. */
    private const val MAX_REPLY = 64
}
