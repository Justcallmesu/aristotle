package io.github.justcallmesu.aristotle.data

import java.io.InputStream
import kotlin.random.Random
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One approved loading-screen quote (spec Q45, Q47), read as it stands in `docs/quotes.json`. */
@Serializable
data class Quote(
    val id: Int,
    val en: String,
    val el: String,
    /** Work, book/chapter and Bekker number: "Nicomachean Ethics I.1, 1094a". */
    val source: String,
    /** Translator and year: "W. D. Ross, 1908". */
    val translator: String,
) {
    /** What TalkBack reads: the English and the citation, never the Greek. */
    val spoken: String get() = "$en. $source, $translator."
}

/** Parses the `quotes.json` asset. A broken file is an empty list, never a crash (the screen then shows only the progress line). */
object QuoteParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): List<Quote> =
        try {
            json.decodeFromString<List<Quote>>(text).filter { it.en.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
}

object QuotePicker {
    /** A random quote that is not [lastId] (when there is any other); null for an empty list. */
    fun pick(quotes: List<Quote>, lastId: Int?, random: Random = Random.Default): Quote? {
        if (quotes.isEmpty()) return null
        val others = quotes.filter { it.id != lastId }
        return (others.ifEmpty { quotes }).random(random)
    }

    /** A random quote whose id is in none of [avoid] (all of them when that leaves nothing); null for an empty list. */
    fun pickAvoiding(quotes: List<Quote>, avoid: Set<Int>, random: Random = Random.Default): Quote? {
        if (quotes.isEmpty()) return null
        return quotes.filter { it.id !in avoid }.ifEmpty { quotes }.random(random)
    }
}

/**
 * The quotes of this process: the asset is read and parsed once, off the main thread ([load] is called from a
 * background dispatcher), and the last quote shown is remembered so the next start differs.
 */
class QuoteSource(private val open: () -> InputStream, private val settings: AppSettings) {
    private val quotes: List<Quote> by lazy {
        try {
            QuoteParser.parse(open().use { it.readBytes().decodeToString() })
        } catch (e: Exception) {
            emptyList()
        }
    }

    private var launchId: Int? = null
    private var inlineId: Int? = null

    /** Reads and parses the asset now (blocking; call off the main thread). */
    fun load(): List<Quote> = quotes

    /** The next quote to show, remembered as the last one; null when the asset is unusable. Blocking on first use. */
    fun next(random: Random = Random.Default): Quote? {
        val quote = QuotePicker.pick(quotes, settings.lastQuoteId, random) ?: return null
        settings.lastQuoteId = quote.id
        synchronized(this) { if (launchId == null) launchId = quote.id }
        return quote
    }

    /**
     * A quote for a chat that is loading (Q59): never the one shown at launch in this process, and never the one
     * the previous chat load showed. Does not touch the remembered launch quote. Blocking on first use.
     */
    @Synchronized
    fun nextInline(random: Random = Random.Default): Quote? {
        val quote = QuotePicker.pickAvoiding(quotes, setOfNotNull(launchId, inlineId), random) ?: return null
        inlineId = quote.id
        return quote
    }
}
