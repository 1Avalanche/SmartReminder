package smartagent.tools.github

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GitHubGetFileContentsToolTest {

    @Test
    fun `id and description are set correctly`() {
        val tool = GitHubGetFileContentsTool(FakeMcpSession().asSession())
        assertEquals("github_get_file_contents", tool.id)
        assertTrue(tool.description.isNotBlank())
    }

    @Test
    fun `execute returns rendered text when session returns MCP text content`() {
        val mcpResponse = buildJsonObject {
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", JsonPrimitive("text"))
                    put("text", JsonPrimitive("# Hello World"))
                })
            })
            put("isError", JsonPrimitive("false"))
        }
        val fake = FakeMcpSession(toolResult = mcpResponse)
        val tool = GitHubGetFileContentsTool(fake.asSession())

        val result = tool.execute(mapOf("owner" to "test", "repo" to "repo", "path" to "README.md"))

        assertEquals("# Hello World", result)
    }

    @Test
    fun `execute returns empty string when session returns null`() {
        val fake = FakeMcpSession(toolResult = null)
        val tool = GitHubGetFileContentsTool(fake.asSession())

        val result = tool.execute(mapOf("owner" to "test", "repo" to "repo", "path" to "README.md"))

        assertEquals("", result)
    }

    @Test
    fun `args are forwarded to session callTool`() {
        val fake = FakeMcpSession(toolResult = null)
        val tool = GitHubGetFileContentsTool(fake.asSession())
        val args = mapOf("owner" to "myorg", "repo" to "myrepo", "path" to "docs/guide.md", "branch" to "main")

        tool.execute(args)

        assertEquals("get_file_contents", fake.lastCalledTool)
        assertEquals(JsonPrimitive("myorg"), fake.lastCalledArgs["owner"])
    }
}
