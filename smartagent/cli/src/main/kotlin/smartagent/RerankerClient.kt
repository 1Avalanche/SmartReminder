package smartagent

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

data class RerankResult(
    val index: Int,
    val score: Double
)

class RerankerClient(
    private val apiKey: String,
    private val model: ModelConfig = ModelConfig.RERANK,
    private val overrideUrl: String? = null,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
) {
    private val baseUrl: String get() = overrideUrl ?: model.url
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class RerankRequest(
        val model: String,
        val query: String,
        val documents: List<String>,
        @kotlinx.serialization.SerialName("top_n")
        val topN: Int
    )

    @Serializable
    private data class RerankResponse(
        val results: List<RerankResponseResult> = emptyList()
    )

    @Serializable
    private data class RerankResponseResult(
        val index: Int,
        @kotlinx.serialization.SerialName("relevance_score")
        val relevanceScore: Double
    )

    fun rerank(query: String, documents: List<String>, topN: Int = 3): List<RerankResult> {
        if (documents.isEmpty()) return emptyList()

        val requestBodyString = json.encodeToString(
            RerankRequest(
                model = model.apiModelId,
                query = query,
                documents = documents,
                topN = topN
            )
        )

        val request = Request.Builder()
            .url("$baseUrl/rerank")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBodyString.toRequestBody("application/json".toMediaType()))
            .build()

        val reqHeaders = request.headers.toMap()

        return try {
            val response = client.newCall(request).execute()
            response.use {
                val bodyStr = it.body?.string()
                val resHeaders = it.headers.toMap()
                if (!it.isSuccessful) {
                    NetworkLogger.log("$baseUrl/rerank", reqHeaders, requestBodyString, it.code, resHeaders, bodyStr ?: "N/A", "[RERANK]")
                    println("${Colors.LIGHT_YELLOW}Reranker API error: HTTP ${it.code} — ${bodyStr?.take(200) ?: "N/A"}${Colors.RESET}")
                    return emptyList()
                }
                if (bodyStr.isNullOrBlank()) {
                    NetworkLogger.log("$baseUrl/rerank", reqHeaders, requestBodyString, it.code, resHeaders, "Empty response", "[RERANK]")
                    println("${Colors.LIGHT_YELLOW}Reranker returned empty response${Colors.RESET}")
                    return emptyList()
                }
                val parsed = json.decodeFromString<RerankResponse>(bodyStr)
                NetworkLogger.log("$baseUrl/rerank", reqHeaders, requestBodyString, it.code, resHeaders, bodyStr, "[RERANK]")
                parsed.results.map { result ->
                    RerankResult(index = result.index, score = result.relevanceScore)
                }
            }
        } catch (e: IOException) {
            NetworkLogger.log("$baseUrl/rerank", reqHeaders, requestBodyString, 0, emptyMap(), "IOException: ${e.message}", "[RERANK]")
            println("${Colors.LIGHT_YELLOW}Reranker unavailable: ${e.message}${Colors.RESET}")
            emptyList()
        } catch (e: Exception) {
            NetworkLogger.log("$baseUrl/rerank", reqHeaders, requestBodyString, 0, emptyMap(), "Error: ${e.message}", "[RERANK]")
            println("${Colors.LIGHT_YELLOW}Reranker error: ${e.message}${Colors.RESET}")
            emptyList()
        }
    }
}
