package smartagent

interface EmbeddingGenerator {
    val dimension: Int
    fun embed(text: String): FloatArray
    fun embedBatch(texts: List<String>): List<FloatArray> = texts.map { embed(it) }
}
