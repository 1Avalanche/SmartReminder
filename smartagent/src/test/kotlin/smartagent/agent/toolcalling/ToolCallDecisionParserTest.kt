package smartagent.agent.toolcalling

import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ToolCallDecisionParserTest {

    @Test
    fun `parse TOOL_CALL with valid args`() {
        val input = """
            TOOL_CALL
            tool=list_repositories
            arguments={"query": "kotlin mcp"}
        """.trimIndent()
        val decision = parseDecision(input)
        assertIs<ToolCallDecision.CallTool>(decision)
        assertEquals("list_repositories", decision.toolName)
        assertEquals("kotlin mcp", decision.arguments["query"]?.jsonPrimitive?.content)
    }

    @Test
    fun `parse TOOL_CALL with multiple args`() {
        val input = """
            TOOL_CALL
            tool=get_repository
            arguments={"owner": "octocat", "repo": "Hello-World"}
        """.trimIndent()
        val decision = parseDecision(input)
        assertIs<ToolCallDecision.CallTool>(decision)
        assertEquals("get_repository", decision.toolName)
        assertEquals("octocat", decision.arguments["owner"]?.jsonPrimitive?.content)
        assertEquals("Hello-World", decision.arguments["repo"]?.jsonPrimitive?.content)
    }

    @Test
    fun `parse TOOL_CALL with empty args defaults to empty object`() {
        val input = """
            TOOL_CALL
            tool=list_repositories
        """.trimIndent()
        val decision = parseDecision(input)
        assertIs<ToolCallDecision.CallTool>(decision)
        assertTrue(decision.arguments.isEmpty())
    }

    @Test
    fun `parse FINAL_ANSWER`() {
        val input = """
            FINAL_ANSWER
            Here are the repositories you asked about.
        """.trimIndent()
        val decision = parseDecision(input)
        assertIs<ToolCallDecision.FinalAnswer>(decision)
        assertEquals("Here are the repositories you asked about.", decision.text)
    }

    @Test
    fun `parse FINAL_ANSWER preserves multi-line text`() {
        val input = """
            FINAL_ANSWER
            Line one.
            Line two.
            Line three.
        """.trimIndent()
        val decision = parseDecision(input)
        assertIs<ToolCallDecision.FinalAnswer>(decision)
        assertTrue(decision.text.contains("Line one."))
        assertTrue(decision.text.contains("Line two."))
        assertTrue(decision.text.contains("Line three."))
    }

    @Test
    fun `no keyword returns ParseError`() {
        val input = "I'll just say something without a keyword."
        val decision = parseDecision(input)
        assertIs<ToolCallDecision.ParseError>(decision)
    }

    @Test
    fun `TOOL_CALL with invalid JSON args returns CallTool with empty args`() {
        val input = """
            TOOL_CALL
            tool=some_tool
            arguments=NOT_VALID_JSON
        """.trimIndent()
        val decision = parseDecision(input)
        assertIs<ToolCallDecision.CallTool>(decision)
        assertTrue(decision.arguments.isEmpty())
    }

    @Test
    fun `TOOL_CALL takes precedence when both keywords appear`() {
        val input = """
            TOOL_CALL
            tool=search
            arguments={}
            FINAL_ANSWER
            ignored
        """.trimIndent()
        val decision = parseDecision(input)
        assertIs<ToolCallDecision.CallTool>(decision)
    }

    @Test
    fun `FINAL_ANSWER with preamble text before keyword`() {
        val input = "Let me answer that. FINAL_ANSWER\nThe result is 42."
        val decision = parseDecision(input)
        assertIs<ToolCallDecision.FinalAnswer>(decision)
        assertTrue(decision.text.contains("42"))
    }
}
