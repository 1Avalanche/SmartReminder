package smartagent.tools.github

import smartagent.mcp_handler.McpSession
import smartagent.mcp_handler.renderToolResult
import smartagent.tools.Tool

class GitHubGetPRTool(private val session: McpSession) : Tool {
    override val id = "github_get_pr"
    override val description = "Get metadata for a GitHub pull request"

    override fun execute(args: Map<String, Any>): String {
        val result = session.callTool("get_pull_request", args.toJsonArgs()) ?: return ""
        return renderToolResult(result)
    }
}
