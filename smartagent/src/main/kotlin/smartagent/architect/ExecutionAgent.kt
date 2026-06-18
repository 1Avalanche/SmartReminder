package smartagent.architect

import kotlinx.serialization.Serializable
import smartagent.LLMGateway
import smartagent.Message
import smartagent.NetworkLogger
import smartagent.SessionConfig
import smartagent.TokenTracker
import smartagent.json
import java.io.File

@Serializable
data class ExecutionAgentResponse(
    val executionComplete: Boolean = false,
    val currentStep: String = "Проектирование",
    val expectedAction: String = "Подтвердить результат",
    val artifact: String = "",
    val response: String = ""
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

    fun run(feature: Feature, task: Task, userInput: String): ExecutionAgentResponse? {
        val messages = listOf(
            Message("system", loadSystemPrompt()),
            Message("user", buildContext(feature, task, userInput))
        )
        val response = gateway.chat(messages, config.currentModel, "[ExecutionAgent]") ?: return null
        response.usage?.let { tokens.addTokenEntry(it) }
        val parsed = parseResponse(response.content) ?: return null
        applyToTask(task, parsed)
        return parsed
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

private val FALLBACK_PROMPT = """
Ты — агент проектирования задачи. Создавай архитектурный документ на основе требований.
Верни ТОЛЬКО JSON: {"executionComplete":false,"currentStep":"...","expectedAction":"...","artifact":"...","response":"..."}
artifact — полный markdown-документ архитектуры (сохраняется в файл, пользователю не показывается напрямую).
response — ответ пользователю в консоль.
""".trimIndent()
