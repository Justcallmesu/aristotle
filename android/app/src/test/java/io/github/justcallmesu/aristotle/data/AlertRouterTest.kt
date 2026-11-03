package io.github.justcallmesu.aristotle.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertRouterTest {
    private val away = ScreenFocus(appVisible = false, openSessionId = null)

    private fun post(actions: List<NotifyAction>): AlertNotice = (actions.single() as NotifyAction.Post).notice

    @Test
    fun passwordAndVaultRequestsGetTheirOwnBodyWithoutDetails() {
        val router = AlertRouter()
        val secret = post(router.onAlert(SessionAlert.NeedsYou("s1", "T", "srq-1", PromptKind.Secret, null), away))
        assertEquals(AlertBody.Secret, secret.body)
        assertEquals("srq-1", secret.promptId)
        assertEquals(AlertChannel.NeedsYou, secret.channel)
        assertEquals(AlertBody.OnPc, post(router.onAlert(SessionAlert.NeedsYou("s2", "T", "srq-2", PromptKind.OnPc, null), away)).body)
        assertEquals(listOf(NotifyAction.Cancel(NotificationText.needsTag("s1"))), router.onAlert(SessionAlert.Settled("s1", "srq-1"), away))
    }

    @Test
    fun firstLineSkipsBlankAndFenceLinesAndDropsHeadingMarks() {
        assertEquals("pong", NotificationText.firstLine("pong"))
        assertEquals("one", NotificationText.firstLine("one\ntwo\nthree\nsteered"))
        assertEquals("Done: tests pass", NotificationText.firstLine("\n\n  ```kotlin\n## Done:   tests\tpass\nmore"))
        assertEquals("", NotificationText.firstLine(""))
        assertEquals("", NotificationText.firstLine(" \n```\n"))
    }

    @Test
    fun firstLineIsCutAt120WithAnEllipsis() {
        val exact = "a".repeat(120)
        assertEquals(exact, NotificationText.firstLine(exact))
        val cut = NotificationText.firstLine("b".repeat(300))
        assertEquals(120, cut.length)
        assertTrue(cut.endsWith("…"))
        assertEquals("b".repeat(119), cut.dropLast(1))
    }

    @Test
    fun theCutNeverSplitsASurrogatePair() {
        val text = "x".repeat(118) + "😀" + "tail"
        val cut = NotificationText.firstLine(text)
        assertEquals("x".repeat(118) + "…", cut)
        assertTrue(cut.length <= 120)
    }

    @Test
    fun aTurnIsCollapsedPerSession() {
        val router = AlertRouter()
        val first = post(router.onAlert(SessionAlert.TurnFinished("s1", "Fix relay test", "All green.\nDetails…"), away))
        val second = post(router.onAlert(SessionAlert.TurnFinished("s1", "Fix relay test", "Second turn"), away))
        val other = post(router.onAlert(SessionAlert.TurnFinished("s2", null, "x"), away))
        assertEquals("turn:s1", first.tag)
        assertEquals(first.tag, second.tag)
        assertEquals("turn:s2", other.tag)
        assertEquals(AlertChannel.TurnFinished, first.channel)
        assertEquals("Fix relay test", first.title)
        assertEquals(AlertBody.Text("All green."), first.body)
        assertEquals(null, other.title)
    }

    @Test
    fun aTurnWithoutTextStillSaysItFinished() {
        val notice = post(AlertRouter().onAlert(SessionAlert.TurnFinished("s1", "t", "  \n"), away))
        assertEquals(AlertBody.TurnFinishedNoText, notice.body)
    }

    @Test
    fun nothingForTheSessionOnScreenButOthersStillNotify() {
        val router = AlertRouter()
        val looking = ScreenFocus(appVisible = true, openSessionId = "s1")
        assertEquals(emptyList<NotifyAction>(), router.onAlert(SessionAlert.TurnFinished("s1", "t", "x"), looking))
        assertEquals(emptyList<NotifyAction>(), router.onAlert(SessionAlert.NeedsYou("s1", "t", "p1", PromptKind.Approval, null), looking))
        assertEquals("turn:s2", post(router.onAlert(SessionAlert.TurnFinished("s2", "t", "x"), looking)).tag)
        // The same session open but the app in the background: the user is not looking.
        val backgrounded = looking.copy(appVisible = false)
        assertEquals("turn:s1", post(router.onAlert(SessionAlert.TurnFinished("s1", "t", "x"), backgrounded)).tag)
    }

    @Test
    fun approvalsNeverShowTheCommandAndClarifyShowsTheQuestion() {
        val router = AlertRouter()
        val approval = post(router.onAlert(SessionAlert.NeedsYou("s1", "t", "p1", PromptKind.Approval, null), away))
        assertEquals(AlertChannel.NeedsYou, approval.channel)
        assertEquals("needs:s1", approval.tag)
        assertEquals(AlertBody.Approval(command = false), approval.body)
        val cmd = post(router.onAlert(SessionAlert.NeedsYou("s4", "t", "p4", PromptKind.Approval, null, command = true), away))
        assertEquals(AlertBody.Approval(command = true), cmd.body)
        val clarify = post(router.onAlert(SessionAlert.NeedsYou("s2", "t", "p2", PromptKind.Clarify, "Which do you pick?\nred or blue"), away))
        assertEquals(AlertBody.Clarify("Which do you pick?"), clarify.body)
        val blank = post(router.onAlert(SessionAlert.NeedsYou("s3", "t", "p3", PromptKind.Clarify, " "), away))
        assertEquals(AlertBody.Clarify(null), blank.body)
    }

    @Test
    fun aSettledPromptRemovesOnlyItsOwnNotification() {
        val router = AlertRouter()
        router.onAlert(SessionAlert.NeedsYou("s1", "t", "p1", PromptKind.Approval, null), away)
        assertEquals(emptyList<NotifyAction>(), router.onAlert(SessionAlert.Settled("s1", "p0"), away))
        router.onAlert(SessionAlert.NeedsYou("s1", "t", "p2", PromptKind.Approval, null), away)
        assertEquals(emptyList<NotifyAction>(), router.onAlert(SessionAlert.Settled("s1", "p1"), away))
        assertEquals(listOf(NotifyAction.Cancel("needs:s1")), router.onAlert(SessionAlert.Settled("s1", "p2"), away))
        assertEquals(emptyList<NotifyAction>(), router.onAlert(SessionAlert.Settled("s1", "p2"), away))
    }

    private fun needs(prompt: String, sid: String = "s1") = SessionAlert.NeedsYou(sid, "t", prompt, PromptKind.Approval, null)

    @Test
    fun aPromptSentAgainAfterAReconnectIsPostedSilently() {
        val router = AlertRouter()
        val first = post(router.onAlert(needs("p1"), away))
        assertEquals(false, first.repeat)
        assertEquals("p1", first.promptId)
        val again = post(router.onAlert(needs("p1"), away))
        assertEquals(true, again.repeat)
        assertEquals(false, post(router.onAlert(needs("p2"), away)).repeat)
    }

    @Test
    fun aDismissedPromptIsNotRestoredByAReconnect() {
        val router = AlertRouter()
        router.onAlert(needs("p1"), away)
        router.onDismissed("s1", "p1")
        assertEquals(emptyList<NotifyAction>(), router.onAlert(needs("p1"), away))
        // A different prompt is new again.
        assertEquals(false, post(router.onAlert(needs("p2"), away)).repeat)
    }

    @Test
    fun aDismissOfAnOlderPromptIsIgnored() {
        val router = AlertRouter()
        router.onAlert(needs("p1"), away)
        router.onAlert(needs("p2"), away)
        router.onDismissed("s1", "p1")
        assertEquals(true, post(router.onAlert(needs("p1"), away)).repeat)
    }

    @Test
    fun aSnapshotWithoutTheShownPromptCancelsItsNotification() {
        val router = AlertRouter()
        router.onAlert(needs("p1"), away)
        assertEquals(emptyList<NotifyAction>(), router.onAlert(SessionAlert.PromptsOpen("s1", setOf("p1")), away))
        assertEquals(listOf(NotifyAction.Cancel("needs:s1")), router.onAlert(SessionAlert.PromptsOpen("s1", emptySet()), away))
        assertEquals(emptyList<NotifyAction>(), router.onAlert(SessionAlert.PromptsOpen("s1", emptySet()), away))
        // The prompt settled while the link was down: it is new if it ever comes back.
        assertEquals(false, post(router.onAlert(needs("p1"), away)).repeat)
    }

    @Test
    fun openingASessionClearsItsNotifications() {
        val router = AlertRouter()
        router.onAlert(SessionAlert.NeedsYou("s1", "t", "p1", PromptKind.Approval, null), away)
        assertEquals(listOf(NotifyAction.Cancel("turn:s1"), NotifyAction.Cancel("needs:s1")), router.onOpened("s1"))
        assertEquals(emptyList<NotifyAction>(), router.onAlert(SessionAlert.Settled("s1", "p1"), away))
    }
}
