package io.github.justcallmesu.aristotle.ui.chat

import io.github.justcallmesu.aristotle.data.chat.ModelGroup

/** One provider as the model sheet shows it: the [shown] models (all of them, or only the search hits) and whether it is open. */
data class ProviderView(val group: ModelGroup, val shown: List<String>, val expanded: Boolean, val filtered: Boolean) {
    /** All of the provider's models, whatever the search shows ("3 of 13"). */
    val total: Int get() = group.models.size
}

/** Pure model-sheet logic: local search over the catalog's groups, which providers are open, and where the current row sits. */
object ModelFilter {
    /** The query as it is matched: trimmed; blank means no search. */
    fun normalize(query: String): String = query.trim()

    /** Case-insensitive substring range of [query] in [name], null when it does not occur (or the query is blank). */
    fun matchRange(name: String, query: String): IntRange? {
        val q = normalize(query)
        if (q.isEmpty()) return null
        val at = name.indexOf(q, ignoreCase = true)
        return if (at < 0) null else at until at + q.length
    }

    /** Models in all providers; the search field's "Search N models". */
    fun modelCount(groups: List<ModelGroup>): Int = groups.sumOf { it.models.size }

    /**
     * The providers to list, in Hermes' order. Without a query: all of them, each open when the user opened it
     * ([opened] holds slugs the user toggled away from the default) — the session's [provider] is open by default.
     * With a query: only providers with a hit, each open and showing just its hits.
     */
    fun views(groups: List<ModelGroup>, query: String, provider: String?, toggled: Set<String>): List<ProviderView> {
        val q = normalize(query)
        if (q.isEmpty()) {
            return groups.map { g ->
                val open = g.matches(provider) != (g.slug in toggled)
                ProviderView(g, g.models, expanded = open, filtered = false)
            }
        }
        return groups.mapNotNull { g ->
            val hits = g.models.filter { it.contains(q, ignoreCase = true) }
            if (hits.isEmpty()) null else ProviderView(g, hits, expanded = true, filtered = true)
        }
    }

    /** The LazyColumn index of the current model's row among [views] (a header item per provider), null when it is not shown. */
    fun currentRow(views: List<ProviderView>, provider: String?, model: String?): Int? {
        if (model == null) return null
        var index = 0
        for (v in views) {
            if (v.expanded && v.group.matches(provider)) {
                val at = v.shown.indexOf(model)
                if (at >= 0) return index + 1 + at
            }
            index += 1 + if (v.expanded) v.shown.size else 0
        }
        return null
    }

    /**
     * The first item index to scroll to so [target] shows with the least movement: null when it is already visible
     * ([first]..[last], [visibleCount] items), [target] itself when it is above, the index that puts it last when below.
     */
    fun revealIndex(first: Int, last: Int, visibleCount: Int, target: Int): Int? = when {
        target in first..last -> null
        target < first -> target
        else -> (target - visibleCount + 1).coerceAtLeast(0)
    }
}
