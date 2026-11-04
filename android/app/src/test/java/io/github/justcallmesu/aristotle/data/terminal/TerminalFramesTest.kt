package io.github.justcallmesu.aristotle.data.terminal

import io.github.justcallmesu.aristotle.data.ConnectionProblem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Terminal frames (protocol §14): resize JSON, the server's exit/closing messages, paste chunking, close codes. */
class TerminalFramesTest {
    @Test
    fun resizeIsTheProtocolsJsonObject() {
        assertEquals("""{"type":"resize","cols":120,"rows":40}""", TerminalFrames.resize(TermSize(120, 40)))
        assertEquals("""{"type":"resize","cols":80,"rows":24}""", TerminalFrames.resize(TerminalFrames.DEFAULT_SIZE))
    }

    @Test
    fun exitAndClosingAreRead() {
        assertEquals(TerminalControl.Exit(0), TerminalFrames.control("""{"type":"exit","code":0}"""))
        assertEquals(TerminalControl.Exit(130), TerminalFrames.control("""{"code":130,"type":"exit"}"""))
        assertEquals(TerminalControl.Closing("idle_timeout"), TerminalFrames.control("""{"type":"closing","reason":"idle_timeout"}"""))
        assertEquals(TerminalControl.Closing(""), TerminalFrames.control("""{"type":"closing"}"""))
    }

    @Test
    fun anythingElseIsUnknown() {
        listOf(
            """{"type":"exit"}""",
            """{"type":"exit","code":"0"}""",
            """{"type":"resize","cols":1,"rows":1}""",
            """{"type":7}""",
            """[1,2]""",
            "not json",
            "",
        ).forEach { assertEquals(it, TerminalControl.Unknown, TerminalFrames.control(it)) }
    }

    @Test
    fun pasteIsSplitIntoSixteenKibFramesInOrder() {
        val paste = ByteArray(40_000) { (it % 251).toByte() }
        val chunks = TerminalFrames.chunks(paste)
        assertEquals(listOf(16_384, 16_384, 7_232), chunks.map { it.size })
        assertArrayEquals(paste, chunks.reduce { a, b -> a + b })
        assertTrue(chunks.all { it.size <= TerminalFrames.MAX_INPUT_FRAME })
    }

    @Test
    fun chunkingEdges() {
        assertEquals(emptyList<ByteArray>(), TerminalFrames.chunks(ByteArray(0)))
        assertEquals(listOf(16_384, 16_384), TerminalFrames.chunks(ByteArray(32_768)).map { it.size })
        assertEquals(listOf(1), TerminalFrames.chunks(byteArrayOf(3)).map { it.size })
        // Well under the server's 64 KiB frame cap (a larger frame closes 1009).
        assertTrue(TerminalFrames.MAX_INPUT_FRAME <= 64 * 1024)
    }

    @Test
    fun closeCodesMapToWhatTheScreenSays() {
        assertEquals(TerminalEnd.Exited(3), TerminalEnd.ofClose(1000, "exit", exitCode = 3))
        assertEquals(TerminalEnd.Exited(null), TerminalEnd.ofClose(1000, "", exitCode = null))
        assertEquals(TerminalEnd.ServerStopping, TerminalEnd.ofClose(1001, "going_away", null))
        assertEquals(TerminalEnd.ConnectionChanged, TerminalEnd.ofClose(1006, "", null))
        assertEquals(TerminalEnd.ConnectionChanged, TerminalEnd.ofClose(1011, "pong_timeout", null))
        assertEquals(TerminalEnd.ShellFailed, TerminalEnd.ofClose(1011, "shell_start_failed", null))
        assertEquals(TerminalEnd.Revoked, TerminalEnd.ofClose(4401, "revoked", null))
        assertEquals(TerminalEnd.TurnedOff, TerminalEnd.ofClose(4403, "terminal_disabled", null))
        assertEquals(TerminalEnd.NotGranted, TerminalEnd.ofClose(4403, "terminal_not_granted", null))
        assertEquals(TerminalEnd.Idle, TerminalEnd.ofClose(4408, "idle_timeout", null))
        assertEquals(TerminalEnd.MaxLength, TerminalEnd.ofClose(4408, "max_session", null))
        assertEquals(TerminalEnd.TooMany, TerminalEnd.ofClose(4430, "too_many_terminals", null))
        assertEquals(TerminalEnd.Other("close 1008 bad_control_frame"), TerminalEnd.ofClose(1008, "bad_control_frame", null))
        assertEquals(TerminalEnd.Other("close 4999"), TerminalEnd.ofClose(4999, "", null))
    }

    @Test
    fun refusedUpgradesAndBrokenLinks() {
        val none = ConnectionProblem.Unreachable
        assertEquals(TerminalEnd.Revoked, TerminalEnd.ofFailure(false, 401, """{"error":"unauthorized"}""", none))
        assertEquals(TerminalEnd.TurnedOff, TerminalEnd.ofFailure(false, 403, """{"error":"terminal_disabled"}""" + "\n", none))
        assertEquals(TerminalEnd.NotGranted, TerminalEnd.ofFailure(false, 403, """{"error":"terminal_not_granted"}""", none))
        // The listener's peer gate: 403 with the text body `forbidden` is not a switch-off (protocol §14).
        assertEquals(TerminalEnd.Refused("/v1/terminal HTTP 403"), TerminalEnd.ofFailure(false, 403, "forbidden\n", none))
        assertEquals(TerminalEnd.Refused("/v1/terminal HTTP 404"), TerminalEnd.ofFailure(false, 404, null, none))
        assertEquals(
            TerminalEnd.Refused("certificate SPKI SHA-256 differs from the pinned fingerprint"),
            TerminalEnd.ofFailure(false, null, null, ConnectionProblem.CertificateChanged),
        )
        assertEquals(TerminalEnd.Unreachable, TerminalEnd.ofFailure(false, null, null, none))
        assertEquals(TerminalEnd.ConnectionChanged, TerminalEnd.ofFailure(true, null, null, none))
    }

    @Test
    fun onlyASwitchOffHidesTheEntryAndRevokeOrSwitchOffOffersNoReopen() {
        assertTrue(TerminalEnd.TurnedOff.hidesEntry)
        assertTrue(TerminalEnd.NotGranted.hidesEntry)
        assertFalse(TerminalEnd.ConnectionChanged.hidesEntry)
        assertFalse(TerminalEnd.Revoked.canReopen)
        assertFalse(TerminalEnd.TurnedOff.canReopen)
        assertTrue(TerminalEnd.ConnectionChanged.canReopen)
        assertTrue(TerminalEnd.Exited(0).canReopen)
        assertTrue(TerminalEnd.Idle.canReopen)
    }
}
