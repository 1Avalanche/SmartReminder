package smartagent.tools.rag

import smartagent.doc.ProjectKnowledgeService
import smartagent.tools.Tool

class RagSearchTool(private val service: ProjectKnowledgeService) : Tool {
    override val id = "rag_search"
    override val description = "Semantic search over indexed documentation"

    override fun execute(args: Map<String, Any>): String {
        val query = args["query"] as? String ?: return "Missing 'query' argument"
        val topK = (args["top_k"] as? Int) ?: 5
        return service.getContext(query, topK).ragContext
    }
}
