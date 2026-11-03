package io.github.justcallmesu.aristotle.data

/** Which notification channel an alert goes to (T10); the Android side owns the channels. */
enum class AlertChannel { TurnFinished, NeedsYou }

/** The notification body before localisation; agent text is already cut to [NotificationText.MAX_TEXT]. */
sealed interface AlertBody {
    data class Text(val text: String) : AlertBody

    /** The turn ended without assistant text. */
    data object TurnFinishedNoText : AlertBody

    /** Never the command itself: a command line can carry a secret. */
    data class Approval(val command: Boolean) : AlertBody
    data class Clarify(val question: String?) : AlertBody

    /** A sudo password or secret value is asked for; never what for, never a value. */
    data object Secret : AlertBody

    /** A vault prompt the PC answers. */
    data object OnPc : AlertBody
}

/** One notification: [tag] is its collapse key, so a newer one for the same session replaces it. */
data class AlertNotice(
    val channel: AlertChannel,
    val tag: String,
    val sessionId: String,
    val title: String?,
    val body: AlertBody,
    /** The prompt a "needs you" notice is for (its dismissal is reported back with it). */
    val promptId: String? = null,
    /** A prompt shown before (a reconnect sends it again): no heads-up, no sound. */
    val repeat: Boolean = false,
)

sealed interface NotifyAction {
    data class Post(val notice: AlertNotice) : NotifyAction
    data class Cancel(val tag: String) : NotifyAction
}

/** Where the user is: the app on screen, and the session open in it (set by the chat screen, T11). */
data class ScreenFocus(val appVisible: Boolean, val openSessionId: String?) {
    fun isLookingAt(sessionId: String): Boolean = appVisible && openSessionId == sessionId
}

object NotificationText {
    const val MAX_TEXT = 120
    private const val ELLIPSIS = "…"

    fun turnTag(sessionId: String): String = "turn:$sessionId"
    fun needsTag(sessionId: String): String = "needs:$sessionId"

    /**
     * The first line worth reading: blank lines and code-fence lines are skipped, a Markdown heading loses
     * its `#`s, whitespace runs become one space. Longer than [max] -> cut (never inside a surrogate pair) + "…".
     */
    fun firstLine(text: String, max: Int = MAX_TEXT): String {
        val line = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("```") }
            ?.trimStart('#')
            ?.replace(WHITESPACE, " ")
            ?.trim()
            .orEmpty()
        if (line.length <= max) return line
        var cut = max - ELLIPSIS.length
        if (Character.isHighSurrogate(line[cut - 1])) cut--
        return line.substring(0, cut).trimEnd() + ELLIPSIS
    }

    private val WHITESPACE = Regex("\\s+")
}

/**
 * Turns [SessionAlert]s into notifications (T10). One "turn finished" per session (a new turn replaces the
 * last), one "needs you" per session, removed when its prompt settles. Nothing for the session the user is
 * looking at. Pure; call from one thread.
 */
class AlertRouter {
    /** Session -> the prompt its "needs you" notification shows. */
    private val shownPrompts = mutableMapOf<String, String>()

    /** Prompt ids posted in this process (they outlive a reconnect) and the ones the user swiped away or opened. */
    private val posted = mutableSetOf<String>()
    private val dismissed = mutableSetOf<String>()

    fun onAlert(alert: SessionAlert, focus: ScreenFocus): List<NotifyAction> = when (alert) {
        is SessionAlert.TurnFinished -> if (focus.isLookingAt(alert.sessionId)) emptyList() else listOf(NotifyAction.Post(turnNotice(alert)))
        is SessionAlert.NeedsYou -> if (focus.isLookingAt(alert.sessionId)) {
            emptyList()
        } else if (alert.promptId in dismissed) {
            emptyList() // the user already got rid of this one
        } else {
            val repeat = !posted.add(alert.promptId)
            shownPrompts[alert.sessionId] = alert.promptId
            listOf(NotifyAction.Post(needsNotice(alert, repeat)))
        }
        is SessionAlert.Settled -> {
            forget(alert.promptId)
            clearShown(alert.sessionId, alert.promptId)
        }
        is SessionAlert.PromptsOpen -> {
            val shown = shownPrompts[alert.sessionId]
            if (shown != null && shown !in alert.promptIds) {
                forget(shown)
                clearShown(alert.sessionId, shown)
            } else {
                emptyList()
            }
        }
    }

    /** The user swiped the notification for [promptId] away: a reconnect must not bring it back. */
    fun onDismissed(sessionId: String, promptId: String) {
        if (shownPrompts[sessionId] == promptId) dismissed += promptId
    }

    private fun forget(promptId: String) {
        posted -= promptId
        dismissed -= promptId
    }

    private fun clearShown(sessionId: String, promptId: String): List<NotifyAction> =
        if (shownPrompts[sessionId] == promptId) {
            shownPrompts.remove(sessionId)
            listOf(NotifyAction.Cancel(NotificationText.needsTag(sessionId)))
        } else {
            emptyList()
        }

    /** The user opened [sessionId] in the app: its notifications are read. */
    fun onOpened(sessionId: String): List<NotifyAction> {
        shownPrompts.remove(sessionId)?.let { dismissed += it }
        return listOf(NotificationText.turnTag(sessionId), NotificationText.needsTag(sessionId)).map(NotifyAction::Cancel)
    }

    private fun turnNotice(alert: SessionAlert.TurnFinished): AlertNotice {
        val line = NotificationText.firstLine(alert.text)
        val body = if (line.isEmpty()) AlertBody.TurnFinishedNoText else AlertBody.Text(line)
        return AlertNotice(AlertChannel.TurnFinished, NotificationText.turnTag(alert.sessionId), alert.sessionId, alert.title, body)
    }

    private fun needsNotice(alert: SessionAlert.NeedsYou, repeat: Boolean): AlertNotice {
        val body = when (alert.kind) {
            PromptKind.Approval -> AlertBody.Approval(alert.command)
            PromptKind.Clarify -> AlertBody.Clarify(alert.question?.let(NotificationText::firstLine)?.ifEmpty { null })
            PromptKind.Secret -> AlertBody.Secret
            PromptKind.OnPc -> AlertBody.OnPc
        }
        return AlertNotice(AlertChannel.NeedsYou, NotificationText.needsTag(alert.sessionId), alert.sessionId, alert.title, body, alert.promptId, repeat)
    }
}
