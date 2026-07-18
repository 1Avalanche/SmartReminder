package smartagent.investigator

import smartagent.investigator.agents.extractJson
import smartagent.investigator.agents.stripThinkBlocks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LlmResponseParserTest {

    @Test
    fun `extractJson on plain JSON returns as-is`() {
        val json = """{"type": "data_flow"}"""
        assertEquals(json, extractJson(json))
    }

    @Test
    fun `extractJson strips think block then returns JSON`() {
        val input = "<think>reasoning</think>\n\n{\"type\": \"data_flow\"}"
        assertEquals("""{"type": "data_flow"}""", extractJson(input))
    }

    @Test
    fun `extractJson strips markdown fences`() {
        val input = "```json\n{\"status\": \"found\"}\n```"
        assertEquals("""{"status": "found"}""", extractJson(input))
    }

    @Test
    fun `extractJson finds JSON embedded after content prefix`() {
        val input = """"content": "<think>reasoning</think>\n\n{"type": "data_flow"}", trailing text"""
        val result = extractJson(input)
        assertEquals("""{"type": "data_flow"}""", result)
    }

    @Test
    fun `extractJson finds JSON with trailing text after closing brace`() {
        val input = """Some text {"status": "found"} more text"""
        assertEquals("""{"status": "found"}""", extractJson(input))
    }

    @Test
    fun `extractJson handles nested JSON objects`() {
        val input = """prefix {"status": "found", "items": [{"id": "1"}]} suffix"""
        val result = extractJson(input)
        assertTrue(result.startsWith("{"))
        assertTrue(result.endsWith("}"))
        assertTrue(result.contains("\"items\""))
    }

    @Test
    fun `extractJson returns stripped string when no JSON found`() {
        val input = "no json here"
        assertEquals("no json here", extractJson(input))
    }

    @Test
    fun `stripThinkBlocks removes think content`() {
        val input = "<think>internal reasoning</think>\nactual response"
        assertFalse(stripThinkBlocks(input).contains("<think>"))
        assertTrue(stripThinkBlocks(input).contains("actual response"))
    }

    @Test
    fun `stripThinkBlocks is case insensitive`() {
        val input = "<THINK>reasoning</THINK> result"
        assertFalse(stripThinkBlocks(input).contains("THINK"))
        assertTrue(stripThinkBlocks(input).trim().startsWith("result"))
    }
}
