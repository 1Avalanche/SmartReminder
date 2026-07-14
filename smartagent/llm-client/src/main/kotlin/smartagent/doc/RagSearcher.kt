package smartagent.doc

import smartagent.EmbeddingGenerator
import smartagent.SearchResult
import smartagent.VectorStore

class RagSearcher(
    private val embeddingGenerator: EmbeddingGenerator,
    private val indexStorage: IndexStorage
) {
    private var cachedStore: VectorStore? = null

    fun search(query: String, topK: Int = 5): List<SearchResult> {
        val store = getOrLoadStore() ?: return emptyList()
        val embedding = embeddingGenerator.embed(query).vector
        return store.search(embedding, topK)
    }

    fun isReady(): Boolean = indexStorage.exists()

    fun invalidateCache() {
        cachedStore = null
    }

    private fun getOrLoadStore(): VectorStore? {
        if (cachedStore != null) return cachedStore
        return indexStorage.load()?.also { cachedStore = it }
    }
}
