package smartagent

class FixedChunker(
    private val chunkSize: Int,
    private val normalizer: TextNormalizer = DefaultTextNormalizer()
) : Chunker {

    override fun chunk(documents: List<Document>): List<Chunk> =
        documents.flatMap { document ->
            document.content
                .chunked(chunkSize)
                .mapIndexed { index, text ->
                    Chunk(
                        id = "${document.id}_$index",
                        content = normalizer.normalize(text),
                        documentId = document.id,
                        index = index,
                        metadata = ChunkMetadata(
                            documentTitle = document.title,
                            documentSource = document.metadata.source,
                            extension = document.metadata.extension
                        )
                    )
                }
        }
}
