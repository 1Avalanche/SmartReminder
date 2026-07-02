package smartagent

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IndexerTest {

    private fun fakeDoc(id: String = "doc") = Document(
        id = id, title = "$id.md", content = "content of $id",
        metadata = DocumentMetadata(source = "$id.md", extension = "md")
    )

    private fun fakeChunk(id: String, documentId: String) = Chunk(
        id = id, content = "chunk", documentId = documentId, chunkIndex = 0,
        metadata = ChunkMetadata(documentTitle = "doc.md", documentSource = "doc.md", extension = "md")
    )

    @Test
    fun `index returns chunks from chunker`() {
        val chunk = fakeChunk("d1_0", "d1")
        val indexer = Indexer(
            loader = object : DocumentLoader { override fun load() = listOf(fakeDoc("d1")) },
            chunker = object : Chunker { override fun chunk(docs: List<Document>) = listOf(chunk) }
        )
        assertEquals(listOf(chunk), indexer.index())
    }

    @Test
    fun `index passes loader output to chunker`() {
        val doc = fakeDoc("captured")
        var received: List<Document> = emptyList()
        val indexer = Indexer(
            loader = object : DocumentLoader { override fun load() = listOf(doc) },
            chunker = object : Chunker {
                override fun chunk(docs: List<Document>): List<Chunk> {
                    received = docs
                    return emptyList()
                }
            }
        )
        indexer.index()
        assertEquals(listOf(doc), received)
    }

    @Test
    fun `index returns empty when loader returns nothing`() {
        val indexer = Indexer(
            loader = object : DocumentLoader { override fun load(): List<Document> = emptyList() },
            chunker = FixedChunker(100)
        )
        assertTrue(indexer.index().isEmpty())
    }

    @Test
    fun `indexer does not depend on concrete loader implementation`() {
        var loadCalled = false
        val indexer = Indexer(
            loader = object : DocumentLoader {
                override fun load(): List<Document> {
                    loadCalled = true
                    return emptyList<Document>()
                }
            },
            chunker = FixedChunker(100)
        )
        indexer.index()
        assertTrue(loadCalled)
    }
}
