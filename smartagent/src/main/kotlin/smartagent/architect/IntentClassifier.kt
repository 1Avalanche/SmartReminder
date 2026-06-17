package smartagent.architect

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import smartagent.ChatRequest
import smartagent.ChatResponse
import smartagent.ChatSession
import smartagent.Config
import smartagent.Message
import smartagent.NetworkLogger
import smartagent.json
import java.io.File
import java.util.concurrent.TimeUnit

@Serializable
enum class UserIntent {
    NEW_FEATURE,
    NEW_TASK,
    TASK_UPDATE,
    SWITCH_FEATURE,
    QUESTION,
    APPROVAL
}

@Serializable
data class IntentResult(
    val intent: UserIntent,
    val featureId: String? = null,
    val taskId: String? = null,
    val confidence: Double = 0.0,
    val reason: String? = null
)

internal class IntentClassifier(
    private val session: ChatSession,
    private val featureRepository: FeatureRepository,
    private val taskRepository: TaskRepository
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val promptDir: File = listOf(
        "smartagent/src/main/kotlin/prompts/architect",
        "src/main/kotlin/prompts/architect",
        "prompts/architect"
    ).map(::File).firstOrNull { it.isDirectory } ?: File("smartagent/src/main/kotlin/prompts/architect")

    fun classify(userInput: String): IntentResult? {
        val apiKey = Config.apiKey(session.currentModel) ?: return null
        val systemPrompt = loadSystemPrompt()
        val userContent = buildContext(userInput)

        val messages = listOf(
            Message("system", systemPrompt),
            Message("user", userContent)
        )
        val requestBody = json.encodeToString(ChatRequest(session.currentModel.apiModelId, messages))

        val request = Request.Builder()
            .url(session.currentModel.url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        return runCatching {
            val startMs = System.currentTimeMillis()
            val response = http.newCall(request).execute()
            val durationMs = System.currentTimeMillis() - startMs
            val body = response.body?.string() ?: ""
            val chatResponse = runCatching { json.decodeFromString<ChatResponse>(body) }.getOrNull()
            NetworkLogger.logRequest(
                source = "[IntentClassifier]",
                url = session.currentModel.url,
                reqHeaders = request.headers.toMap(),
                reqBody = requestBody,
                statusCode = response.code,
                resHeaders = response.headers.toMap(),
                resBody = body,
                durationMs = durationMs,
                usage = chatResponse?.usage
            )
            if (!response.isSuccessful) return@runCatching null
            val raw = chatResponse?.choices?.firstOrNull()?.message?.content?.trim()
                ?: return@runCatching null
            parseResult(raw)
        }.getOrNull()
    }

    private fun buildContext(userInput: String): String = buildString {
        val active = featureRepository.getActiveFeature()
        if (active != null) {
            appendLine("ACTIVE FEATURE")
            appendLine("${active.id} | ${active.title}")
            if (active.summary.isNotBlank()) appendLine("summary: ${active.summary}")
            appendLine()

            val tasks = taskRepository.getTasksForFeature(active.id)
                .filter { it.status != TaskStatus.COMPLETED }
            if (tasks.isNotEmpty()) {
                appendLine("OPEN TASKS")
                tasks.forEach { t ->
                    val activeMarker = if (t.status == TaskStatus.ACTIVE) " [ACTIVE]" else ""
                    appendLine("${t.id} | ${t.title}$activeMarker")
                    if (t.summary.isNotBlank()) appendLine("  summary: ${t.summary}")
                }
                appendLine()
            }
        } else {
            appendLine("ACTIVE FEATURE: none")
            appendLine()
        }

        val others = featureRepository.getAllFeatures()
            .filter { it.id != active?.id && it.status != FeatureStatus.COMPLETED }
        if (others.isNotEmpty()) {
            appendLine("OTHER FEATURES")
            others.forEach { f -> appendLine("${f.id} | ${f.title}") }
            appendLine()
        }

        appendLine("USER MESSAGE")
        appendLine()
        append(userInput)
    }.trimEnd()

    private fun parseResult(raw: String): IntentResult? {
        val trimmed = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        runCatching { json.decodeFromString<IntentResult>(trimmed) }.getOrNull()?.let { return it }
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start >= 0 && end > start) {
            runCatching { json.decodeFromString<IntentResult>(raw.substring(start, end + 1)) }
                .getOrNull()?.let { return it }
        }
        return null
    }

    private fun loadSystemPrompt(): String =
        runCatching { File(promptDir, "intent_classifier.txt").readText() }
            .getOrElse { FALLBACK_PROMPT }
}

private fun okhttp3.Headers.toMap(): Map<String, String> = buildMap {
    forEach { (name, value) -> put(name, value) }
}

private val FALLBACK_PROMPT = """
Ты — классификатор намерений. Верни ТОЛЬКО JSON:
{"intent":"...","featureId":null,"taskId":null,"confidence":0.9,"reason":"..."}
intent: NEW_FEATURE | NEW_TASK | TASK_UPDATE | SWITCH_FEATURE | QUESTION | APPROVAL
TASK_UPDATE: укажи taskId из OPEN TASKS, к которой относится запрос.
""".trimIndent()
