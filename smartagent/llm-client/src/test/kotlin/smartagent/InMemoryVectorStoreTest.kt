package smartagent

import org.junit.Before
import org.junit.Test
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InMemoryVectorStoreTest {

    private lateinit var store: InMemoryVectorStore

    @Before
    fun setup() {
        store = InMemoryVectorStore()
    }

    @Test
    fun `search returns topK results without threshold`() {
        addChunk(0f, 1f, 0f)
        addChunk(1f, 0f, 0f)
        addChunk(0f, 0f, 1f)

        val results = store.search(floatArrayOf(1f, 0f, 0f), topK = 2)

        assertEquals(2, results.size)
        assertTrue(results[0].score >= results[1].score)
    }

    @Test
    fun `search with threshold filters low-scoring results`() {
        addChunk(1f, 0f, 0f)
        addChunk(0f, 1f, 0f)
        addChunk(0f, 0f, 1f)

        val results = store.search(floatArrayOf(1f, 0f, 0f), topK = 3, threshold = 0.5)

        assertEquals(1, results.size)
        assertEquals(1f, results[0].score)
    }

    @Test
    fun `search with threshold returns empty when all below threshold`() {
        addChunk(0f, 1f, 0f)
        addChunk(0f, 0f, 1f)

        val results = store.search(floatArrayOf(1f, 0f, 0f), topK = 3, threshold = 0.9)

        assertTrue(results.isEmpty())
    }

    @Test
    fun `search with threshold zero behaves same as without`() {
        repeat(5) { addChunk(1f, 0f, 0f) }

        val without = store.search(floatArrayOf(1f, 0f, 0f), topK = 3)
        val with = store.search(floatArrayOf(1f, 0f, 0f), topK = 3, threshold = 0.0)

        assertEquals(without.size, with.size)
    }

    @Test
    fun `search with threshold negative behaves same as without`() {
        repeat(5) { addChunk(0f, 1f, 0f) }

        val without = store.search(floatArrayOf(1f, 0f, 0f), topK = 3)
        val withNegative = store.search(floatArrayOf(1f, 0f, 0f), topK = 3, threshold = -0.5)

        assertEquals(without.size, withNegative.size)
    }

    @Test
    fun `search results stay sorted after threshold filter`() {
        addChunk(0.9f, 0.1f, 0f)
        addChunk(0.8f, 0.2f, 0f)
        addChunk(0.3f, 0.7f, 0f)
        addChunk(0.2f, 0.8f, 0f)

        val results = store.search(floatArrayOf(1f, 0f, 0f), topK = 4, threshold = 0.3)

        for (i in 0 until results.size - 1) {
            assertTrue(results[i].score >= results[i + 1].score, "Results must be sorted descending")
        }
    }

    @Test
    fun `search returns fewer results than topK when threshold filters some`() {
        addChunk(1f, 0f, 0f)
        addChunk(0.5f, 0.5f, 0f)
        addChunk(0f, 1f, 0f)

        val results = store.search(floatArrayOf(1f, 0f, 0f), topK = 3, threshold = 0.5)

        assertEquals(2, results.size)
    }

    @Test
    fun `search with very high threshold returns empty`() {
        addChunk(1f, 0f, 0f)

        val results = store.search(floatArrayOf(1f, 0f, 0f), topK = 5, threshold = 1.5)

        assertTrue(results.isEmpty())
    }

    @Test
    fun `search on empty store returns empty list`() {
        val results = store.search(floatArrayOf(1f, 0f, 0f), topK = 5)

        assertTrue(results.isEmpty())
    }

    @Test
    fun `search topK limits results correctly`() {
        repeat(10) { addChunk(1f, 0f, 0f) }

        val results = store.search(floatArrayOf(1f, 0f, 0f), topK = 3)

        assertEquals(3, results.size)
    }

    @Test
    fun `search returns correct search result data`() {
        val chunk = Chunk(
            id = "c1",
            content = "test content",
            documentId = "doc1",
            chunkIndex = 0,
            metadata = ChunkMetadata("Test Doc", "test.md", "md")
        )
        store.add(floatArrayOf(1f, 0f, 0f), chunk)

        val results = store.search(floatArrayOf(1f, 0f, 0f), topK = 1)

        assertEquals(1, results.size)
        assertEquals("c1", results[0].chunk.id)
        assertEquals("test content", results[0].chunk.content)
        assertEquals(1f, results[0].score)
    }

    @Test
    fun `search computes cosine similarity correctly`() {
        addChunk(1f, 0f, 0f)
        addChunk(0f, 1f, 0f)
        addChunk(1f, 1f, 0f)

        val results = store.search(floatArrayOf(1f, 0f, 0f), topK = 3)

        val expectedScore = (1.0 / (sqrt(2.0) * sqrt(1.0))).toFloat()
        assertEquals(1f, results[0].score)
        assertEquals(expectedScore, results[1].score)
        assertEquals(0f, results[2].score)
    }

    @Test
    fun `search with threshold keeps only qualifying scores`() {
        addChunk(1f, 0f, 0f)
        addChunk(0.6f, 0.4f, 0f)
        addChunk(0.4f, 0.6f, 0f)

        val results = store.search(floatArrayOf(1f, 0f, 0f), topK = 3, threshold = 0.5)

        assertTrue(results.all { it.score >= 0.5f })
    }

    private fun addChunk(vararg vector: Float) {
        store.add(
            vector,
            Chunk(
                id = "c${store.size()}",
                content = "content ${store.size()}",
                documentId = "doc",
                chunkIndex = store.size(),
                metadata = ChunkMetadata("Doc", "doc.md", "md")
            )
        )
    }
}
