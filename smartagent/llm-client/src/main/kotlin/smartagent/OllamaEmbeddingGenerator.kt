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
    private val client: OkHttpClient = OkHttpClient()
) : EmbeddingGenerator {

    override val dimension: Int = 768

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class EmbedRequest(val model: String, val prompt: String)

    @Serializable
    private data class EmbedResponse(val embedding: List<Float>)

    override fun embed(text: String): FloatArray {
        val body = json.encodeToString(EmbedRequest(model = model, prompt = text))
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$baseUrl/api/embeddings")
            .post(body)
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw IllegalStateException("Ollama unavailable at $baseUrl: ${e.message}", e)
        }

        response.use {
            if (!it.isSuccessful) {
                throw IllegalStateException("Ollama returned HTTP ${it.code}: ${it.message}")
            }
            val responseBody = it.body?.string()?.takeIf { s -> s.isNotBlank() }
                ?: throw IllegalStateException("Ollama returned empty response")
            return json.decodeFromString<EmbedResponse>(responseBody).embedding.toFloatArray()
        }
    }
}
