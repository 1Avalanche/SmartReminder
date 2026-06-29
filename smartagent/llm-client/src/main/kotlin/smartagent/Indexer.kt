package smartagent

class Indexer(
    private val loader: DocumentLoader,
    private val chunker: Chunker
) {
    fun index(): List<Chunk> = chunker.chunk(loader.load())
}
