package io.github.justcallmesu.aristotle.data.terminal

import io.github.justcallmesu.aristotle.data.HermesJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** A terminal size in character cells. */
data class TermSize(val cols: Int, val rows: Int)

/** What a server text frame on `/v1/terminal` says (docs/protocol.md §14 Frames). */
sealed interface TerminalControl {
    /** The shell exited with [code] (128+signal when killed); a `1000` close follows. */
    data class Exit(val code: Int) : TerminalControl

    /** The server decided to close; [reason] is the close reason that follows. */
    data class Closing(val reason: String) : TerminalControl

    /** Any other text frame: ignored. */
    data object Unknown : TerminalControl
}

/** The frames the phone sends and reads on the terminal socket. Pure; terminal content never passes through here. */
object TerminalFrames {
    /**
     * Phone frames are capped at 64 KiB by the server (`1009` above it); the phone splits input at 16 KiB (T19b brief), so
     * a paste never comes near the cap and a large one is read by the server at its 64 KiB/s pace in small steps.
     */
    const val MAX_INPUT_FRAME = 16 * 1024

    /** The size the server starts the PTY with; sent until the view has measured itself. */
    val DEFAULT_SIZE = TermSize(80, 24)

    /** `{"type":"resize","cols":C,"rows":R}`; the server clamps to 20-500 x 5-200, the phone sends what fits. */
    fun resize(size: TermSize): String = buildJsonObject {
        put("type", "resize")
        put("cols", size.cols)
        put("rows", size.rows)
    }.toString()

    /** Reads a server text frame; anything that is not one of the two known objects is [TerminalControl.Unknown]. */
    fun control(text: String): TerminalControl {
        val obj = runCatching { HermesJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return TerminalControl.Unknown
        return when ((obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content) {
            "exit" -> obj.int("code")?.let(TerminalControl::Exit) ?: TerminalControl.Unknown
            "closing" -> TerminalControl.Closing((obj["reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty())
            else -> TerminalControl.Unknown
        }
    }

    /** Splits input into frames of at most [max] bytes, in order; empty input sends nothing. */
    fun chunks(bytes: ByteArray, max: Int = MAX_INPUT_FRAME): List<ByteArray> {
        require(max > 0)
        if (bytes.isEmpty()) return emptyList()
        return (bytes.indices step max).map { start -> bytes.copyOfRange(start, minOf(start + max, bytes.size)) }
    }

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
}
