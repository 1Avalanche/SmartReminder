package smartagent.mcp_handler

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolResultRendererTest {

    private fun textContent(text: String, isError: Boolean = false) = buildJsonObject {
        put("content", buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", text)
            })
        })
        if (isError) put("isError", true)
    }

    // ─── basic text extraction ─────────────────────────────────────────────────

    @Test
    fun `plain text extracted directly`() {
        val result = textContent("hello world")
        assertEquals("hello world", renderToolResult(result))
    }

    @Test
    fun `text that is valid JSON object is pretty-printed`() {
        val result = textContent("""{"name":"octocat","stars":42}""")
        val rendered = renderToolResult(result)
        assertTrue(rendered.contains("\"name\""))
        assertTrue(rendered.contains("\"octocat\""))
        assertTrue(rendered.contains("\n"))   // has newlines → pretty printed
    }

    @Test
    fun `text that is valid JSON array is pretty-printed`() {
        val result = textContent("""[{"name":"repo1"},{"name":"repo2"}]""")
        val rendered = renderToolResult(result)
        assertTrue(rendered.contains("\"repo1\""))
        assertTrue(rendered.contains("\n"))
    }

    @Test
    fun `bare JSON primitive string is left as-is`() {
        val result = textContent("true")
        assertEquals("true", renderToolResult(result))
    }

    @Test
    fun `non-JSON text is left as-is`() {
        val result = textContent("Repository not found")
        assertEquals("Repository not found", renderToolResult(result))
    }

    // ─── isError ──────────────────────────────────────────────────────────────

    @Test
    fun `isError true adds error prefix`() {
        val result = textContent("unauthorized", isError = true)
        assertTrue(renderToolResult(result).startsWith("[error] "))
    }

    @Test
    fun `isError false has no prefix`() {
        val result = textContent("ok")
        assertTrue(!renderToolResult(result).startsWith("[error]"))
    }

    // ─── multiple content items ────────────────────────────────────────────────

    @Test
    fun `multiple text items joined with separator`() {
        val result = buildJsonObject {
            put("content", buildJsonArray {
                add(buildJsonObject { put("type", "text"); put("text", "part1") })
                add(buildJsonObject { put("type", "text"); put("text", "part2") })
            })
        }
        val rendered = renderToolResult(result)
        assertTrue(rendered.contains("part1"))
        assertTrue(rendered.contains("part2"))
        assertTrue(rendered.contains("---"))
    }

    // ─── edge cases ───────────────────────────────────────────────────────────

    @Test
    fun `empty content array returns empty string`() {
        val result = buildJsonObject {
            put("content", buildJsonArray {})
        }
        assertEquals("", renderToolResult(result))
    }

    @Test
    fun `direct text field extracted without JSON wrapper`() {
        val result = buildJsonObject { put("text", "Found 1211 repositories") }
        assertEquals("Found 1211 repositories", renderToolResult(result))
    }

    @Test
    fun `direct text field with newlines renders actual newlines`() {
        val result = buildJsonObject { put("text", "line1\nline2\nline3") }
        val rendered = renderToolResult(result)
        assertEquals("line1\nline2\nline3", rendered)
        assertTrue(!rendered.contains("\\n"), "must not contain literal \\n")
    }

    @Test
    fun `direct text field that is JSON is pretty-printed`() {
        val result = buildJsonObject { put("text", """[{"name":"repo1"}]""") }
        val rendered = renderToolResult(result)
        assertTrue(rendered.contains("\"repo1\""))
        assertTrue(rendered.contains("\n"))
    }

    @Test
    fun `no content field and no text field falls back to pretty JSON`() {
        val result = buildJsonObject {
            put("foo", "bar")
        }
        val rendered = renderToolResult(result)
        assertTrue(rendered.contains("\"foo\""))
        assertTrue(rendered.contains("\"bar\""))
    }

    @Test
    fun `non-text content type falls back to pretty JSON of item`() {
        val result = buildJsonObject {
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "image")
                    put("data", "base64==")
                    put("mimeType", "image/png")
                })
            })
        }
        val rendered = renderToolResult(result)
        assertTrue(rendered.contains("\"image\""))
    }
}
