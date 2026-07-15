package smartagent

import smartagent.doc.ProjectKnowledgeService
import smartagent.mcp_handler.McpManager
import smartagent.tools.ToolRegistry
import smartagent.tools.github.GitHubCreatePRCommentTool
import smartagent.tools.github.GitHubGetDiffTool
import smartagent.tools.github.GitHubGetFileContentsTool
import smartagent.tools.github.GitHubGetPRFilesTool
import smartagent.tools.github.GitHubGetPRTool
import smartagent.tools.github.GitHubListBranchesTool
import smartagent.tools.github.GitHubListCommitsTool
import smartagent.tools.github.GitHubSearchCodeTool

class DocInitCommandHandler(
    private val service: ProjectKnowledgeService,
    private val registry: ToolRegistry
) {
    fun handle(args: List<String>) {
        val ownerRepo = args.firstOrNull() ?: run {
            println("${Colors.LIGHT_YELLOW}Usage: /init <owner>/<repo> [--branch <branch>] [path1] [path2...]${Colors.RESET}")
            return
        }
        val parts = ownerRepo.split("/")
        if (parts.size != 2) {
            println("${Colors.LIGHT_YELLOW}Format must be <owner>/<repo>${Colors.RESET}")
            return
        }
        val (owner, repo) = parts

        val branchIdx = args.indexOf("--branch")
        val branch = if (branchIdx >= 0) args.getOrElse(branchIdx + 1) { "main" } else "main"

        val paths = args.drop(1)
            .filter { it != "--branch" && it != branch }
            .ifEmpty { listOf(".") }

        val session = McpManager.getSession("github") ?: run {
            println("${Colors.LIGHT_YELLOW}GitHub MCP not connected. Add GITHUB_PERSONAL_ACCESS_TOKEN to local.properties and restart.${Colors.RESET}")
            return
        }

        registry.register(GitHubGetFileContentsTool(session))
        registry.register(GitHubSearchCodeTool(session))
        registry.register(GitHubListCommitsTool(session))
        registry.register(GitHubGetDiffTool(session))
        registry.register(GitHubListBranchesTool(session))
        registry.register(GitHubGetPRTool(session))
        registry.register(GitHubGetPRFilesTool(session))
        registry.register(GitHubCreatePRCommentTool(session))

        println("${Colors.LIGHT_GREEN}GitHub MCP connected.${Colors.RESET}")

        service.init(owner, repo, branch, paths)
    }
}
