package smartagent

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FixedChunkerTest {

    private fun doc(content: String, id: String = "doc") = Document(
        id = id,
        title = "$id.kt",
        content = content,
        metadata = DocumentMetadata(source = "src/$id.kt", extension = "kt")
    )

    @Test
    fun `splits content into equal chunks`() {
        val chunks = FixedChunker(4).chunk(listOf(doc("abcdefgh")))
        assertEquals(2, chunks.size)
        assertEquals("abcd", chunks[0].content)
        assertEquals("efgh", chunks[1].content)
    }

    @Test
    fun `last chunk can be smaller than chunkSize`() {
        val chunks = FixedChunker(4).chunk(listOf(doc("abcde")))
        assertEquals(2, chunks.size)
        assertEquals("e", chunks[1].content)
    }

    @Test
    fun `content smaller than chunkSize produces single chunk`() {
        val chunks = FixedChunker(100).chunk(listOf(doc("hello")))
        assertEquals(1, chunks.size)
        assertEquals("hello", chunks[0].content)
    }

    @Test
    fun `empty content produces no chunks`() {
        assertTrue(FixedChunker(10).chunk(listOf(doc(""))).isEmpty())
    }

    @Test
    fun `chunk id follows documentId_index pattern`() {
        val chunks = FixedChunker(4).chunk(listOf(doc("abcdefgh", id = "myfile")))
        assertEquals("myfile_0", chunks[0].id)
        assertEquals("myfile_1", chunks[1].id)
    }

    @Test
    fun `chunk index is sequential`() {
        val chunks = FixedChunker(3).chunk(listOf(doc("abcdefghi")))
        assertEquals(0, chunks[0].index)
        assertEquals(1, chunks[1].index)
        assertEquals(2, chunks[2].index)
    }

    @Test
    fun `all chunks reference correct documentId`() {
        val chunks = FixedChunker(3).chunk(listOf(doc("abcdefghi", id = "file1")))
        assertTrue(chunks.all { it.documentId == "file1" })
    }

    @Test
    fun `multiple documents produce independent chunks`() {
        val chunks = FixedChunker(5).chunk(listOf(doc("aaaaa", "doc1"), doc("bbbbb", "doc2")))
        assertEquals(2, chunks.size)
        assertEquals("doc1", chunks[0].documentId)
        assertEquals("doc2", chunks[1].documentId)
    }

    @Test
    fun `metadata carries document title and source`() {
        val chunks = FixedChunker(100).chunk(listOf(doc("content", "myfile")))
        assertEquals("myfile.kt", chunks[0].metadata.documentTitle)
        assertEquals("src/myfile.kt", chunks[0].metadata.documentSource)
        assertEquals("kt", chunks[0].metadata.extension)
    }

    @Test
    fun `sectionPath is empty for all chunks`() {
        val chunks = FixedChunker(5).chunk(listOf(doc("hello world")))
        assertTrue(chunks.all { it.metadata.sectionPath.isEmpty() })
    }
}
