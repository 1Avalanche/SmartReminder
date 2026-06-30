package smartagent

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DefaultTextNormalizerTest {

    private val normalizer = DefaultTextNormalizer()

    @Test
    fun `keeps normal text intact`() {
        assertEquals("Hello, World!", normalizer.normalize("Hello, World!"))
    }

    @Test
    fun `removes null bytes`() {
        assertEquals("Hi", normalizer.normalize("Hi\u0000"))
    }

    @Test
    fun `removes null bytes in the middle`() {
        assertEquals("HelloWorld", normalizer.normalize("Hello\u0000World"))
    }

    @Test
    fun `removes multiple null bytes`() {
        assertEquals("ab", normalizer.normalize("a\u0000b\u0000"))
    }

    @Test
    fun `keeps newline`() {
        assertEquals("line1\nline2", normalizer.normalize("line1\nline2"))
    }

    @Test
    fun `keeps tab`() {
        assertEquals("col1\tcol2", normalizer.normalize("col1\tcol2"))
    }

    @Test
    fun `keeps carriage return`() {
        assertEquals("line1\r\nline2", normalizer.normalize("line1\r\nline2"))
    }

    @Test
    fun `removes bell character 0x07`() {
        assertEquals("hello", normalizer.normalize("hello\u0007"))
    }

    @Test
    fun `removes backspace 0x08`() {
        assertEquals("hello", normalizer.normalize("hello\u0008"))
    }

    @Test
    fun `removes escape character 0x1B`() {
        assertEquals("hello", normalizer.normalize("hello\u001B"))
    }

    @Test
    fun `removes unit separator 0x1F`() {
        assertEquals("hello", normalizer.normalize("hello\u001F"))
    }

    @Test
    fun `removes high surrogate D800`() {
        assertEquals("hello", normalizer.normalize("hello\uD800"))
    }

    @Test
    fun `removes low surrogate DFFF`() {
        assertEquals("hello", normalizer.normalize("hello\uDFFF"))
    }

    @Test
    fun `removes mid surrogate DB80`() {
        assertEquals("ab", normalizer.normalize("a\uDB80b"))
    }

    @Test
    fun `removes multiple individual surrogates`() {
        assertEquals("xyz", normalizer.normalize("\uD800x\uDC00y\uDFFFz\uDBFF"))
    }

    @Test
    fun `removes mix of null bytes and control chars but keeps newlines`() {
        val input = "hel\u0000lo\u0007\nwor\u001Bld\u0000"
        assertEquals("hello\nworld", normalizer.normalize(input))
    }

    @Test
    fun `empty string returns empty`() {
        assertEquals("", normalizer.normalize(""))
    }

    @Test
    fun `only control chars returns empty`() {
        assertEquals("", normalizer.normalize("\u0000\u0001\u0002\u0003\u001B"))
    }

    @Test
    fun `removes vertical tab 0x0B`() {
        assertEquals("ab", normalizer.normalize("a\u000Bb"))
    }

    @Test
    fun `removes form feed 0x0C`() {
        assertEquals("ab", normalizer.normalize("a\u000Cb"))
    }

    @Test
    fun `handles long clean text without overhead`() {
        val longText = "The quick brown fox jumps over the lazy dog.\n".repeat(100)
        assertEquals(longText, normalizer.normalize(longText))
    }

    @Test
    fun `normalize is idempotent`() {
        val dirty = "hel\u0000lo\u0007\n\u001Bworld\uD800"
        val once = normalizer.normalize(dirty)
        val twice = normalizer.normalize(once)
        assertEquals(once, twice)
        assertNotEquals(dirty, once)
    }
}
