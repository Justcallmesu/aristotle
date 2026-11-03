package io.github.justcallmesu.aristotle.data.chat

import io.github.justcallmesu.aristotle.data.HermesJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolTextTest {
    private fun json(text: String) = HermesJson.parseToJsonElement(text)

    @Test
    fun theSummaryPrefersHermesContextThenTheMostTellingArgument() {
        assertEquals("sleep 20 + 1 command", ToolText.summary("sleep 20 + 1 command", json("""{"command":"sleep 20; echo"}""") as JsonObject))
        assertEquals("go test ./...", ToolText.summary(null, json("""{"timeout":60,"command":"go test ./..."}""") as JsonObject))
        assertEquals("a b", ToolText.summary(null, json("""{"note":"a\n  b"}""") as JsonObject))
        assertEquals("""{"n":1}""", ToolText.summary("", json("""{"n":1}""") as JsonObject))
        assertEquals("", ToolText.summary(null, null))
        assertEquals(ToolText.SUMMARY_MAX, ToolText.summary("x".repeat(500), null).length)
    }

    @Test
    fun hermesToolResultsShowTheirOutput() {
        assertEquals("done-sleeping", ToolText.output(JsonPrimitive("""{"output": "done-sleeping", "exit_code": 0, "error": null}""")))
        assertEquals("[Command interrupted]\nexit_code: 130", ToolText.output(JsonPrimitive("""{"output": "[Command interrupted]", "exit_code": 130, "error": null}""")))
        assertEquals("boom", ToolText.output(json("""{"output": "", "error": "boom"}""")))
        assertEquals("plain text", ToolText.output(JsonPrimitive("plain text")))
        assertEquals("{\n    \"ok\": true\n}", ToolText.output(json("""{"ok":true}""")))
        assertEquals("[\n    1\n]", ToolText.output(JsonPrimitive("[1]")))
        assertEquals("", ToolText.output(null))
        assertEquals("text\n--- a\n+++ b", ToolText.output(JsonPrimitive("text"), diff = "--- a\n+++ b"))
        assertEquals("rendered", ToolText.output(JsonPrimitive("raw"), resultText = "rendered"))
    }

    @Test
    fun aStoredResultLooksFailedOnErrorOrExitCode() {
        assertTrue(ToolText.looksFailed(JsonPrimitive("""{"output":"","exit_code":2,"error":null}""")))
        assertTrue(ToolText.looksFailed(json("""{"error":"denied"}""")))
        assertFalse(ToolText.looksFailed(JsonPrimitive("""{"output":"ok","exit_code":0,"error":null}""")))
        assertFalse(ToolText.looksFailed(JsonPrimitive("error: this is just text")))
    }

    @Test
    fun storedArgumentsAreAJsonString() {
        assertEquals(json("""{"path":"a.go"}"""), ToolText.argsOf(JsonPrimitive("""{"path":"a.go"}""")))
        assertEquals(null, ToolText.argsOf(JsonPrimitive("not json")))
    }
}
