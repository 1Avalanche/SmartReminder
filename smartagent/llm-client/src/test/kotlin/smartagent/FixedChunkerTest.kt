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
        assertEquals(0, chunks[0].chunkIndex)
        assertEquals(1, chunks[1].chunkIndex)
        assertEquals(2, chunks[2].chunkIndex)
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
    fun `metadata chunkIndex reflects chunk position`() {
        val chunks = FixedChunker(chunkSize = 4, overlapSize = 0).chunk(listOf(doc("abcdefgh")))
        assertEquals(0, chunks[0].metadata.chunkIndex)
        assertEquals(1, chunks[1].metadata.chunkIndex)
    }

    @Test
    fun `sectionPath is empty for all chunks`() {
        val chunks = FixedChunker(5).chunk(listOf(doc("hello world")))
        assertTrue(chunks.all { it.metadata.sectionPath.isEmpty() })
    }

    @Test
    fun `overlap produces sliding window chunks`() {
        val chunks = FixedChunker(chunkSize = 5, overlapSize = 2).chunk(listOf(doc("abcdefghij")))
        assertEquals(3, chunks.size)
        assertEquals("abcde", chunks[0].content)
        assertEquals("defgh", chunks[1].content)
        assertEquals("ghij", chunks[2].content)
    }

    @Test
    fun `overlap continues past first chunk`() {
        val chunks = FixedChunker(chunkSize = 5, overlapSize = 2).chunk(listOf(doc("abcdefghijkl")))
        assertEquals(4, chunks.size)
        assertEquals("abcde", chunks[0].content)
        assertEquals("defgh", chunks[1].content)
        assertEquals("ghijk", chunks[2].content)
        assertEquals("jkl", chunks[3].content)
    }

    @Test
    fun `default overlap is 10 percent of chunkSize`() {
        val chunks = FixedChunker(10).chunk(listOf(doc("abcdefghijklmnopqrst")))
        assertEquals(3, chunks.size)
        assertEquals("abcdefghij", chunks[0].content)
        assertEquals("jklmnopqrs", chunks[1].content)
        assertEquals("st", chunks[2].content)
    }

    @Test
    fun `zero overlap behaves like chunked`() {
        val chunks = FixedChunker(chunkSize = 4, overlapSize = 0).chunk(listOf(doc("abcdefgh")))
        assertEquals(2, chunks.size)
        assertEquals("abcd", chunks[0].content)
        assertEquals("efgh", chunks[1].content)
    }

    // --- fix 2: normalization before splitting ---

    @Test
    fun `normalization applied once before splitting preserves correct overlap`() {
        // Normalizer replaces CRLF with LF, shortening the text
        // If normalization happened per-chunk, overlap positions would be wrong
        val crlfNormalizer = object : TextNormalizer {
            override fun normalize(text: String) = text.replace("\r\n", "\n")
        }
        // Content: 10 CRLF-terminated lines, each "ab\r\n" = 4 chars → normalized "ab\n" = 3 chars
        val content = "ab\r\n".repeat(5)  // 20 chars raw, 15 chars normalized
        val chunker = FixedChunker(chunkSize = 9, overlapSize = 3, minChunkSize = 1, normalizer = crlfNormalizer)
        val chunks = chunker.chunk(listOf(doc(content)))
        // All chunk content should be from normalized text (no \r chars)
        assertTrue(chunks.all { '\r' !in it.content }, "No CR chars expected after normalization")
    }

    // --- fix 4: word boundary splitting ---

    @Test
    fun `chunk does not split in the middle of a word`() {
        // Without fix: chunkSize=8 rawEnd=8 lands mid-"world" → chunks "hello wo" / "rld foo"
        // With word-boundary + overlapSize=2: splits at space → "hello" / "world" / "foo"
        val chunker = FixedChunker(chunkSize = 8, overlapSize = 2, minChunkSize = 1)
        val chunks = chunker.chunk(listOf(doc("hello world foo")))
        assertTrue(chunks.any { "world" in it.content }, "word 'world' must not be split across chunks")
        assertTrue(chunks.any { "hello" in it.content })
        assertTrue(chunks.any { "foo" in it.content })
    }

    @Test
    fun `falls back to raw end when no word boundary found in second half`() {
        // No spaces → wordBoundaryEnd returns rawEnd → same behavior as before
        val chunker = FixedChunker(chunkSize = 4, overlapSize = 0, minChunkSize = 1)
        val chunks = chunker.chunk(listOf(doc("abcdefgh")))
        assertEquals(2, chunks.size)
        assertEquals("abcd", chunks[0].content)
        assertEquals("efgh", chunks[1].content)
    }

    // --- fix 5: tiny last chunk merged ---

    @Test
    fun `tiny last chunk is merged into previous`() {
        // "abcdefg" = 7 chars, chunkSize=5, advance=5
        // chunk 1: [0,5)="abcde", chunk 2: [5,7)="fg" (2 chars < minChunkSize=3) → merge
        val chunker = FixedChunker(chunkSize = 5, overlapSize = 0, minChunkSize = 3)
        val chunks = chunker.chunk(listOf(doc("abcdefg")))
        assertEquals(1, chunks.size)
        assertEquals("abcdefg", chunks[0].content)
    }

    @Test
    fun `last chunk above minChunkSize is not merged`() {
        // "abcdefgh" = 8 chars, chunkSize=5, advance=5
        // chunk 1: [0,5)="abcde", chunk 2: [5,8)="fgh" (3 chars >= minChunkSize=3) → no merge
        val chunker = FixedChunker(chunkSize = 5, overlapSize = 0, minChunkSize = 3)
        val chunks = chunker.chunk(listOf(doc("abcdefgh")))
        assertEquals(2, chunks.size)
        assertEquals("fgh", chunks[1].content)
    }
}
