package io.github.justcallmesu.aristotle.ui.chat

import io.github.justcallmesu.aristotle.data.chat.ChatState
import io.github.justcallmesu.aristotle.data.chat.Prompt

/** A one-off line under the chat (a snackbar) about an answer. */
sealed interface PromptMessage {
    /** Hermes says another screen answered first (`already_resolved`, or a batch lock `expired`). */
    data object AnsweredFirst : PromptMessage

    /** The link was down: nothing was sent; the sheet stays. */
    data object NotSent : PromptMessage

    /** Hermes refused the answer (a stale generation, a malformed answer): [reason] is its error message. */
    data class Refused(val reason: String) : PromptMessage
}

/**
 * The prompt sheet (design.md §5 approval/clarify sheet, Q41). Never holds a typed password or secret: that stays in
 * the sheet's own composable state until it is sent (Q51).
 *
 * @property open the user wants the sheet up (a new prompt, the waiting chip, a notification tap); back hides it.
 * @property sending the prompt whose answer is on its way (its buttons are disabled).
 * @property answered prompts this phone answered: their settle is not a withdrawal, and the sheet skips them.
 * @property confirmAlways the approval whose "Always allow" waits for the second confirmation.
 * @property withdrawn a prompt answered elsewhere while the sheet showed it ("Answered elsewhere", closes by itself).
 * @property seen prompts the sheet already knows (a new one raises the sheet).
 * @property settledSerial the last [io.github.justcallmesu.aristotle.data.chat.SettledPrompt.serial] handled.
 * @property showing the prompt the sheet showed at the last update (a settle of it while open is a withdrawal).
 * @property shown what the sheet has on screen to answer and since when: nothing is sent for it before
 *   [PromptSheetLogic.ARM_MS] (T12b tap-through guard).
 * @property unconfirmed sudo/secret answers sent as a response frame, which gets no reply, by the link generation they
 *   went out on: a new socket may have lost them, so they are answerable again there.
 * @property authorizing the prompt whose "Always allow" or sudo/secret answer waits for the app lock's fresh check
 *   (Q61); its buttons are off until the check ends.
 */
data class PromptSheetState(
    val open: Boolean = false,
    val sending: String? = null,
    val answered: Set<String> = emptySet(),
    val confirmAlways: String? = null,
    val withdrawn: Prompt? = null,
    val message: PromptMessage? = null,
    val seen: Set<String> = emptySet(),
    val settledSerial: Long? = null,
    val showing: String? = null,
    val shown: Shown? = null,
    val unconfirmed: Map<String, Long> = emptyMap(),
    val authorizing: String? = null,
) {
    /** The prompt the sheet shows: the oldest still open that this phone has not answered. */
    fun current(chat: ChatState): Prompt? = chat.prompts.firstOrNull { it.key !in answered }

    /** The sheet is on screen. */
    fun visible(chat: ChatState): Boolean = withdrawn != null || (open && current(chat) != null)
}

/**
 * The prompt on screen ([confirming]: its "Always allow" confirmation), shown at [at] on the ViewModel's clock.
 * [armed] turns its buttons on once [PromptSheetLogic.ARM_MS] passed; the ViewModel checks the time itself.
 */
data class Shown(val key: String, val confirming: Boolean, val at: Long, val armed: Boolean = false)

/** How the sheet follows the chat state. Pure. */
object PromptSheetLogic {
    /**
     * How long a prompt must be on screen before an answer for it counts: a double tap, or a tap that lands as the
     * sheet swaps in a new prompt (or rises under the finger), must not answer a prompt never seen (T12 MAJOR-1).
     */
    const val ARM_MS = 600L

    /** Sets [PromptSheetState.shown] from what is on screen now; the same prompt (and step) keeps its time. */
    fun track(sheet: PromptSheetState, chat: ChatState, now: Long): PromptSheetState {
        val prompt = sheet.current(chat)?.takeIf { sheet.open && sheet.withdrawn == null }
        val confirming = prompt != null && sheet.confirmAlways == prompt.key
        val shown = sheet.shown
        return when {
            prompt == null -> if (shown == null) sheet else sheet.copy(shown = null)
            shown != null && shown.key == prompt.key && shown.confirming == confirming -> sheet
            else -> sheet.copy(shown = Shown(prompt.key, confirming, now))
        }
    }

    /** An answer for [key] ([confirming]: the "Always allow" confirmation) counts: it has been on screen [ARM_MS]. */
    fun armed(sheet: PromptSheetState, key: String, confirming: Boolean, now: Long): Boolean {
        val shown = sheet.shown ?: return false
        return shown.key == key && shown.confirming == confirming && now - shown.at >= ARM_MS
    }

    /**
     * A socket of [generation] is up: answers sent as a bare response on an older socket may never have reached
     * Hermes, so they are answerable again. The new snapshot then says whether Hermes still waits for them (T12 MINOR-1).
     */
    fun onReconnect(sheet: PromptSheetState, generation: Long): PromptSheetState {
        val lost = sheet.unconfirmed.filterValues { it < generation }.keys
        if (lost.isEmpty()) return sheet
        return sheet.copy(answered = sheet.answered - lost, unconfirmed = sheet.unconfirmed - lost)
    }

    /** [loaded]: the chat has its first snapshot; before that a raised sheet waits for the prompts to arrive. */
    fun onChat(sheet: PromptSheetState, chat: ChatState, loaded: Boolean = true): PromptSheetState {
        if (!loaded) return sheet
        val keys = chat.prompts.mapTo(LinkedHashSet()) { it.key }
        var next = sheet
        val settled = chat.lastSettled
        if (settled != null && settled.serial != sheet.settledSerial) {
            next = next.copy(settledSerial = settled.serial)
            val key = settled.prompt.key
            // Settled without an answer from here while the sheet showed it: "Answered elsewhere".
            if (key !in sheet.answered && sheet.open && key == sheet.showing && key !in keys) next = next.copy(withdrawn = settled.prompt)
            if (next.confirmAlways == key) next = next.copy(confirmAlways = null)
        }
        val answered = sheet.answered.filterTo(HashSet()) { it in keys || it == sheet.sending }
        val waiting = keys.filter { it !in answered }
        val fresh = waiting.any { it !in sheet.seen }
        return next.copy(
            open = (next.open || fresh) && waiting.isNotEmpty(),
            seen = keys,
            answered = answered,
            unconfirmed = sheet.unconfirmed.filterKeys { it in answered },
            showing = waiting.firstOrNull(),
        )
    }
}
