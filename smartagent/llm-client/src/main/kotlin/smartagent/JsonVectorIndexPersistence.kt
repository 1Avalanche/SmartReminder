package smartagent

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File

class JsonVectorIndexPersistence : VectorIndexPersistence {

    private val json = Json { prettyPrint = true }

    override fun save(entries: List<Pair<FloatArray, Chunk>>, filePath: String) {
        val dtos = entries.map { (embedding, chunk) ->
            IndexEntryDto(
                embedding = embedding.toList(),
                chunk = chunk.toDto()
            )
        }
        File(filePath).writeText(json.encodeToString(dtos))
    }

    override fun load(filePath: String): VectorStore {
        val dtos = json.decodeFromString<List<IndexEntryDto>>(File(filePath).readText())
        val store = InMemoryVectorStore()
        dtos.forEach { dto ->
            store.add(dto.embedding.toFloatArray(), dto.chunk.toDomain())
        }
        return store
    }

    private fun Chunk.toDto() = ChunkDto(
        id = id,
        content = content,
        documentId = documentId,
        index = index,
        metadata = ChunkMetadataDto(
            documentTitle = metadata.documentTitle,
            documentSource = metadata.documentSource,
            extension = metadata.extension,
            sectionPath = metadata.sectionPath
        )
    )

    private fun ChunkDto.toDomain() = Chunk(
        id = id,
        content = content,
        documentId = documentId,
        index = index,
        metadata = ChunkMetadata(
            documentTitle = metadata.documentTitle,
            documentSource = metadata.documentSource,
            extension = metadata.extension,
            sectionPath = metadata.sectionPath
        )
    )

    @Serializable
    private data class IndexEntryDto(
        val embedding: List<Float>,
        val chunk: ChunkDto
    )

    @Serializable
    private data class ChunkDto(
        val id: String,
        val content: String,
        val documentId: String,
        val index: Int,
        val metadata: ChunkMetadataDto
    )

    @Serializable
    private data class ChunkMetadataDto(
        val documentTitle: String,
        val documentSource: String,
        val extension: String?,
        val sectionPath: List<String>
    )
}
