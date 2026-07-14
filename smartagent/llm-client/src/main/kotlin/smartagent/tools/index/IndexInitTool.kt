package smartagent.tools.index

import smartagent.doc.ProjectKnowledgeService
import smartagent.tools.Tool

class IndexInitTool(private val service: ProjectKnowledgeService) : Tool {
    override val id = "index_init"
    override val description = "Initialize or refresh documentation index from a GitHub repository"

    override fun execute(args: Map<String, Any>): String {
        val owner = args["owner"] as? String ?: return "Missing 'owner' argument"
        val repo = args["repo"] as? String ?: return "Missing 'repo' argument"
        val branch = args["branch"] as? String ?: "main"
        @Suppress("UNCHECKED_CAST")
        val paths = (args["paths"] as? List<String>) ?: listOf("README.md")
        service.init(owner, repo, branch, paths)
        return "Index initialized: $owner/$repo@$branch"
    }
}
