package smartagent

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class OllamaEmbeddingGenerator(
    private val baseUrl: String = "http://localhost:11434",
    private val model: String = "nomic-embed-text",
    private val client: OkHttpClient = OkHttpClient(),
    private val normalizer: TextNormalizer = DefaultTextNormalizer()
) : EmbeddingGenerator {

    override val dimension: Int = 768

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class EmbedRequest(val model: String, val input: String)

    @Serializable
    private data class EmbedResponse(
        val embeddings: List<List<Float>>,
        val prompt_eval_count: Int = 0
    )

    override fun embed(text: String): EmbeddingResult {
        val cleanText = normalizer.normalize(text)
        val body = json.encodeToString(EmbedRequest(model = model, input = cleanText))
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$baseUrl/api/embed")
            .post(body)
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw IllegalStateException("Ollama unavailable at $baseUrl: ${e.message}", e)
        }

        response.use {
            val bodyStr = it.body?.string()
            if (!it.isSuccessful) {
                throw IllegalStateException("Ollama returned HTTP ${it.code}: ${it.message}\nBody: ${bodyStr ?: "N/A"}")
            }
            val responseBody = bodyStr?.takeIf { s -> s.isNotBlank() }
                ?: throw IllegalStateException("Ollama returned empty response")
            val parsed = json.decodeFromString<EmbedResponse>(responseBody)
            val vector = parsed.embeddings.firstOrNull()?.toFloatArray()
                ?: throw IllegalStateException("Ollama returned empty embeddings")
            return EmbeddingResult(vector = vector, promptTokens = parsed.prompt_eval_count)
        }
    }
}
