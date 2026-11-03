package io.github.justcallmesu.aristotle.data.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Why a listed command cannot run from the phone (shown on its row, it is not sent). */
enum class SlashBlock {
    /** The Desktop does it in its own window or on its own machine (theme, pet, file save, local browser). */
    DesktopApp,

    /** The phone has its own control for it (Stop / Steer / Queue, new session, profile, model and reasoning). */
    PhoneControl,

    /** The Desktop runs it with its own calls, not `slash.exec`, and the phone does not have them yet. */
    NotOnPhone,
}

/** One row of the slash menu: [name] with its slash, Hermes' [description], [skill] for skill commands. */
data class SlashCommand(
    val name: String,
    val description: String,
    val skill: Boolean = false,
    val block: SlashBlock? = null,
    /** Only these first arguments may run (`desktop_subcommands`); null = any. */
    val subcommands: List<String>? = null,
)

/**
 * The `commands.catalog` answer (`tui_gateway/command_discovery.py:206-250`): `pairs` = every `[command, description]`
 * (registry commands, quick and plugin commands, skills), `commands` = the Desktop metadata per registry and plugin
 * command (`desktop`: null offered, `"hidden"`, or why the Desktop has no surface for it; `:137-149`,
 * `hermes_cli/commands.py:367-372`), `canon` = lowercase name or alias -> canonical name, `skills` = the skill commands.
 */
data class SlashCatalog(val commands: List<SlashCommand>, val canon: Map<String, String> = emptyMap()) {
    /** [typed] (a name or alias, any case) as its catalog row, or null for a command Hermes does not list. */
    fun resolve(typed: String): SlashCommand? {
        val key = typed.trim().lowercase().let { if (it.startsWith("/")) it else "/$it" }
        val name = canon[key] ?: PhoneSlash.ALIASES[key] ?: key
        return commands.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    companion object {
        /**
         * The rows the Desktop's `/` menu offers (`apps/desktop/src/lib/desktop-slash-commands.ts:949-997`, gate `:800-820`):
         * aliases are not in `pairs`; `hidden` (e.g. `/model`, which has its own picker) and every command the Desktop
         * has no surface for (`terminal`, `advanced`, `settings`, …) are left out; so is a command whose
         * `desktop_subcommands` is empty. Then the phone's own blocks ([PhoneSlash]) mark what cannot run here.
         */
        fun of(result: JsonElement?): SlashCatalog {
            val answer = result.asObject() ?: return SlashCatalog(emptyList())
            val meta = (answer["commands"] as? JsonObject).orEmpty()
            val skills = (answer["skills"] as? JsonObject).orEmpty().keys
            val canon = (answer["canon"] as? JsonObject).orEmpty().mapNotNull { (alias, name) ->
                (name as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.let { alias.lowercase() to it }
            }.toMap()
            val seen = HashSet<String>()
            val rows = (answer["pairs"] as? JsonArray).orEmpty().mapNotNull { pair ->
                val (name, description) = pairOf(pair) ?: return@mapNotNull null
                if (!seen.add(name.lowercase())) return@mapNotNull null
                val entry = meta[name].asObject()
                val desktop = entry?.text("desktop")
                val subcommands = (entry?.get("desktop_subcommands") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
                if (desktop != null || subcommands?.isEmpty() == true) return@mapNotNull null
                SlashCommand(name, description, skill = name in skills, block = PhoneSlash.block(name), subcommands = subcommands)
            }
            return SlashCatalog(rows, canon)
        }

        private fun pairOf(element: JsonElement): Pair<String, String>? {
            val pair = element as? JsonArray ?: return null
            val name = (pair.getOrNull(0) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.startsWith("/") && it.length > 1 } ?: return null
            val description = (pair.getOrNull(1) as? JsonPrimitive)?.contentOrNull.orEmpty()
            return name to description
        }
    }
}

/**
 * The commands the Desktop does not send to Hermes as `slash.exec` but handles in its own client
 * (`apps/desktop/src/lib/desktop-slash-commands.ts:202-391`, `DESKTOP_COMMAND_SPECS`), sorted by what the phone does
 * with them. Every other listed command (including new registry commands, quick and plugin commands and skills) goes
 * to Hermes, which refuses by name what a shared-gateway session cannot run (`gateway/session_commands.py:118-145`,
 * `:456-458`): a command added later fails safe with Hermes' own message.
 */
object PhoneSlash {
    private val DESKTOP_APP = setOf(
        "/skin", "/pet", "/hatch", "/journey", "/palette", "/wake", "/timestamps", "/browser", "/save", "/export", "/import", "/handoff",
    )
    private val PHONE_CONTROL = setOf("/new", "/resume", "/profile", "/stop", "/steer", "/queue", "/model", "/reasoning")
    private val NOT_ON_PHONE = setOf("/compress", "/undo", "/retry", "/branch", "/btw", "/bg", "/background", "/agents", "/config", "/history", "/rollback")

    /** The Desktop's aliases for the commands above (`desktop-slash-commands.ts:204-366`), for a typed alias Hermes' `canon` does not map. */
    val ALIASES = mapOf(
        "/reset" to "/new", "/fork" to "/branch", "/compact" to "/compress", "/s" to "/steer", "/q" to "/queue",
        "/tasks" to "/agents", "/ts" to "/timestamps", "/sessions" to "/resume", "/switch" to "/resume",
        "/learning" to "/journey", "/memory-graph" to "/journey", "/generate-pet" to "/hatch",
    )

    fun block(name: String): SlashBlock? {
        val key = name.lowercase().let { ALIASES[it] ?: it }
        return when (key) {
            in DESKTOP_APP -> SlashBlock.DesktopApp
            in PHONE_CONTROL -> SlashBlock.PhoneControl
            in NOT_ON_PHONE -> SlashBlock.NotOnPhone
            else -> null
        }
    }
}

/** A typed command split into its name and argument. */
data class TypedCommand(val name: String, val arg: String)

/** The composer's `/` menu: when it shows, what it lists, what picking inserts. Pure. */
object SlashMenu {
    /** The draft is a command (it starts with `/`, as the Desktop reads it): Send runs it instead of sending a message. */
    fun isCommand(draft: String): Boolean = typed(draft) != null

    fun typed(draft: String): TypedCommand? {
        val text = draft.trim()
        if (!text.startsWith("/") || text.length < 2 || text[1].isWhitespace()) return null
        val parts = text.split(Regex("\\s+"), limit = 2)
        return TypedCommand(parts[0], parts.getOrElse(1) { "" }.trim())
    }

    /** The command token while it is being typed (`/mo`, no space yet), without its slash; null hides the menu. */
    fun query(draft: String): String? {
        if (!draft.startsWith("/") || draft.any { it.isWhitespace() }) return null
        return draft.substring(1)
    }

    /**
     * The rows for [query]: names starting with it, then names containing it (any case); runnable commands, then
     * skills, then what the phone cannot run.
     */
    fun filter(catalog: SlashCatalog, query: String): List<SlashCommand> {
        val q = query.lowercase()
        val matches = catalog.commands.filter { q.isEmpty() || it.name.lowercase().contains(q) }
        return matches.sortedWith(
            compareBy<SlashCommand>({ it.block != null }, { it.skill }, { !it.name.lowercase().startsWith("/$q") }),
        )
    }

    /** What picking a row puts in the field: the command and a space for its argument. Blocked rows insert nothing. */
    fun insert(command: SlashCommand): String? = if (command.block == null) "${command.name} " else null

    /**
     * The Desktop's subcommand gate (`apps/desktop/src/lib/desktop-slash-commands.ts:629-653`): a command narrowed to
     * some first arguments (`/skills pending`) runs only with one of them. The allowed list when [arg] is not one.
     */
    fun subcommandBlocked(command: SlashCommand, arg: String): List<String>? {
        val allowed = command.subcommands ?: return null
        val first = arg.trim().split(Regex("\\s+")).firstOrNull()?.lowercase().orEmpty()
        return allowed.takeIf { first.isEmpty() || allowed.none { it.equals(first, ignoreCase = true) } }
    }
}

/** What `slash.exec` answered (`gateway/session_commands.py:418-529`). */
sealed interface SlashAnswer {
    /** Text to show (`type: exec`); empty when the command printed nothing. */
    data class Output(val text: String) : SlashAnswer

    /**
     * A turn to submit (`type: skill` or `send`): the client sends [message] with its own submission id, never the
     * gateway (`gateway/session_commands.py:1-5`, `:523-528`); [display] is what the user's bubble shows
     * (`apps/desktop/src/app/session/hooks/use-prompt-actions/slash.ts:470-517`).
     */
    data class Submit(val message: String, val display: String, val notice: String?) : SlashAnswer

    companion object {
        fun of(result: JsonElement?, typed: String): SlashAnswer {
            val answer = result.asObject() ?: return Output("")
            val message = answer.text("message")?.trim().orEmpty()
            if ((answer.text("type") == "skill" || answer.text("type") == "send") && message.isNotEmpty()) {
                return Submit(message, answer.text("display")?.takeIf { it.isNotBlank() } ?: typed, answer.text("notice")?.trim()?.ifEmpty { null })
            }
            val body = answer.text("output").orEmpty()
            val warning = answer.text("warning")?.takeIf { it.isNotBlank() }
            return Output(if (warning != null) "warning: $warning\n$body" else body)
        }
    }
}
