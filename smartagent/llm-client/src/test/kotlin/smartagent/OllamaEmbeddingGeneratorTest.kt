package smartagent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OllamaEmbeddingGeneratorTest {

    private lateinit var server: MockWebServer
    private lateinit var generator: OllamaEmbeddingGenerator

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        generator = OllamaEmbeddingGenerator(baseUrl = server.url("").toString().trimEnd('/'))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `embed returns EmbeddingResult with vector and tokens`() {
        server.enqueue(MockResponse()
            .setBody("""{"embeddings":[[0.1,0.2,0.3]],"prompt_eval_count":42}""")
            .setResponseCode(200))

        val result = generator.embed("hello")

        assertContentEquals(floatArrayOf(0.1f, 0.2f, 0.3f), result.vector)
        assertEquals(42, result.promptTokens)
    }

    @Test
    fun `embed defaults tokens to 0 when not in response`() {
        server.enqueue(MockResponse()
            .setBody("""{"embeddings":[[0.0]]}""")
            .setResponseCode(200))

        val result = generator.embed("test")

        assertEquals(0, result.promptTokens)
    }

    @Test
    fun `embed sends POST to correct endpoint`() {
        server.enqueue(MockResponse().setBody("""{"embeddings":[[0.0]]}""").setResponseCode(200))

        generator.embed("test")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/embed", request.path)
    }

    @Test
    fun `embed sends correct model and input in request body`() {
        server.enqueue(MockResponse().setBody("""{"embeddings":[[0.0]]}""").setResponseCode(200))

        generator.embed("my text")

        val request = server.takeRequest()
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("nomic-embed-text", body["model"]?.jsonPrimitive?.content)
        assertEquals("my text", body["input"]?.jsonPrimitive?.content)
    }

    @Test
    fun `embed throws on HTTP error`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))

        val ex = assertFailsWith<IllegalStateException> { generator.embed("text") }
        assertTrue(ex.message!!.contains("500"))
    }

    @Test
    fun `embed throws when Ollama unreachable`() {
        server.shutdown()

        val ex = assertFailsWith<IllegalStateException> { generator.embed("text") }
        assertTrue(ex.message!!.contains("unavailable"))
    }

    @Test
    fun `embed throws on empty response body`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))

        assertFailsWith<IllegalStateException> { generator.embed("text") }
    }

    @Test
    fun `embed throws on empty embeddings array`() {
        server.enqueue(MockResponse()
            .setBody("""{"embeddings":[]}""")
            .setResponseCode(200))

        assertFailsWith<IllegalStateException> { generator.embed("text") }
    }

    @Test
    fun `dimension is 768`() {
        assertEquals(768, generator.dimension)
    }

    @Test
    fun `embedBatch calls embed for each text`() {
        repeat(3) {
            server.enqueue(MockResponse()
                .setBody("""{"embeddings":[[${it}.0]]}""")
                .setResponseCode(200))
        }

        val results = generator.embedBatch(listOf("a", "b", "c"))

        assertEquals(3, results.size)
        assertEquals(3, server.requestCount)
    }
}
