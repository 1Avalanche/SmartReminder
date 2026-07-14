package smartagent.tools.github

import smartagent.mcp_handler.McpSession
import smartagent.mcp_handler.renderToolResult
import smartagent.tools.Tool

class GitHubListBranchesTool(private val session: McpSession) : Tool {
    override val id = "github_list_branches"
    override val description = "List branches in a GitHub repository"

    override fun execute(args: Map<String, Any>): String {
        val result = session.callTool("list_branches", args.toJsonArgs()) ?: return ""
        return renderToolResult(result)
    }
}
