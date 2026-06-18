package smartagent.architect

import kotlinx.serialization.Serializable
import smartagent.LLMGateway
import smartagent.Message
import smartagent.SessionConfig
import smartagent.json
import java.io.File

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
    private val config: SessionConfig,
    private val featureRepository: FeatureRepository,
    private val taskRepository: TaskRepository,
    private val gateway: LLMGateway
) {
    private val promptDir: File = listOf(
        "smartagent/src/main/kotlin/prompts/architect",
        "src/main/kotlin/prompts/architect",
        "prompts/architect"
    ).map(::File).firstOrNull { it.isDirectory } ?: File("smartagent/src/main/kotlin/prompts/architect")

    fun classify(userInput: String): IntentResult? {
        val messages = listOf(
            Message("system", loadSystemPrompt()),
            Message("user", buildContext(userInput))
        )
        val response = gateway.chat(messages, config.currentModel, "[IntentClassifier]") ?: return null
        return parseResult(response.content)
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

private val FALLBACK_PROMPT = """
Ты — классификатор намерений. Верни ТОЛЬКО JSON:
{"intent":"...","featureId":null,"taskId":null,"confidence":0.9,"reason":"..."}
intent: NEW_FEATURE | NEW_TASK | TASK_UPDATE | SWITCH_FEATURE | QUESTION | APPROVAL
TASK_UPDATE: укажи taskId из OPEN TASKS, к которой относится запрос.
""".trimIndent()
