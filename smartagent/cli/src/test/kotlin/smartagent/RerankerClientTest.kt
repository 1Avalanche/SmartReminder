package smartagent

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RerankerClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: RerankerClient

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        client = RerankerClient(
            apiKey = "test-key",
            model = ModelConfig.RERANK,
            client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        )
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    private fun rerankUrl() = server.url("").toString().trimEnd('/')

    private fun clientWithUrl(url: String) = RerankerClient(
        apiKey = "test-key",
        overrideUrl = url,
        client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    )

    @Test
    fun `rerank returns results for valid response`() {
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""
                {"results":[
                    {"index":1,"relevance_score":0.95},
                    {"index":0,"relevance_score":0.82},
                    {"index":2,"relevance_score":0.45}
                ]}
            """.trimIndent())
        )

        val results = clientWithUrl(rerankUrl()).rerank(
            query = "test query",
            documents = listOf("doc1", "doc2", "doc3"),
            topN = 3
        )

        assertEquals(3, results.size)
        assertEquals(1, results[0].index)
        assertEquals(0.95, results[0].score)
        assertEquals(0, results[1].index)
        assertEquals(0.82, results[1].score)
    }

    @Test
    fun `rerank returns empty list for empty documents`() {
        val results = client.rerank(query = "test", documents = emptyList())

        assertTrue(results.isEmpty())
    }

    @Test
    fun `rerank respects topN parameter`() {
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""
                {"results":[
                    {"index":2,"relevance_score":0.95},
                    {"index":0,"relevance_score":0.80}
                ]}
            """.trimIndent())
        )

        val results = clientWithUrl(rerankUrl()).rerank(
            query = "test",
            documents = listOf("a", "b", "c"),
            topN = 2
        )

        assertEquals(2, results.size)
    }

    @Test
    fun `rerank returns empty list on HTTP error`() {
        server.enqueue(MockResponse().setResponseCode(500))

        val results = clientWithUrl(rerankUrl()).rerank(
            query = "test",
            documents = listOf("doc1")
        )

        assertTrue(results.isEmpty())
    }

    @Test
    fun `rerank returns empty list on invalid JSON`() {
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("not json")
        )

        val results = clientWithUrl(rerankUrl()).rerank(
            query = "test",
            documents = listOf("doc1")
        )

        assertTrue(results.isEmpty())
    }

    @Test
    fun `rerank returns empty list on empty response body`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))

        val results = clientWithUrl(rerankUrl()).rerank(
            query = "test",
            documents = listOf("doc1")
        )

        assertTrue(results.isEmpty())
    }

    @Test
    fun `rerank returns empty list on timeout`() {
        server.enqueue(MockResponse().setBodyDelay(2, java.util.concurrent.TimeUnit.SECONDS)
            .setResponseCode(200)
            .setBody("""{"results":[{"index":0,"relevance_score":0.9}]}""")
        )

        val slowClient = RerankerClient(
            apiKey = "test-key",
            overrideUrl = rerankUrl(),
            client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(1, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(1, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        )

        val results = slowClient.rerank(query = "test", documents = listOf("doc1"))

        assertTrue(results.isEmpty())
    }

    @Test
    fun `rerank handles empty results array`() {
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"results":[]}""")
        )

        val results = clientWithUrl(rerankUrl()).rerank(
            query = "test",
            documents = listOf("doc1")
        )

        assertTrue(results.isEmpty())
    }
}
