package smartagent

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class OpenRouterEmbeddingGenerator(
    private val apiKey: String = Config.localProperties["OPENROUTER_STUDY_API_KEY"]
        ?: System.getenv("OPENROUTER_STUDY_API_KEY")
        ?: error("OPENROUTER_STUDY_API_KEY not set"),
    private val model: String = "qwen/qwen3-embedding-4b",
    private val client: OkHttpClient = OkHttpClient(),
    private val normalizer: TextNormalizer = DefaultTextNormalizer()
) : EmbeddingGenerator {

    override val dimension: Int = 2560

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class EmbedRequest(val model: String, val input: String)

    @Serializable
    private data class EmbeddingData(val embedding: List<Float>, val index: Int)

    @Serializable
    private data class EmbedResponse(val data: List<EmbeddingData>)

    override fun embed(text: String): EmbeddingResult {
        val cleanText = normalizer.normalize(text)
        val body = json.encodeToString(EmbedRequest(model = model, input = cleanText))
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/embeddings")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body)
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw IllegalStateException("OpenRouter embeddings unavailable: ${e.message}", e)
        }

        response.use {
            val bodyStr = it.body?.string()
            if (!it.isSuccessful) {
                throw IllegalStateException("OpenRouter returned HTTP ${it.code}: ${it.message}\nBody: ${bodyStr ?: "N/A"}")
            }
            val responseBody = bodyStr?.takeIf { s -> s.isNotBlank() }
                ?: throw IllegalStateException("OpenRouter returned empty response")
            val parsed = json.decodeFromString<EmbedResponse>(responseBody)
            val vector = parsed.data.firstOrNull()?.embedding?.toFloatArray()
                ?: throw IllegalStateException("OpenRouter returned empty embeddings")
            return EmbeddingResult(vector = vector, promptTokens = 0)
        }
    }
}
