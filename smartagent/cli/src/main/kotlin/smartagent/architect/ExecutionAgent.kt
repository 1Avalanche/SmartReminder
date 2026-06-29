package smartagent.architect

import kotlinx.serialization.Serializable
import smartagent.LLMGateway
import smartagent.Message
import smartagent.SessionConfig
import smartagent.TokenTracker
import smartagent.json
import java.io.File

@Serializable
data class ExecutionAgentResponse(
    val executionComplete: Boolean = false,
    val currentStep: String = "Проектирование",
    val expectedAction: String = "Подтвердить результат",
    val artifact: String? = null,
    val response: String? = null
)

internal class ExecutionAgent(
    private val config: SessionConfig,
    private val tokens: TokenTracker,
    private val taskRepository: TaskRepository,
    private val gateway: LLMGateway
) {
    private val promptDir: File = listOf(
        "smartagent/src/main/kotlin/prompts/architect",
        "src/main/kotlin/prompts/architect",
        "prompts/architect"
    ).map(::File).firstOrNull { it.isDirectory } ?: File("smartagent/src/main/kotlin/prompts/architect")

    fun run(feature: Feature, task: Task, userInput: String, invariants: String = ""): ExecutionAgentResponse? {
        val parsed = fetch(feature, task, userInput, invariants) ?: return null
        apply(task, parsed)
        return parsed
    }

    fun fetch(feature: Feature, task: Task, userInput: String, invariants: String = ""): ExecutionAgentResponse? {
        val messages = listOf(
            Message("system", loadSystemPrompt(invariants)),
            Message("user", buildContext(task, userInput))
        )
        val response = gateway.chat(messages, config.currentModel, "[ExecutionAgent]") ?: return null
        response.usage?.let { tokens.addTokenEntry(it) }
        return parseResponse(response.content)
    }

    fun apply(task: Task, agentResponse: ExecutionAgentResponse) {
        applyToTask(task, agentResponse)
    }

    private fun applyToTask(task: Task, agentResponse: ExecutionAgentResponse) {
        taskRepository.updateCurrentStep(
            taskId = task.id,
            currentStep = agentResponse.currentStep,
            expectedAction = agentResponse.expectedAction
        )

        if (!agentResponse.artifact.isNullOrBlank()) {
            taskRepository.saveArchitecture(task.id, agentResponse.artifact)
        }

        taskRepository.appendHistory(task.id, agentResponse.response.orEmpty(), role = "ExecutionAgent")
    }

    private fun buildContext(task: Task, userInput: String): String = buildString {
        val plan = taskRepository.getPlan(task.id)
        if (plan.isNotBlank()) {
            appendLine("PLAN")
            appendLine(plan)
            appendLine()
        }

        val architecture = taskRepository.getArchitecture(task.id)
        if (architecture.isNotBlank()) {
            appendLine("CURRENT ARTIFACT")
            appendLine(architecture)
            appendLine()
        } else {
            appendLine("CURRENT ARTIFACT: (пусто — создай с нуля)")
            appendLine()
        }

        if (userInput.startsWith("[INVARIANT VIOLATION]")) {
            appendLine("INVARIANT FEEDBACK")
            appendLine(userInput)
        }
        if (userInput.startsWith("[VALIDATION FEEDBACK]")) {
            appendLine("VALIDATION FEEDBACK")
            appendLine(userInput.removePrefix("[VALIDATION FEEDBACK]").trim())
        }
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

    private fun loadSystemPrompt(invariants: String = ""): String {
        val base = runCatching { File(promptDir, "execution_agent.txt").readText() }
            .getOrElse { FALLBACK_PROMPT }
        if (invariants.isEmpty()) return base
        return "$base\n\nЗАПРЕТЫ — ВСЁ ПЕРЕЧИСЛЕННОЕ ЗАПРЕЩЕНО К ИСПОЛЬЗОВАНИЮ В АРХИТЕКТУРЕ:\n$invariants"
    }
}

private val FALLBACK_PROMPT = """
Ты — агент проектирования задачи. Создавай архитектурный документ на основе требований.
Верни ТОЛЬКО JSON: {"executionComplete":false,"currentStep":"...","expectedAction":"...","artifact":"...","response":"..."}
artifact — полный markdown-документ архитектуры (сохраняется в файл, пользователю не показывается напрямую).
response — ответ пользователю в консоль.
""".trimIndent()
