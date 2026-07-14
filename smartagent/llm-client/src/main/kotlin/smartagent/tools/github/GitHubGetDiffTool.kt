package smartagent.tools.github

import smartagent.mcp_handler.McpSession
import smartagent.mcp_handler.renderToolResult
import smartagent.tools.Tool

class GitHubGetDiffTool(private val session: McpSession) : Tool {
    override val id = "github_get_diff"
    override val description = "Get diff for a GitHub pull request"

    override fun execute(args: Map<String, Any>): String {
        val result = session.callTool("get_pull_request_diff", args.toJsonArgs()) ?: return ""
        return renderToolResult(result)
    }
}
