package io.github.justcallmesu.aristotle.ui.chat

import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.Network
import io.github.justcallmesu.aristotle.data.ServerEndpoint
import io.github.justcallmesu.aristotle.data.chat.BackgroundProcess
import io.github.justcallmesu.aristotle.data.chat.ChatFeedState
import io.github.justcallmesu.aristotle.data.chat.ChatState
import io.github.justcallmesu.aristotle.data.chat.Subagent
import io.github.justcallmesu.aristotle.data.chat.SubagentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TB1: when the status stack shows and when the chat polls, as pure rules. */
class StatusStackLogicTest {
    private val online = LinkState.Online(ServerEndpoint("192.168.1.61", 8760, Network.Lan), 7)
    private val loaded = ChatFeedState(ChatState("s1"), loaded = true, confirmedGeneration = 7)
    private val process = BackgroundProcess("proc_1", "npm run dev", "running", 60)

    private fun child(id: String, status: SubagentStatus) = Subagent(id, "goal $id", status, startedAtMillis = 0)

    @Test
    fun theStackHidesWhenBothSectionsAreEmpty() {
        assertFalse(BackgroundLogic.stackVisible(emptyList(), emptyList()))
        assertTrue(BackgroundLogic.stackVisible(listOf(child("a", SubagentStatus.Running)), emptyList()))
        assertTrue(BackgroundLogic.stackVisible(emptyList(), listOf(process)))
    }

    @Test
    fun onlyRunningOrQueuedChildrenAreRows() {
        val rows = BackgroundLogic.subagentRows(
            listOf(
                child("a", SubagentStatus.Running),
                child("b", SubagentStatus.Completed),
                child("c", SubagentStatus.Queued),
                child("d", SubagentStatus.Failed),
                child("e", SubagentStatus.Interrupted),
            ),
        )
        assertEquals(listOf("a", "c"), rows.map { it.id })
        // Finished children only: the section (and with no process, the stack) is gone.
        assertFalse(BackgroundLogic.stackVisible(BackgroundLogic.subagentRows(listOf(child("b", SubagentStatus.Completed))), emptyList()))
    }

    @Test
    fun itPollsOnlyVisibleLiveAndOnline() {
        assertTrue(BackgroundLogic.polls(visible = true, feed = loaded, link = online, goneFor = null))
        assertFalse(BackgroundLogic.polls(visible = false, feed = loaded, link = online, goneFor = null))
        assertFalse(BackgroundLogic.polls(true, loaded, LinkState.Connecting, null))
        assertFalse(BackgroundLogic.polls(true, loaded.copy(loaded = false), online, null))
        // The resume on this socket was refused: the chat is not live.
        assertFalse(BackgroundLogic.polls(true, loaded.copy(problem = "refused"), online, null))
        assertFalse(BackgroundLogic.polls(true, loaded.copy(confirmedGeneration = null), online, null))
    }

    @Test
    fun aGoneSessionStaysQuietUntilItIsSubscribedAgain() {
        assertFalse(BackgroundLogic.polls(true, loaded, online, goneFor = 7))
        assertTrue(BackgroundLogic.polls(true, loaded.copy(confirmedGeneration = 8), online.copy(generation = 8), goneFor = 7))
    }

    @Test
    fun uptimeTicksOnFromTheAnswer() {
        assertEquals(60, BackgroundLogic.uptime(process, polledAt = 10_000, now = 10_999))
        assertEquals(65, BackgroundLogic.uptime(process, polledAt = 10_000, now = 15_000))
        assertEquals(60, BackgroundLogic.uptime(process, polledAt = 10_000, now = 9_000))
    }

    @Test
    fun theDialogsStopCountsOnlyOnceArmedAndWithNoKillOnItsWay() {
        val open = BackgroundState(processes = listOf(process), confirm = StopConfirm(process, polledAt = 0, at = 1_000))
        assertFalse(BackgroundLogic.confirmable(open, now = 1_000 + BackgroundLogic.ARM_MS - 1))
        assertTrue(BackgroundLogic.confirmable(open, now = 1_000 + BackgroundLogic.ARM_MS))
        assertFalse(BackgroundLogic.confirmable(open.copy(stopping = "proc_0"), now = 10_000))
        assertFalse(BackgroundLogic.confirmable(open.copy(confirm = null), now = 10_000))
    }

    @Test
    fun theStackFoldsWhileTheKeyboardIsUpAndIsCappedByTheRoomLeft() {
        assertTrue(BackgroundLogic.stackLayout(imeVisible = true, spaceLeft = 1000).collapsed)
        assertFalse(BackgroundLogic.stackLayout(imeVisible = false, spaceLeft = 1000).collapsed)
        assertEquals(400, BackgroundLogic.stackLayout(imeVisible = false, spaceLeft = 1000).maxHeight)
        // Little room left after the composer: the cap follows it, not the window.
        assertEquals(120, BackgroundLogic.stackLayout(imeVisible = false, spaceLeft = 300).maxHeight)
        assertEquals(0, BackgroundLogic.stackLayout(imeVisible = false, spaceLeft = -5).maxHeight)
    }

    @Test
    fun theTimerRunsOnlyWhileARowRuns() {
        assertFalse(BackgroundLogic.timerRuns(emptyList()))
        assertFalse(BackgroundLogic.timerRuns(listOf(process.copy(status = "exited"))))
        assertTrue(BackgroundLogic.timerRuns(listOf(process)))
    }
}
