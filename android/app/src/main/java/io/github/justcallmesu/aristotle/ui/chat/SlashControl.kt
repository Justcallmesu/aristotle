package io.github.justcallmesu.aristotle.ui.chat

import io.github.justcallmesu.aristotle.data.RelayLink
import io.github.justcallmesu.aristotle.data.RpcException
import io.github.justcallmesu.aristotle.data.chat.ControlFrames
import io.github.justcallmesu.aristotle.data.chat.SlashAnswer
import io.github.justcallmesu.aristotle.data.chat.SlashBlock
import io.github.justcallmesu.aristotle.data.chat.SlashCatalog
import io.github.justcallmesu.aristotle.data.chat.SlashCommand
import io.github.justcallmesu.aristotle.data.chat.SlashMenu
import io.github.justcallmesu.aristotle.data.chat.TypedCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** Where a command the user ran stands; shown under the transcript on this phone only. */
sealed interface CommandStatus {
    data object Running : CommandStatus

    /** Hermes' text; empty = the command printed nothing. */
    data class Output(val text: String) : CommandStatus

    /** Not sent: the phone cannot run it ([SlashBlock]). */
    data class Blocked(val block: SlashBlock) : CommandStatus

    /** Not sent: only these first arguments run from here (`desktop_subcommands`). */
    data class Subcommands(val allowed: List<String>) : CommandStatus

    /** Hermes refused it ([reason] its words), or no socket took it ([sent] false). */
    data class Failed(val reason: String, val sent: Boolean = true) : CommandStatus
}

data class CommandRun(val id: String, val command: String, val status: CommandStatus)

/** The `/` menu's list and the commands run from this chat (T15). */
data class SlashState(
    val catalog: SlashCatalog? = null,
    val loading: Boolean = false,
    val problem: String? = null,
    val runs: List<CommandRun> = emptyList(),
)

/**
 * Slash commands for one session, on the [ChatViewModel]'s state. The list is Hermes' own (`commands.catalog`, read
 * once the user types `/`); a picked row only fills the field; Send runs the command with `slash.exec`, as the
 * Desktop does for every command it does not handle in its own window. A command is a tap: nothing here runs one again.
 */
internal class ChatCommands(
    private val sessionId: String,
    private val link: RelayLink,
    private val newId: () -> String,
    private val clock: () -> Long,
    private val work: CoroutineScope,
    private val state: () -> ChatUiState,
    private val change: ((ChatUiState) -> ChatUiState) -> Unit,
    private val composer: ChatComposer,
) {
    private var checking = false
    private var reading: Deferred<SlashCatalog?>? = null
    private val submissionIds = HashMap<String, String>()

    /** The field changed: the first `/` loads the list; after a failure the next keystroke tries again (as the Desktop). */
    fun onDraft(draft: String) {
        val slash = state().slash
        if (SlashMenu.query(draft) != null && slash.catalog == null && !slash.loading) load()
    }

    fun reload() {
        load()
    }

    /** A menu row: its name goes into the field; a row the phone cannot run inserts nothing. */
    fun pick(command: SlashCommand) {
        val text = SlashMenu.insert(command) ?: return
        composer.replaceDraft(text)
    }

    fun dismiss(id: String) = update { it.copy(runs = it.runs.filter { run -> run.id != id }) }

    /**
     * Send with a command in the field. Only once the composer has shown its controls for the arming time. The command
     * is checked against the catalog first (a typed `/footer` the Desktop refuses is not sent; with no catalog yet it
     * is read first, the run row shows the wait); the draft is only taken once the command goes out.
     */
    fun run() {
        val ui = state()
        if (!ComposerLogic.armedOnline(ui.composer, clock())) return
        val typed = SlashMenu.typed(ui.composer.draft) ?: return
        if (checking) return
        val text = ui.composer.draft.trim()
        val id = newId()
        val catalog = ui.slash.catalog
        if (catalog != null) {
            dispatch(id, text, typed, catalog)
            return
        }
        // Never send unchecked: wait for the catalog (a spinner row), then decide.
        checking = true
        update { it.copy(runs = it.runs + CommandRun(id, text, CommandStatus.Running)) }
        work.launch {
            val loaded = try {
                load().await()
            } finally {
                checking = false
            }
            if (loaded == null) {
                val problem = state().slash.problem.orEmpty()
                update { slash -> slash.copy(runs = slash.runs.map { if (it.id == id) it.copy(status = CommandStatus.Failed(problem, sent = false)) else it }) }
            } else {
                dispatch(id, text, typed, loaded, listed = true)
            }
        }
    }

    private fun dispatch(id: String, text: String, typed: TypedCommand, catalog: SlashCatalog, listed: Boolean = false) {
        val entry = catalog.resolve(typed.name)
        val blocked = catalog.blockOf(typed.name)?.let { CommandStatus.Blocked(it) }
            ?: entry?.let { SlashMenu.subcommandBlocked(it, typed.arg) }?.let { CommandStatus.Subcommands(it) }
        if (blocked != null) {
            // Not sent: the draft stays so nothing the user typed is lost.
            setRun(id, text, blocked, listed)
            return
        }
        if (state().composer.draft.trim() == text) composer.replaceDraft("")
        setRun(id, text, CommandStatus.Running, listed)
        // The submission id of a skill's turn exists before the first call and is reused when the same text goes again
        // (Retry, or the restored text of a dismissed "no answer"), so a second `slash.exec` cannot start a second turn.
        val submission = state().composer.keptIds[text] ?: submissionIds.getOrPut(text, newId)
        val call = ControlFrames.slashExec(sessionId, text)
        work.launch {
            val status = try {
                when (val answer = SlashAnswer.of(link.call(call.method, call.params), text)) {
                    is SlashAnswer.Output -> CommandStatus.Output(answer.text)
                    is SlashAnswer.Submit -> {
                        submissionIds.remove(text)
                        composer.submitDirective(answer.display, answer.message, submission)
                        answer.notice?.let { CommandStatus.Output(it) }
                    }
                }
            } catch (e: RpcException) {
                val error = e.error
                val reason = error?.let { listOfNotNull(it.reason ?: it.message, it.data?.get("detail")?.let(::detailOf)).joinToString(" — ") }
                CommandStatus.Failed(reason ?: e.message.orEmpty(), sent = e.written)
            }
            update { slash ->
                val runs = if (status == null) slash.runs.filter { it.id != id } else slash.runs.map { if (it.id == id) it.copy(status = status) else it }
                slash.copy(runs = runs)
            }
        }
    }

    /** The run row with [status]; a row that waited for the catalog already exists. */
    private fun setRun(id: String, text: String, status: CommandStatus, exists: Boolean) = update { slash ->
        slash.copy(runs = if (exists) slash.runs.map { if (it.id == id) it.copy(status = status) else it } else slash.runs + CommandRun(id, text, status))
    }

    /** Hermes' hint on a refused command (`unsupported_command` + `data.detail`, `gateway/session_commands.py:118-126`, `:456-458`). */
    private fun detailOf(element: JsonElement): String? =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    /** Reads the catalog; a read already on its way is joined. Completes with the catalog, or null when it failed. */
    private fun load(): Deferred<SlashCatalog?> {
        reading?.takeIf { it.isActive }?.let { return it }
        update { it.copy(loading = true, problem = null) }
        val call = ControlFrames.commandsCatalog(sessionId)
        val read = work.async {
            val outcome = try {
                Result.success(SlashCatalog.of(link.call(call.method, call.params)))
            } catch (e: RpcException) {
                Result.failure(e)
            }
            update { slash ->
                outcome.fold(
                    onSuccess = { slash.copy(loading = false, catalog = it) },
                    onFailure = { slash.copy(loading = false, problem = (it as RpcException).error?.message ?: it.message.orEmpty()) },
                )
            }
            outcome.getOrNull()
        }
        reading = read
        return read
    }

    private fun update(transform: (SlashState) -> SlashState) = change { it.copy(slash = transform(it.slash)) }
}
