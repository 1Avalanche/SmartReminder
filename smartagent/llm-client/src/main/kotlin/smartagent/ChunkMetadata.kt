package smartagent

data class ChunkMetadata(
    val documentTitle: String,
    val documentSource: String,
    val extension: String?,
    val sectionPath: List<String> = emptyList(),
    val chunkIndex: Int = 0
)
