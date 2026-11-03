package io.github.justcallmesu.aristotle.data.chat

import io.github.justcallmesu.aristotle.data.HermesJson
import io.github.justcallmesu.aristotle.data.InboundFrame
import io.github.justcallmesu.aristotle.data.RpcFrames
import java.io.File
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The frames captured by the v0 spike against the real Hermes (`docs/spikes/frames/`), read as the phone would
 * get them. Each line is `{"client", "dir", "frame", "ts"}`; [line] is its 1-based line number in the file.
 */
object CapturedFrames {
    /** The session every capture talks to. */
    const val SESSION = "local-42f9aa95e63c6e33e15cfea13561b1c74191c70798ba168a87af20657a0994d2"

    data class Line(val line: Int, val dir: String, val frame: JsonObject, val atMillis: Long)

    fun lines(name: String): List<Line> = file(name).readLines().mapIndexedNotNull { index, text ->
        if (text.isBlank()) return@mapIndexedNotNull null
        val obj = HermesJson.parseToJsonElement(text) as JsonObject
        val ts = (obj["ts"] as? JsonPrimitive)?.contentOrNull?.let { Instant.parse(it).toEpochMilli() } ?: 0L
        Line(index + 1, (obj["dir"] as JsonPrimitive).content, obj["frame"] as JsonObject, ts)
    }

    /** The inbound session events of [name] with the lines they came from, in order. */
    fun events(name: String, through: Int = Int.MAX_VALUE): List<Pair<Int, ChatFrame>> = lines(name)
        .filter { it.dir == "in" && it.line <= through }
        .mapNotNull { line -> (RpcFrames.parse(line.frame.toString()) as? InboundFrame.Event)?.let { line.line to ChatFrame(it, line.atMillis) } }

    /** The `result` of the response on [line]. */
    fun result(name: String, line: Int): JsonObject = lines(name).first { it.line == line }.frame["result"] as JsonObject

    fun replay(name: String, start: ChatState = ChatState(SESSION), through: Int = Int.MAX_VALUE): ChatState =
        events(name, through).fold(start) { state, (_, frame) -> ChatReducer.reduce(state, frame) }

    fun frameAt(name: String, line: Int): ChatFrame = events(name).first { it.first == line }.second

    private fun file(name: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "docs/spikes/frames/$name")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("captured frames $name not found above ${System.getProperty("user.dir")}")
    }
}
