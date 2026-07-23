package smartagent.investigator

import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.test.assertEquals

class TerminalInputTest {

    private fun stream(vararg bytes: Int) =
        ByteArrayInputStream(bytes.map { it.toByte() }.toByteArray())

    @Test
    fun `ascii char`() {
        assertEquals('A'.code, readUtf8CodePoint(stream(0x41)))
    }

    @Test
    fun `eof returns -1`() {
        assertEquals(-1, readUtf8CodePoint(stream()))
    }

    @Test
    fun `esc byte`() {
        assertEquals(0x1b, readUtf8CodePoint(stream(0x1b)))
    }

    @Test
    fun `cyrillic А U+0410`() {
        // А = 0xD0 0x90
        assertEquals(0x0410, readUtf8CodePoint(stream(0xD0, 0x90)))
    }

    @Test
    fun `cyrillic п U+043F`() {
        // п = 0xD0 0xBF
        assertEquals(0x043F, readUtf8CodePoint(stream(0xD0, 0xBF)))
    }

    @Test
    fun `cyrillic я U+044F`() {
        // я = 0xD1 0x8F
        assertEquals(0x044F, readUtf8CodePoint(stream(0xD1, 0x8F)))
    }

    @Test
    fun `euro sign U+20AC three-byte`() {
        // € = 0xE2 0x82 0xAC
        assertEquals(0x20AC, readUtf8CodePoint(stream(0xE2, 0x82, 0xAC)))
    }

    @Test
    fun `sequential cyrillic chars`() {
        // "Да" = Д(0xD0 0x94) + а(0xD0 0xB0)
        val s = stream(0xD0, 0x94, 0xD0, 0xB0)
        assertEquals(0x0414, readUtf8CodePoint(s))  // Д
        assertEquals(0x0430, readUtf8CodePoint(s))  // а
        assertEquals(-1, readUtf8CodePoint(s))       // EOF
    }

    @Test
    fun `ascii followed by cyrillic`() {
        // "aА" = 0x61 + 0xD0 0x90
        val s = stream(0x61, 0xD0, 0x90)
        assertEquals('a'.code, readUtf8CodePoint(s))
        assertEquals(0x0410, readUtf8CodePoint(s))
    }

    @Test
    fun `backspace 0x7f is returned as-is`() {
        assertEquals(0x7f, readUtf8CodePoint(stream(0x7f)))
    }

    @Test
    fun `enter 0x0d is returned as-is`() {
        assertEquals(0x0d, readUtf8CodePoint(stream(0x0d)))
    }

    @Test
    fun `cyrillic word привет decoded char by char`() {
        // п р и в е т in UTF-8
        val bytes = "привет".toByteArray(Charsets.UTF_8).map { it.toInt() and 0xFF }.toIntArray()
        val s = stream(*bytes)
        val result = StringBuilder()
        repeat(6) {
            val cp = readUtf8CodePoint(s)
            result.append(String(Character.toChars(cp)))
        }
        assertEquals("привет", result.toString())
    }
}
