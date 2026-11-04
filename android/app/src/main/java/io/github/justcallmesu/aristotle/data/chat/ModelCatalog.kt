package io.github.justcallmesu.aristotle.data.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * What a model supports, from the catalog's `capabilities[model]` (`hermes_cli/inventory.py::_apply_capabilities`,
 * `apps/shared/src/gateway-contract.generated.ts:857-862`). [canDisableReasoning] null = the catalog does not say.
 */
data class ModelCaps(val reasoning: Boolean, val canDisableReasoning: Boolean?)

/** One provider of the picker with the models it offers, in Hermes' own (curated) order. */
data class ModelGroup(
    val slug: String,
    val name: String,
    val aliases: List<String>,
    val models: List<String>,
    /** Listed but locked for this account (a paid tier): shown, not selectable (`model-picker.tsx:356-380`). */
    val unavailable: Set<String>,
    val capabilities: Map<String, ModelCaps>,
    val warning: String?,
) {
    /** The Desktop's `catalogProviderMatches`: slug, display name or a custom-provider alias (`apps/desktop/src/lib/model-options.ts:10-20`). */
    fun matches(provider: String?): Boolean =
        !provider.isNullOrEmpty() && (slug == provider || name == provider || provider in aliases)
}

/**
 * The `model.options` answer (`ModelOptionsResult`, `apps/shared/src/gateway-contract.generated.ts:822-856`): the
 * providers grouped as the Desktop's picker groups them, and the session's current [model] / [provider].
 */
data class ModelCatalog(
    val groups: List<ModelGroup>,
    val model: String?,
    val provider: String?,
    /** The Claude plan windows of the same answer (TY6). */
    val plan: PlanUsage = PlanUsage.NONE,
) {
    /** The capabilities of [model] under [provider], or null while unknown (`apps/desktop/src/lib/model-options.ts:31-40`). */
    fun capabilities(provider: String?, model: String?): ModelCaps? =
        groups.firstOrNull { it.matches(provider) }?.capabilities?.get(model ?: return null)

    companion object {
        /** The managed local-model provider, shown by the Desktop only when launched with `--local` (`model-picker.tsx:311-318`, `:489-491`). */
        const val LOCAL_PROVIDER = "llamacpp"

        /**
         * Only providers with models are selectable here; adding a provider happens on the PC (the Desktop's picker
         * does the same, `apps/desktop/src/components/model-picker.tsx:308-318`). Groups keep Hermes' order.
         */
        fun of(result: JsonElement?): ModelCatalog {
            val answer = result.asObject() ?: return ModelCatalog(emptyList(), null, null)
            val groups = (answer["providers"] as? JsonArray).orEmpty().mapNotNull { it.asObject()?.let(::group) }
                .filter { it.models.isNotEmpty() && it.slug != LOCAL_PROVIDER }
                .distinctBy { it.slug }
            return ModelCatalog(groups, answer.text("model")?.ifBlank { null }, answer.text("provider")?.ifBlank { null }, PlanUsage.of(result))
        }

        private fun group(row: JsonObject): ModelGroup? {
            val slug = row.text("slug")?.takeIf { it.isNotBlank() } ?: return null
            return ModelGroup(
                slug = slug,
                name = row.text("name")?.takeIf { it.isNotBlank() } ?: slug,
                aliases = strings(row["aliases"]),
                models = strings(row["models"]).distinct(),
                unavailable = strings(row["unavailable_models"]).toSet(),
                capabilities = (row["capabilities"] as? JsonObject).orEmpty().mapNotNull { (model, caps) ->
                    caps.asObject()?.let { model to ModelCaps(it.flag("reasoning") ?: true, it.flag("can_disable_reasoning")) }
                }.toMap(),
                warning = row.text("warning")?.takeIf { it.isNotBlank() },
            )
        }

        private fun strings(element: JsonElement?): List<String> = (element as? JsonArray).orEmpty().mapNotNull { item ->
            (item as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
        }
    }
}

/**
 * Hermes' reasoning levels and when the phone offers them, as the Desktop's reasoning menu does
 * (`apps/desktop/src/app/shell/reasoning-menu-panel.tsx:24-58`, `model-edit-submenu.tsx:131-134`, `:204-216`). Pure.
 */
object ReasoningLevels {
    /** `VALID_REASONING_EFFORTS` (`hermes_constants.py:1073`), mirrored by the Desktop (`apps/shared/src/reasoning-effort.ts:4`). */
    val LEVELS = listOf("minimal", "low", "medium", "high", "xhigh", "max", "ultra")

    /** Thinking off: not a level, the disabled state (`hermes_constants.py:1076-1100`). */
    const val OFF = "none"

    /**
     * The choices for a model with [caps]: none when the catalog says it has no reasoning; the Desktop assumes
     * reasoning while the catalog does not say (`caps?.reasoning ?? true`). "Off" only when the model can turn thinking
     * off (`canDisableReasoning !== false`).
     */
    fun choices(caps: ModelCaps?): List<String> {
        if (caps?.reasoning == false) return emptyList()
        return if (caps?.canDisableReasoning == false) LEVELS else listOf(OFF) + LEVELS
    }

    /**
     * The level the session runs, from `session.info.reasoning_effort` (`gateway/session_local.py:427-444`); empty =
     * not set (the profile's default applies).
     */
    fun current(effort: String?): String? = effort?.trim()?.lowercase()?.takeIf { it == OFF || it in LEVELS }

    /**
     * The level the route actually sends when it differs (`ultra` sends `max` on most routes), from
     * `reasoning_effort_wire`; null when the same or unknown (`apps/desktop/src/lib/reasoning-effort.ts:19-38`).
     */
    fun clampedTo(effort: String?, wire: String?): String? {
        val picked = current(effort) ?: return null
        val sent = wire?.trim()?.lowercase()?.takeIf { it in LEVELS } ?: return null
        return sent.takeIf { picked in LEVELS && it != picked }
    }
}
