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
data class PlanningAgentResponse(
    val planningComplete: Boolean = false,
    val currentStep: String = "Сбор требований",
    val expectedAction: String = "Уточнить требования",
    val summary: String = "",
    val response: String = "",
    val plan: String? = null
)

internal class PlanningAgent(
    private val config: SessionConfig,
    private val tokens: TokenTracker,
    private val taskRepository: TaskRepository,
    private val gateway: LLMGateway,
    private val invariantAgent: InvariantAgent
) {
    private val promptDir: File = listOf(
        "smartagent/src/main/kotlin/prompts/architect",
        "src/main/kotlin/prompts/architect",
        "prompts/architect"
    ).map(::File).firstOrNull { it.isDirectory } ?: File("smartagent/src/main/kotlin/prompts/architect")

    fun run(feature: Feature, task: Task, userInput: String): PlanningAgentResponse? {
        val parsed = fetch(feature, task, userInput) ?: return null
        apply(task, parsed)
        return parsed
    }

    fun fetch(feature: Feature, task: Task, userInput: String): PlanningAgentResponse? {
        val messages = listOf(
            Message("system", loadSystemPrompt()),
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
            taskRepository.updateStage(task.id, Stage.EXECUTION)
            NetworkLogger.logEvent(
                source = "[FSM]",
                message = "PLANNING → EXECUTION: ${task.id} | ${task.title}"
            )
        }

        taskRepository.appendHistory(task.id, agentResponse.response, role = "PlanningAgent")
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

    private fun loadSystemPrompt(): String {
        val base = runCatching { File(promptDir, "planning_agent.txt").readText() }
            .getOrElse { FALLBACK_PROMPT }
        val invariants = invariantAgent.getAllInvariants()
        if (invariants.isEmpty()) return base
        return "$base\n\nЗАПРЕТЫ — ВСЁ ПЕРЕЧИСЛЕННОЕ ЗАПРЕЩЕНО К ИСПОЛЬЗОВАНИЮ В АРХИТЕКТУРЕ:\n$invariants"
    }
}

private val FALLBACK_PROMPT = """
Ты — агент планирования задачи. Собирай требования, задавай уточняющие вопросы.
Верни ТОЛЬКО JSON: {"planningComplete":false,"currentStep":"...","expectedAction":"...","summary":"...","response":"...","plan":null}
Когда planningComplete:true, заполни plan — markdown-документ с требованиями.
""".trimIndent()
