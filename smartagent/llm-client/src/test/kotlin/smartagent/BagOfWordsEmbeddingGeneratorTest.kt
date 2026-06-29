package smartagent

import org.junit.Test
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BagOfWordsEmbeddingGeneratorTest {

    private val gen = BagOfWordsEmbeddingGenerator()

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        val dot = a.indices.sumOf { (a[it] * b[it]).toDouble() }
        val magA = sqrt(a.sumOf { (it * it).toDouble() })
        val magB = sqrt(b.sumOf { (it * it).toDouble() })
        return if (magA == 0.0 || magB == 0.0) 0f else (dot / (magA * magB)).toFloat()
    }

    @Test
    fun `output size equals dimension`() {
        assertEquals(gen.dimension, gen.embed("hello world").size)
    }

    @Test
    fun `same text produces identical embedding`() {
        val text = "Kotlin is a great language"
        val a = gen.embed(text)
        val b = gen.embed(text)
        assertTrue(a.contentEquals(b))
    }

    @Test
    fun `embedding is L2-normalized`() {
        val v = gen.embed("normalize this text please")
        val magnitude = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        assertEquals(1.0f, magnitude, 1e-6f)
    }

    @Test
    fun `similar texts have higher cosine similarity than unrelated texts`() {
        val a = gen.embed("Kotlin coroutines async programming")
        val b = gen.embed("Kotlin coroutines suspend functions")
        val c = gen.embed("database index relational schema")
        assertTrue(cosineSimilarity(a, b) > cosineSimilarity(a, c))
    }

    @Test
    fun `different texts produce distinguishable vectors`() {
        val a = gen.embed("authentication token JWT")
        val b = gen.embed("database migration schema")
        assertTrue(cosineSimilarity(a, b) < 0.99f)
    }

    @Test
    fun `case insensitive - same words different case produce same embedding`() {
        val lower = gen.embed("hello world")
        val upper = gen.embed("HELLO WORLD")
        assertTrue(lower.contentEquals(upper))
    }

    @Test
    fun `empty text produces zero vector`() {
        val v = gen.embed("")
        assertTrue(v.all { it == 0f })
    }

    @Test
    fun `single word produces non-zero embedding`() {
        val v = gen.embed("kotlin")
        assertTrue(v.any { it != 0f })
    }

    @Test
    fun `embedBatch returns one vector per text`() {
        val texts = listOf("first text", "second text", "third text")
        assertEquals(3, gen.embedBatch(texts).size)
    }

    @Test
    fun `embedBatch results match individual embed calls`() {
        val texts = listOf("alpha beta", "gamma delta")
        val batch = gen.embedBatch(texts)
        texts.forEachIndexed { i, text ->
            assertTrue(batch[i].contentEquals(gen.embed(text)))
        }
    }

    @Test
    fun `embedBatch on empty list returns empty`() {
        assertTrue(gen.embedBatch(emptyList()).isEmpty())
    }
}
