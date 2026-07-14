package smartagent.tools.github

import smartagent.mcp_handler.McpSession
import smartagent.mcp_handler.renderToolResult
import smartagent.tools.Tool

class GitHubGetFileContentsTool(private val session: McpSession) : Tool {
    override val id = "github_get_file_contents"
    override val description = "Get contents of a file or directory listing from a GitHub repository"

    override fun execute(args: Map<String, Any>): String {
        val result = session.callTool("get_file_contents", args.toJsonArgs()) ?: return ""
        return renderToolResult(result)
    }
}
