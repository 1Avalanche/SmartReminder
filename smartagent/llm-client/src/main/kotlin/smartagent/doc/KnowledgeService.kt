package smartagent.doc

import smartagent.tools.index.IndexMetadata

interface KnowledgeService {
    fun getContext(query: String, topK: Int = 5): ProjectContext
    fun init(owner: String, repo: String, branch: String, paths: List<String>)
    fun reindex()
    fun isStale(ttlHours: Int = 12): Boolean
    fun isInitialized(): Boolean
    fun getStats(): IndexMetadata?
    fun clear()
}
