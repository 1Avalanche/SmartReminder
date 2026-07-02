package smartagent

class FixedChunker(
    private val chunkSize: Int,
    private val overlapSize: Int = (chunkSize * 0.1).toInt(),
    private val minChunkSize: Int = chunkSize / 4,
    private val normalizer: TextNormalizer = DefaultTextNormalizer()
) : Chunker {

    override fun chunk(documents: List<Document>): List<Chunk> =
        documents.flatMap { document ->
            val text = normalizer.normalize(document.content)
            val advance = chunkSize - overlapSize
            val result = mutableListOf<Chunk>()
            var start = 0
            var index = 0

            while (start < text.length) {
                val end = wordBoundaryEnd(text, start, minOf(start + chunkSize, text.length))
                result.add(
                    Chunk(
                        id = "${document.id}_$index",
                        content = text.substring(start, end),
                        documentId = document.id,
                        chunkIndex = index,
                        metadata = ChunkMetadata(
                            documentTitle = document.title,
                            documentSource = document.metadata.source,
                            extension = document.metadata.extension,
                            chunkIndex = index
                        )
                    )
                )
                if (end == text.length) break
                start += advance
                index++
            }

            if (result.size >= 2) {
                val last = result.last()
                if (last.content.length < minChunkSize) {
                    val prevIdx = result.lastIndex - 1
                    val prev = result[prevIdx]
                    result[prevIdx] = prev.copy(content = prev.content + last.content)
                    result.removeAt(result.lastIndex)
                }
            }

            result
        }

    private fun wordBoundaryEnd(text: String, start: Int, rawEnd: Int): Int {
        if (rawEnd >= text.length) return rawEnd
        val searchFloor = start + chunkSize / 2
        val lastNewline = text.lastIndexOf('\n', rawEnd - 1).takeIf { it > searchFloor } ?: -1
        val lastSpace   = text.lastIndexOf(' ',  rawEnd - 1).takeIf { it > searchFloor } ?: -1
        val boundary = maxOf(lastNewline, lastSpace)
        return if (boundary > searchFloor) boundary else rawEnd
    }
}
