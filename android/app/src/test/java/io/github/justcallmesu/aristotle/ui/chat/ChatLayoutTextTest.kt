package io.github.justcallmesu.aristotle.ui.chat

import androidx.compose.ui.unit.dp
import io.github.justcallmesu.aristotle.data.chat.ChatItem
import io.github.justcallmesu.aristotle.data.chat.NoticeKind
import io.github.justcallmesu.aristotle.data.chat.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TU4: the display-only tool summary, chat rhythm, status line, tables and inline code (ChatText.kt). */
class ChatLayoutTextTest {
    // The 19-live-chat capture's summaries (TU1 C1).
    private val scratch = "/home/justcallmesu/.hermes/profiles/pm/cache/scratch"

    @Test
    fun theHomeDirectoryReadsTilde() {
        assertEquals("~/project", ToolSummary.home("/home/justcallmesu/project"))
        assertEquals("~/x ~", ToolSummary.home("/Users/ana/x /root"))
        assertEquals("cat ~/.bashrc ~/notes", ToolSummary.home("cat \$HOME/.bashrc \${HOME}/notes"))
        // Not a home directory: a path that merely contains "home".
        assertEquals("/srv/home/x /rooted", ToolSummary.home("/srv/home/x /rooted"))
    }

    @Test
    fun aLongPathKeepsItsFileNameFirst() {
        assertEquals("safe.sh · ~/…/scratch", ToolSummary.display("write_file", "$scratch/safe.sh"))
        assertEquals("11-settings.png · ~/…/ui", ToolSummary.display("vision_analyze", "$scratch/ui/11-settings.png"))
        // Short enough to show whole (the mockup's read_file card).
        assertEquals("server/internal/relay/relay.go", ToolSummary.display("read_file", "server/internal/relay/relay.go"))
    }

    @Test
    fun pathsInsideACommandHideTheirMiddle() {
        assertEquals(
            "S=~/…/scratch/safe.sh bash \$S tap 806 165 13-cron 3",
            ToolSummary.display("terminal", "S=$scratch/safe.sh bash \$S tap 806 165 13-cron 3"),
        )
        assertEquals(
            "r='~/project/personal/Aristotle' p=r+'/docs/v1-tickets/tickets.md'",
            ToolSummary.display("execute_code", "r='/home/justcallmesu/project/personal/Aristotle' p=r+'/docs/v1-tickets/tickets.md'"),
        )
        assertEquals("ls /usr/…/site-packages/x.py", ToolSummary.display("terminal", "ls /usr/lib/python3/dist/site-packages/x.py"))
    }

    @Test
    fun shellAssignmentsInFrontOfTheCommandGo() {
        val command = "A=~/Android/Sdk/platform-tools/adb; P=io.github.justcallmesu.aristotle; \$A shell cmd statusbar collapse"
        assertEquals("\$A shell cmd statusbar collapse", ToolSummary.display("terminal", command))
        assertEquals("make test", ToolSummary.dropAssignments("export CI=1 && make test"))
        // Nothing but assignments: kept.
        assertEquals("A=1; B=2;", ToolSummary.dropAssignments("A=1; B=2;"))
        // Not a shell tool: assignments are code and stay.
        assertEquals("x=1; print(x)", ToolSummary.display("execute_code", "x=1; print(x)"))
    }

    @Test
    fun urlsAndShortPathsStay() {
        assertEquals("https://example.com/a/b/c/d/e", ToolSummary.display("web_extract", "https://example.com/a/b/c/d/e"))
        assertEquals("cd server && go test ./...", ToolSummary.display("terminal", "cd server && go test ./..."))
        assertEquals("a/b/c/d", ToolSummary.collapse("a/b/c/d"))
        assertEquals("a/…/d/e/", ToolSummary.collapse("a/b/c/d/e/"))
        assertEquals("", ToolSummary.display("terminal", ""))
    }

    @Test
    fun theGapAboveARowDependsOnTheRowBefore() {
        assertEquals(RowGap.None, ChatRhythm.gap(null, RowKind.User))
        assertEquals(RowGap.Work, ChatRhythm.gap(RowKind.Work, RowKind.Work))
        assertEquals(RowGap.Message, ChatRhythm.gap(RowKind.Message, RowKind.Work))
        assertEquals(RowGap.Message, ChatRhythm.gap(RowKind.Work, RowKind.Message))
        assertEquals(RowGap.Message, ChatRhythm.gap(RowKind.User, RowKind.Message))
        assertEquals(RowGap.Turn, ChatRhythm.gap(RowKind.Message, RowKind.User))
        assertEquals(RowGap.Turn, ChatRhythm.gap(RowKind.Work, RowKind.User))
        assertEquals(RowGap.Message, ChatRhythm.gap(RowKind.User, RowKind.User))
    }

    @Test
    fun rowKinds() {
        assertEquals(RowKind.User, ChatRhythm.kindOf(ChatItem.User("u", "hi")))
        assertEquals(RowKind.Message, ChatRhythm.kindOf(ChatItem.Assistant("a", "hi")))
        assertEquals(RowKind.Work, ChatRhythm.kindOf(ChatItem.Reasoning("r", "")))
        assertEquals(RowKind.Work, ChatRhythm.kindOf(ChatItem.Notice("n", NoticeKind.Info, "")))
        assertEquals(RowKind.Work, ChatRhythm.kindOf(ChatItem.Card("c", process = true, title = "t", text = "")))
        val tool = ChatItem.Tool("t", "t", "terminal", "", ToolStatus.Done)
        assertEquals(RowKind.Work, ChatRhythm.kindOf(TranscriptRow.Folded(listOf(tool, tool.copy(key = "t2"), tool.copy(key = "t3")))))
    }

    @Test
    fun theStatusLineHasOneKindOfSeparator() {
        assertEquals(StatusText("Running · step 8 · pm · opus · Medium", 16), StatusText.of("Running · step 8", listOf("pm", "opus", "Medium")))
        assertEquals(StatusText("pm · opus", 0), StatusText.of(null, listOf("pm", null, "opus", " ")))
        assertEquals(StatusText("", 0), StatusText.of(null, listOf(null)))
        assertEquals(StatusText("Done", 4), StatusText.of("Done", emptyList()))
    }

    @Test
    fun tableColumnsShareTheWidthWithinBounds() {
        assertEquals(200.dp, ChatTables.cellWidth(400.dp, 2))
        assertEquals(ChatTables.CELL_MIN, ChatTables.cellWidth(300.dp, 3))
        assertEquals(ChatTables.CELL_MAX, ChatTables.cellWidth(400.dp, 1))
        assertFalse(ChatTables.asMono(3))
        assertTrue(ChatTables.asMono(4))
    }

    @Test
    fun inlineCodeLosesItsFencesNotItsText() {
        assertEquals("docs.tar.gz", InlineCode.text("`docs.tar.gz`"))
        assertEquals("a ` b", InlineCode.text("`` a ` b ``"))
        assertEquals("  ", InlineCode.text("`  `"))
        assertEquals("one two", InlineCode.text("`one\ntwo`"))
        assertEquals("\u2009x\u2009", InlineCode.padded("`x`"))
        assertEquals("plain", InlineCode.text("plain"))
    }
}
