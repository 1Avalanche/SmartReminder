package smartagent.doc

import smartagent.tools.index.IndexMetadata

class ProjectKnowledgeService(
    private val indexBuilder: IndexBuilder,
    private val ragSearcher: RagSearcher,
    private val metadataStorage: MetadataStorage,
    private val sourceFactory: (owner: String, repo: String, branch: String, paths: List<String>) -> DocumentSource
) : KnowledgeService {
    override fun init(owner: String, repo: String, branch: String, paths: List<String>) {
        val source = sourceFactory(owner, repo, branch, paths)
        indexBuilder.build(source)
        ragSearcher.invalidateCache()
    }

    override fun reindex() {
        val meta = metadataStorage.load()
        if (meta == null) {
            println("[DocIndex] No metadata. Run /init first.")
            return
        }
        val source = sourceFactory(meta.owner, meta.repo, meta.currentBranch, meta.docPaths)
        indexBuilder.build(source)
        ragSearcher.invalidateCache()
    }

    override fun getContext(query: String, topK: Int): ProjectContext {
        if (isStale()) {
            println("[DocIndex] Index is stale, reindexing...")
            reindex()
        }
        val results = ragSearcher.search(query, topK)
        val ragContext = if (results.isEmpty()) "" else {
            results.joinToString("\n\n---\n\n") { r ->
                "[${r.chunk.metadata.documentTitle}]\n${r.chunk.content}"
            }
        }
        val gitContext = metadataStorage.load()?.let {
            DocGitContext(branch = it.currentBranch, fileList = it.fileList)
        }
        return ProjectContext(ragContext = ragContext, gitContext = gitContext)
    }

    override fun isStale(ttlHours: Int): Boolean =
        metadataStorage.load()?.isStale(ttlHours) ?: false

    override fun isInitialized(): Boolean = ragSearcher.isReady()

    override fun getStats(): IndexMetadata? = metadataStorage.load()

    override fun clear() {
        indexBuilder.clearStorage()
        metadataStorage.clear()
        ragSearcher.invalidateCache()
    }
}
