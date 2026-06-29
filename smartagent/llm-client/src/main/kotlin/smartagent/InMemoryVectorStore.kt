package smartagent

import kotlin.math.sqrt

class InMemoryVectorStore : VectorStore {

    private data class Entry(val embedding: FloatArray, val chunk: Chunk)

    private val entries = mutableListOf<Entry>()

    override fun add(embedding: FloatArray, chunk: Chunk) {
        entries.add(Entry(embedding, chunk))
    }

    override fun search(queryEmbedding: FloatArray, topK: Int): List<SearchResult> =
        entries
            .map { SearchResult(it.chunk, cosineSimilarity(queryEmbedding, it.embedding)) }
            .sortedByDescending { it.score }
            .take(topK)

    override fun size(): Int = entries.size

    fun entries(): List<Pair<FloatArray, Chunk>> = entries.map { it.embedding to it.chunk }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0
        var magA = 0.0
        var magB = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            magA += a[i] * a[i]
            magB += b[i] * b[i]
        }
        val denom = sqrt(magA) * sqrt(magB)
        return if (denom == 0.0) 0f else (dot / denom).toFloat()
    }
}
