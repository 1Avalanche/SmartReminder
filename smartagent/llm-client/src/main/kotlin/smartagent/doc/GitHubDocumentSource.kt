package smartagent.doc

import smartagent.Document
import smartagent.DocumentMetadata
import smartagent.tools.ToolRegistry

class GitHubDocumentSource(
    private val registry: ToolRegistry,
    override val owner: String,
    override val repo: String,
    override val branch: String,
    override val docPaths: List<String>
) : DocumentSource {

    override fun loadDocuments(): List<Document> {
        println("[DocIndex] Fetching docs from $owner/$repo@$branch ...")
        val walker = DocumentWalker(owner, repo, branch, ::fetchRaw)
        val documents = walker.walk(docPaths).toMutableList()

        val commitsRaw = fetchCommitsRaw()
        if (commitsRaw.isNotBlank()) {
            documents.add(
                Document(
                    id = "commits:$owner/$repo@$branch",
                    title = "Recent commits on $branch",
                    content = commitsRaw,
                    metadata = DocumentMetadata(null, "github:$owner/$repo@$branch/commits")
                )
            )
        }

        return documents
    }

    fun fetchRaw(path: String): String? =
        runCatching {
            val result = registry.get("github_get_file_contents").execute(
                mapOf("owner" to owner, "repo" to repo, "path" to path, "branch" to branch)
            )
            result.takeIf { it.isNotBlank() }
        }.getOrElse { e ->
            println("[DocIndex] Failed to fetch $path: ${e.message}")
            null
        }

    fun fetchCommitsRaw(): String =
        runCatching {
            registry.get("github_list_commits").execute(
                mapOf("owner" to owner, "repo" to repo, "sha" to branch, "perPage" to 20)
            )
        }.getOrElse { "" }
}
