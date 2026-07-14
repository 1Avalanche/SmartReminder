package smartagent.tools.github

import smartagent.mcp_handler.McpSession
import smartagent.mcp_handler.renderToolResult
import smartagent.tools.Tool

class GitHubSearchCodeTool(private val session: McpSession) : Tool {
    override val id = "github_search_code"
    override val description = "Search code in a GitHub repository"

    override fun execute(args: Map<String, Any>): String {
        val result = session.callTool("search_code", args.toJsonArgs()) ?: return ""
        return renderToolResult(result)
    }
}
