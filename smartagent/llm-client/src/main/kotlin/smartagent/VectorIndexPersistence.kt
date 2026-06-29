package smartagent

interface VectorIndexPersistence {
    fun save(entries: List<Pair<FloatArray, Chunk>>, filePath: String)
    fun load(filePath: String): VectorStore
}
