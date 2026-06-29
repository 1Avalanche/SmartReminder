package smartagent

interface Chunker {
    fun chunk(documents: List<Document>): List<Chunk>
}
