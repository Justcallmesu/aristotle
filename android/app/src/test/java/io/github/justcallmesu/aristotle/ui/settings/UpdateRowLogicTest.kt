package io.github.justcallmesu.aristotle.ui.settings

import io.github.justcallmesu.aristotle.data.update.AppUpdate
import io.github.justcallmesu.aristotle.data.update.UpdateFailure
import io.github.justcallmesu.aristotle.data.update.UpdateStep
import io.github.justcallmesu.aristotle.data.update.UpdateView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** UP2 (Q132): the About update row offers Update when there is one, Check now otherwise, and fits each step. */
class UpdateRowLogicTest {
    private val update = AppUpdate("2.1.1", 20101, "ab".repeat(32), 1_000)

    @Test
    fun `one action per state`() {
        assertEquals(UpdateAction.Update, UpdateRowLogic.action(UpdateView(available = update)))
        assertEquals(UpdateAction.CheckNow, UpdateRowLogic.action(UpdateView()))
        assertEquals(UpdateAction.Allow, UpdateRowLogic.action(UpdateView(update, UpdateStep.NeedsPermission)))
        assertEquals(UpdateAction.Cancel, UpdateRowLogic.action(UpdateView(update, UpdateStep.Downloading(1, 2))))
        assertEquals(UpdateAction.None, UpdateRowLogic.action(UpdateView(update, UpdateStep.Verifying)))
        assertEquals(UpdateAction.None, UpdateRowLogic.action(UpdateView(update, UpdateStep.Installing)))
        assertEquals(UpdateAction.Confirm, UpdateRowLogic.action(UpdateView(update, UpdateStep.Confirming)))
        assertEquals(UpdateAction.Retry, UpdateRowLogic.action(UpdateView(update, UpdateStep.Failed(UpdateFailure.InstallNotFinished))))
        assertEquals(UpdateAction.Retry, UpdateRowLogic.action(UpdateView(update, UpdateStep.Failed(UpdateFailure.Truncated))))
        assertEquals(UpdateAction.CheckNow, UpdateRowLogic.action(UpdateView(null, UpdateStep.Failed(UpdateFailure.InstallAborted))))
    }

    @Test
    fun `progress only while downloading`() {
        assertEquals(0.25f, UpdateRowLogic.progress(UpdateStep.Downloading(250, 1_000))!!, 0.0001f)
        assertEquals(1f, UpdateRowLogic.progress(UpdateStep.Downloading(2_000, 1_000))!!, 0.0001f)
        assertEquals(0f, UpdateRowLogic.progress(UpdateStep.Downloading(0, 0))!!, 0.0001f)
        assertNull(UpdateRowLogic.progress(UpdateStep.Installing))
    }
}
