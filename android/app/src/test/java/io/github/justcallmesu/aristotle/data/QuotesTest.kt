package io.github.justcallmesu.aristotle.data

import java.io.File
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuotesTest {
    // Unit tests run in android/app; the one source of the quotes is docs/quotes.json (copied into the APK at build time).
    private val realFile = File("../../docs/quotes.json")
    private val real by lazy { QuoteParser.parse(realFile.readText()) }

    @Test
    fun theRealAssetParsesIntoTwentyFourCompleteQuotes() {
        assertEquals(24, real.size)
        assertEquals(24, real.map { it.id }.toSet().size)
        real.forEach {
            assertTrue("en ${it.id}", it.en.isNotBlank())
            assertTrue("el ${it.id}", it.el.isNotBlank())
            assertTrue("source ${it.id}", it.source.isNotBlank())
            assertTrue("translator ${it.id}", it.translator.isNotBlank())
        }
    }

    @Test
    fun aBrokenAssetIsEmptyNotACrash() {
        assertTrue(QuoteParser.parse("not json").isEmpty())
        assertTrue(QuoteParser.parse("""[{"id":1}]""").isEmpty())
        assertTrue(QuoteParser.parse("").isEmpty())
    }

    @Test
    fun theSpokenTextHasTheEnglishAndTheCitationButNotTheGreek() {
        val q = real.first()
        assertTrue(q.spoken.contains(q.en))
        assertTrue(q.spoken.contains(q.source))
        assertTrue(q.spoken.contains(q.translator))
        assertFalse(q.spoken.contains(q.el))
    }

    @Test
    fun theNextQuoteIsNeverTheSameTwiceInARow() {
        val random = Random(7)
        var last: Int? = null
        repeat(2000) {
            val next = QuotePicker.pick(real, last, random)!!
            assertTrue(next.id != last)
            last = next.id
        }
    }

    @Test
    fun everyQuoteIsReachable() {
        val random = Random(1)
        val seen = mutableSetOf<Int>()
        var last: Int? = null
        repeat(5000) {
            val next = QuotePicker.pick(real, last, random)!!
            seen += next.id
            last = next.id
        }
        assertEquals(real.map { it.id }.toSet(), seen)
    }

    @Test
    fun oneQuoteRepeatsAndNoQuoteIsNull() {
        val only = real.take(1)
        assertEquals(only.single(), QuotePicker.pick(only, only.single().id))
        assertNull(QuotePicker.pick(emptyList(), null))
    }

    @Test
    fun theSourceRemembersTheLastIdAndSurvivesAMissingAsset() {
        val store = MemoryStore()
        val settings = AppSettings(store)
        val source = QuoteSource({ realFile.inputStream() }, settings)
        val first = source.next(Random(3))!!
        assertEquals(first.id, AppSettings(store).lastQuoteId)
        assertTrue(source.next(Random(3))!!.id != first.id)
        val broken = QuoteSource({ throw java.io.FileNotFoundException() }, AppSettings(MemoryStore()))
        assertNull(broken.next())
        assertNotNull(source.load())
    }
}
