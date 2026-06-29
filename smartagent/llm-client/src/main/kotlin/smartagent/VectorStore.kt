package smartagent

interface VectorStore {
    fun add(embedding: FloatArray, chunk: Chunk)
    fun search(queryEmbedding: FloatArray, topK: Int): List<SearchResult>
    fun size(): Int
}
