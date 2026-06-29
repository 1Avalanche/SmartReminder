package smartagent

data class Chunk(
    val id: String,
    val content: String,
    val documentId: String,
    val index: Int,
    val metadata: ChunkMetadata
)
