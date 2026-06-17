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
data class ExecutionAgentResponse(
    val executionComplete: Boolean = false,
    val currentStep: String = "Проектирование",
    val expectedAction: String = "Подтвердить результат",
    val artifact: String = "",
    val response: String = ""
)

internal class ExecutionAgent(
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

    fun run(feature: Feature, task: Task, userInput: String): ExecutionAgentResponse? {
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
                source = "[ExecutionAgent]",
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

    private fun applyToTask(task: Task, agentResponse: ExecutionAgentResponse) {
        taskRepository.updateCurrentStep(
            taskId = task.id,
            currentStep = agentResponse.currentStep,
            expectedAction = agentResponse.expectedAction
        )

        if (agentResponse.artifact.isNotBlank()) {
            taskRepository.saveArchitecture(task.id, agentResponse.artifact)
        }

        if (agentResponse.executionComplete) {
            taskRepository.updateStage(task.id, Stage.VALIDATION)
            NetworkLogger.logEvent(
                source = "[FSM]",
                message = "EXECUTION → VALIDATION: ${task.id} | ${task.title}"
            )
        }

        taskRepository.appendHistory(task.id, agentResponse.response, role = "ExecutionAgent")
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
            appendLine("CURRENT ARCHITECTURE")
            appendLine(architecture)
            appendLine()
        } else {
            appendLine("CURRENT ARCHITECTURE: (пусто — начни с нуля)")
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

    private fun parseResponse(raw: String): ExecutionAgentResponse? {
        val trimmed = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        runCatching { json.decodeFromString<ExecutionAgentResponse>(trimmed) }.getOrNull()?.let { return it }
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start >= 0 && end > start) {
            runCatching { json.decodeFromString<ExecutionAgentResponse>(raw.substring(start, end + 1)) }
                .getOrNull()?.let { return it }
        }
        return null
    }

    private fun loadSystemPrompt(): String =
        runCatching { File(promptDir, "execution_agent.txt").readText() }
            .getOrElse { FALLBACK_PROMPT }
}

private fun okhttp3.Headers.toMap(): Map<String, String> = buildMap {
    forEach { (name, value) -> put(name, value) }
}

private val FALLBACK_PROMPT = """
Ты — агент проектирования задачи. Создавай архитектурный документ на основе требований.
Верни ТОЛЬКО JSON: {"executionComplete":false,"currentStep":"...","expectedAction":"...","artifact":"...","response":"..."}
artifact — полный markdown-документ архитектуры (сохраняется в файл, пользователю не показывается напрямую).
response — ответ пользователю в консоль.
""".trimIndent()
