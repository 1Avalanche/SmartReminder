package smartagent.architect

import kotlinx.serialization.Serializable
import smartagent.LLMGateway
import smartagent.Message
import smartagent.SessionConfig
import smartagent.TokenTracker
import smartagent.json
import java.io.File

@Serializable
data class PlanningAgentResponse(
    val planningComplete: Boolean = false,
    val currentStep: String = "Сбор требований",
    val expectedAction: String = "Уточнить требования",
    val summary: String = "",
    val response: String? = null,
    val plan: String? = null
)

internal class PlanningAgent(
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

    fun run(feature: Feature, task: Task, userInput: String, invariants: String = ""): PlanningAgentResponse? {
        val parsed = fetch(feature, task, userInput, invariants) ?: return null
        apply(task, parsed)
        return parsed
    }

    fun fetch(feature: Feature, task: Task, userInput: String, invariants: String = ""): PlanningAgentResponse? {
        val messages = listOf(
            Message("system", loadSystemPrompt(invariants)),
            Message("user", buildContext(feature, task, userInput))
        )
        val response = gateway.chat(messages, config.currentModel, "[PlanningAgent]") ?: return null
        response.usage?.let { tokens.addTokenEntry(it) }
        return parseResponse(response.content)
    }

    fun apply(task: Task, agentResponse: PlanningAgentResponse) {
        applyToTask(task, agentResponse)
    }

    private fun applyToTask(task: Task, agentResponse: PlanningAgentResponse) {
        taskRepository.updateCurrentStep(
            taskId = task.id,
            currentStep = agentResponse.currentStep,
            expectedAction = agentResponse.expectedAction
        )
        val updated = taskRepository.getTask(task.id)?.copy(summary = agentResponse.summary)
        if (updated != null) taskRepository.updateTask(updated)

        if (agentResponse.planningComplete) {
            agentResponse.plan?.takeIf { it.isNotBlank() }?.let {
                taskRepository.savePlan(task.id, it)
            }
        }

        taskRepository.appendHistory(task.id, agentResponse.response.orEmpty(), role = "PlanningAgent")
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

    private fun parseResponse(raw: String): PlanningAgentResponse? {
        val trimmed = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        runCatching { json.decodeFromString<PlanningAgentResponse>(trimmed) }.getOrNull()?.let { return it }
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start >= 0 && end > start) {
            runCatching { json.decodeFromString<PlanningAgentResponse>(raw.substring(start, end + 1)) }
                .getOrNull()?.let { return it }
        }
        return null
    }

    private fun loadSystemPrompt(invariants: String = ""): String {
        val base = runCatching { File(promptDir, "planning_agent.txt").readText() }
            .getOrElse { FALLBACK_PROMPT }
        if (invariants.isEmpty()) return base
        return "$base\n\nЗАПРЕТЫ — ВСЁ ПЕРЕЧИСЛЕННОЕ ЗАПРЕЩЕНО К ИСПОЛЬЗОВАНИЮ В АРХИТЕКТУРЕ:\n$invariants"
    }
}

private val FALLBACK_PROMPT = """
Ты — агент планирования задачи. Собирай требования, задавай уточняющие вопросы. Ты не можешь начать планирование, если остались незакрытые вопросы к пользователю - задавай уточняющие вопросы, пока не получишь на них ответ.
Верни ТОЛЬКО JSON: {"planningComplete":false,"currentStep":"...","expectedAction":"...","summary":"...","response":"...","plan":null}
Когда planningComplete:true, заполни plan — markdown-документ с требованиями.
""".trimIndent()
