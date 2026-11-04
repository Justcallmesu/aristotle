package io.github.justcallmesu.aristotle.data.chat

import io.github.justcallmesu.aristotle.data.HermesJson
import io.github.justcallmesu.aristotle.data.InboundFrame
import io.github.justcallmesu.aristotle.data.RpcError
import io.github.justcallmesu.aristotle.data.RpcException
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TB1: the `process.list` / `process.kill` frames and how their answers read. */
class BackgroundProcessesTest {
    private fun json(text: String) = HermesJson.parseToJsonElement(text)

    @Test
    fun callsCarryOnlyTheKeysHermesTakes() {
        val list = ProcessFrames.list("s1")
        assertEquals("process.list", list.method)
        assertEquals(json("""{"session_id":"s1"}"""), list.params)
        val kill = ProcessFrames.kill("s1", "proc_ab12")
        assertEquals("process.kill", kill.method)
        assertEquals(json("""{"session_id":"s1","process_id":"proc_ab12"}"""), kill.params)
    }

    @Test
    fun aRowsSessionIdIsTheProcessId() {
        val rows = ProcessFrames.processes(
            json(
                """{"processes":[
                {"session_id":"proc_1","command":"npm run dev","cwd":"/w","pid":42,"uptime_seconds":75,"status":"running","output_preview":""},
                {"session_id":"proc_2","command":"make","uptime_seconds":9,"status":"exited","exit_code":2},
                {"command":"no id"}]}""",
            ),
        )
        assertEquals(listOf("proc_1", "proc_2"), rows.map { it.id })
        assertEquals(BackgroundProcess("proc_1", "npm run dev", "running", 75), rows[0])
        assertTrue(rows[0].running)
        assertFalse(rows[1].running)
        assertEquals(2, rows[1].exitCode)
        assertTrue(ProcessFrames.processes(json("""{"x":1}""")).isEmpty())
        assertTrue(ProcessFrames.processes(null).isEmpty())
    }

    @Test
    fun reconcileShowsRunningOnlyAndKeepsPlaces() {
        val a = BackgroundProcess("a", "a", "running", 1)
        val b = BackgroundProcess("b", "b", "running", 1)
        val c = BackgroundProcess("c", "c", "running", 1)
        // Hermes lists c, b, a: a and b keep their places on screen, c is new and goes last.
        val next = ProcessFrames.reconcile(listOf(a, b), listOf(c, b.copy(uptimeSeconds = 6), a.copy(uptimeSeconds = 6)))
        assertEquals(listOf("a", "b", "c"), next.map { it.id })
        assertEquals(6, next[0].uptimeSeconds)
        // A stopped one is gone on the next poll even though Hermes still lists it as exited.
        val after = ProcessFrames.reconcile(next, listOf(a.copy(status = "exited"), b, c))
        assertEquals(listOf("b", "c"), after.map { it.id })
    }

    @Test
    fun killAnswersMapToFourResults() {
        assertEquals(ProcessKill.Stopped, ProcessFrames.killOutcome(json("""{"status":"killed","session_id":"p"}""")))
        assertEquals(ProcessKill.AlreadyExited, ProcessFrames.killOutcome(json("""{"status":"already_exited","command":"x"}""")))
        assertEquals(ProcessKill.NotFound, ProcessFrames.killOutcome(json("""{"status":"not_found","error":"No process with ID p"}""")))
        assertEquals(ProcessKill.Failed("Kill incomplete"), ProcessFrames.killOutcome(json("""{"status":"error","error":"Kill incomplete"}""")))
        assertEquals(ProcessKill.Failed(""), ProcessFrames.killOutcome(json("{}")))
    }

    @Test
    fun killFailuresMapToo() {
        assertEquals(ProcessKill.NotFound, ProcessFrames.killFailure(RpcException(RpcError(4044, "no such process: p"), "x")))
        assertEquals(ProcessKill.NotFound, ProcessFrames.killFailure(RpcException(RpcError(4001, "session not found"), "x")))
        assertEquals(ProcessKill.Failed("boom"), ProcessFrames.killFailure(RpcException(RpcError(5010, "boom"), "x")))
        assertEquals(ProcessKill.NotSent, ProcessFrames.killFailure(RpcException(null, "closed", linkClosed = true, written = false)))
        assertEquals(ProcessKill.Unknown, ProcessFrames.killFailure(RpcException(null, "closed", linkClosed = true, written = true)))
        assertEquals(ProcessKill.Failed("process.kill: no answer in 15 s"), ProcessFrames.killFailure(RpcException(null, "process.kill: no answer in 15 s")))
    }

    @Test
    fun theDesktopsEventsRefreshTheList() {
        fun event(type: String, payload: String) = InboundFrame.Event(type, "s1", json(payload) as JsonObject)
        assertTrue(ProcessFrames.refreshes(event("tool.complete", """{"tool_id":"t","name":"terminal"}""")))
        assertTrue(ProcessFrames.refreshes(event("tool.complete", """{"tool_id":"t","name":"process"}""")))
        assertTrue(ProcessFrames.refreshes(event("status.update", """{"kind":"process","text":"done"}""")))
        assertFalse(ProcessFrames.refreshes(event("tool.complete", """{"tool_id":"t","name":"read_file"}""")))
        assertFalse(ProcessFrames.refreshes(event("status.update", """{"kind":"goal"}""")))
        assertFalse(ProcessFrames.refreshes(InboundFrame.Event("tool.complete", "s1", null)))
    }

    @Test
    fun onlyGoneOrNotOursStopsThePoll() {
        assertTrue(ProcessFrames.stopsPolling(RpcException(RpcError(4001, "session not found"), "x")))
        assertTrue(ProcessFrames.stopsPolling(RpcException(RpcError(4044, "no such process"), "x")))
        assertFalse(ProcessFrames.stopsPolling(RpcException(RpcError(5010, "x"), "x")))
        assertFalse(ProcessFrames.stopsPolling(RpcException(null, "closed", linkClosed = true, written = false)))
    }
}
