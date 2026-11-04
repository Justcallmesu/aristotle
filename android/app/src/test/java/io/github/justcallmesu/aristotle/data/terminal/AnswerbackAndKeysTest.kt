package io.github.justcallmesu.aristotle.data.terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The emulator's own replies (protocol §14: no answer-back that echoes data) and typed-key bytes. */
class AnswerbackAndKeysTest {
    private fun ok(reply: String) = Answerback.allowed(reply.toByteArray(Charsets.ISO_8859_1))

    @Test
    fun numericRepliesPass() {
        listOf(
            "\u001b[?64;1;2;6;9;15;18;21;22c", // DA1
            "\u001b[>41;320;0c", // DA2
            "\u001b[0n", // DSR
            "\u001b[12;40R", // CPR
            "\u001b[?12;40;1R", // DECXCPR
            "\u001b[?2004;1\$y", // DECRQM
            "\u001b[8;24;80t", // window size
            "\u001b]11;rgb:1717/1313/0f0f\u001b\\", // OSC 11 colour query
            "\u001b]4;1;rgb:a3a3/2020/1919\u0007",
        ).forEach { assertTrue(it, ok(it)) }
    }

    @Test
    fun repliesThatCarryTextAreDropped() {
        listOf(
            "\u001bP1\$r64;1\"p\u001b\\", // DECRQSS
            "\u001bP1+r544e=787465726d\u001b\\", // XTGETTCAP
            "\u001bP0+r7a7a\u001b\\",
            "\u001b]LIconLabel\u001b\\",
            "\u001b]l\u001b\\",
            "rm -rf ~\r",
            "\u001b[1;2Hecho hi\r",
            "",
        ).forEach { assertFalse(it, ok(it)) }
        assertFalse(Answerback.allowed(("\u001b[" + "1;".repeat(40) + "c").toByteArray()))
    }

    @Test
    fun aReplyIsJudgedOnlyOnItsOwnBytes() {
        val buffer = "xx\u001b[0nyy".toByteArray()
        assertTrue(Answerback.allowed(buffer, 2, 4))
        assertFalse(Answerback.allowed(buffer, 0, buffer.size))
    }

    @Test
    fun ctrlMapsLettersAndPunctuationLikeATerminal() {
        assertArrayEquals(byteArrayOf(3), KeyBytes.of('c'.code, ctrlOn = true, altOn = false))
        assertArrayEquals(byteArrayOf(3), KeyBytes.of('C'.code, ctrlOn = true, altOn = false))
        assertArrayEquals(byteArrayOf(0), KeyBytes.of(' '.code, ctrlOn = true, altOn = false))
        assertArrayEquals(byteArrayOf(27), KeyBytes.of('['.code, ctrlOn = true, altOn = false))
        assertArrayEquals(byteArrayOf(31), KeyBytes.of('/'.code, ctrlOn = true, altOn = false))
        assertArrayEquals(byteArrayOf(30), KeyBytes.of('~'.code, ctrlOn = true, altOn = false))
        assertArrayEquals("|".toByteArray(), KeyBytes.of('|'.code, ctrlOn = true, altOn = false))
    }

    @Test
    fun altPrefixesEscapeAndTextIsUtf8() {
        assertArrayEquals(byteArrayOf(27, 'b'.code.toByte()), KeyBytes.of('b'.code, ctrlOn = false, altOn = true))
        assertArrayEquals(byteArrayOf(27, 3), KeyBytes.of('c'.code, ctrlOn = true, altOn = true))
        assertArrayEquals("é".toByteArray(), KeyBytes.of('é'.code, ctrlOn = false, altOn = false))
        assertArrayEquals("😀".toByteArray(), KeyBytes.of(0x1F600, ctrlOn = false, altOn = false))
        assertArrayEquals(ByteArray(0), KeyBytes.of(0xD800, ctrlOn = false, altOn = false))
        assertArrayEquals("ls\r-l\r".toByteArray(), KeyBytes.text("ls\n-l\r\n"))
    }
}
