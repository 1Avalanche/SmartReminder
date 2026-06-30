package smartagent

import kotlin.math.min

class FixedChunker(
    private val chunkSize: Int,
    private val overlapSize: Int = (chunkSize * 0.1).toInt(),
    private val normalizer: TextNormalizer = DefaultTextNormalizer()
) : Chunker {

    override fun chunk(documents: List<Document>): List<Chunk> =
        documents.flatMap { document ->
            val text = document.content
            val advance = chunkSize - overlapSize
            val result = mutableListOf<Chunk>()
            var start = 0
            var index = 0

            while (start < text.length) {
                val end = minOf(start + chunkSize, text.length)
                result.add(
                    Chunk(
                        id = "${document.id}_$index",
                        content = normalizer.normalize(text.substring(start, end)),
                        documentId = document.id,
                        index = index,
                        metadata = ChunkMetadata(
                            documentTitle = document.title,
                            documentSource = document.metadata.source,
                            extension = document.metadata.extension
                        )
                    )
                )
                if (end == text.length) break
                start += advance
                index++
            }
            result
        }
}
