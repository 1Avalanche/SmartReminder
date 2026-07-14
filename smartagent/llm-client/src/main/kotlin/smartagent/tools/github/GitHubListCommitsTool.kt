package smartagent.tools.github

import smartagent.mcp_handler.McpSession
import smartagent.mcp_handler.renderToolResult
import smartagent.tools.Tool

class GitHubListCommitsTool(private val session: McpSession) : Tool {
    override val id = "github_list_commits"
    override val description = "List commits in a GitHub repository branch"

    override fun execute(args: Map<String, Any>): String {
        val result = session.callTool("list_commits", args.toJsonArgs()) ?: return ""
        return renderToolResult(result)
    }
}
