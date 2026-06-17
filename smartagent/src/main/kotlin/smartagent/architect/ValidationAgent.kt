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
data class ValidationAgentResponse(
    val validationPassed: Boolean = false,
    val returnToExecution: Boolean = false,
    val currentStep: String = "",
    val expectedAction: String? = null,
    val review: String = "",
    val response: String = ""
)

internal class ValidationAgent(
    private val session: ChatSession,
    private val taskRepository: TaskRepository
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val promptDir: File = listOf(
        "smartagent/src/main/kotlin/prompts/architect",
        "src/main/kotlin/prompts/architect",
        "prompts/architect"
    ).map(::File).firstOrNull { it.isDirectory } ?: File("smartagent/src/main/kotlin/prompts/architect")

    fun run(feature: Feature, task: Task, userInput: String): ValidationAgentResponse? {
        val apiKey = Config.apiKey(session.currentModel) ?: return null
        val systemPrompt = loadSystemPrompt()
        val userContent = buildContext(feature, task, userInput)

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
                source = "[ValidationAgent]",
                url = session.currentModel.url,
                reqHeaders = request.headers.toMap(),
                reqBody = requestBody,
                statusCode = response.code,
                resHeaders = response.headers.toMap(),
                resBody = body,
                durationMs = durationMs,
                usage = chatResponse?.usage
            )
            chatResponse?.usage?.let { session.addTokenEntry(it) }
            if (!response.isSuccessful) return@runCatching null
            val raw = chatResponse?.choices?.firstOrNull()?.message?.content?.trim()
                ?: return@runCatching null
            val parsed = parseResponse(raw) ?: return@runCatching null
            applyToTask(task, parsed)
            parsed
        }.getOrNull()
    }

    private fun applyToTask(task: Task, agentResponse: ValidationAgentResponse) {
        taskRepository.updateCurrentStep(
            taskId = task.id,
            currentStep = agentResponse.currentStep,
            expectedAction = agentResponse.expectedAction
        )

        if (agentResponse.review.isNotBlank()) {
            taskRepository.saveReview(task.id, agentResponse.review)
        }

        taskRepository.appendHistory(task.id, agentResponse.response, role = "ValidationAgent")

        when {
            agentResponse.validationPassed -> {
                taskRepository.completeTask(task.id)
                NetworkLogger.logEvent(
                    source = "[FSM]",
                    message = "VALIDATION → DONE: ${task.id} | ${task.title} (feature ${task.featureId} stays ACTIVE)"
                )
            }
            agentResponse.returnToExecution -> {
                taskRepository.updateStage(task.id, Stage.EXECUTION)
                NetworkLogger.logEvent(
                    source = "[FSM]",
                    message = "VALIDATION → EXECUTION: ${task.id} | ${task.title} | reason: ${agentResponse.currentStep}"
                )
            }
        }
    }

    private fun buildContext(feature: Feature, task: Task, userInput: String): String = buildString {
        appendLine("FEATURE")
        appendLine("id: ${feature.id}")
        appendLine("title: ${feature.title}")
        if (feature.summary.isNotBlank()) appendLine("summary: ${feature.summary}")
        appendLine()

        appendLine("TASK")
        appendLine("id: ${task.id}")
        appendLine("title: ${task.title}")
        appendLine("stage: ${task.stage}")
        if (task.summary.isNotBlank()) appendLine("summary: ${task.summary}")
        appendLine()

        val plan = taskRepository.getPlan(task.id)
        if (plan.isNotBlank()) {
            appendLine("PLAN")
            appendLine(plan)
            appendLine()
        }

        val architecture = taskRepository.getArchitecture(task.id)
        if (architecture.isNotBlank()) {
            appendLine("ARCHITECTURE")
            appendLine(architecture)
            appendLine()
        }

        val review = taskRepository.getReview(task.id)
        if (review.isNotBlank()) {
            appendLine("CURRENT REVIEW")
            appendLine(review)
            appendLine()
        }

        val history = taskRepository.getHistory(task.id)
        if (history.isNotBlank()) {
            appendLine("CONVERSATION HISTORY")
            appendLine(history)
            appendLine()
        }

        appendLine("USER MESSAGE")
        appendLine()
        append(userInput)
    }.trimEnd()

    private fun parseResponse(raw: String): ValidationAgentResponse? {
        val trimmed = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        runCatching { json.decodeFromString<ValidationAgentResponse>(trimmed) }.getOrNull()?.let { return it }
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start >= 0 && end > start) {
            runCatching { json.decodeFromString<ValidationAgentResponse>(raw.substring(start, end + 1)) }
                .getOrNull()?.let { return it }
        }
        return null
    }

    private fun loadSystemPrompt(): String =
        runCatching { File(promptDir, "validation_agent.txt").readText() }
            .getOrElse { FALLBACK_PROMPT }
}

private fun okhttp3.Headers.toMap(): Map<String, String> = buildMap {
    forEach { (name, value) -> put(name, value) }
}

private val FALLBACK_PROMPT = """
Ты — агент проверки архитектуры задачи. Найди пробелы и несогласованности.
Верни ТОЛЬКО JSON: {"validationPassed":false,"returnToExecution":true,"currentStep":"...","expectedAction":"...","review":"...","response":"..."}
review — внутренний документ (сохраняется в файл). response — ответ пользователю в консоль.
""".trimIndent()
