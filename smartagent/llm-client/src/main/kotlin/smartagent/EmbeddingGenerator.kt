package smartagent

data class EmbeddingResult(val vector: FloatArray, val promptTokens: Int = 0)

interface EmbeddingGenerator {
    val dimension: Int
    fun embed(text: String): EmbeddingResult
    fun embedBatch(texts: List<String>): List<EmbeddingResult> = texts.map { embed(it) }
}
