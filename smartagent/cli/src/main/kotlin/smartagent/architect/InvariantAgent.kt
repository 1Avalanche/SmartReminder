package smartagent.architect

import kotlinx.serialization.Serializable
import smartagent.LLMGateway
import smartagent.Message
import smartagent.SessionConfig
import smartagent.TokenTracker
import smartagent.json
import java.io.File

@Serializable
enum class InvariantStatus { VALID, INVALID, NEW_INVARIANT }

@Serializable
data class InvariantResult(
    val status: InvariantStatus,
    val reason: String = "",
    val invariant: String = ""
)

internal class InvariantAgent(
    private val config: SessionConfig,
    private val tokens: TokenTracker,
    private val gateway: LLMGateway
) {
    private val promptDir: File = listOf(
        "smartagent/src/main/kotlin/prompts/architect",
        "src/main/kotlin/prompts/architect",
        "prompts/architect"
    ).map(::File).firstOrNull { it.isDirectory } ?: File("smartagent/src/main/kotlin/prompts/architect")

    private val invariantsDir: File = listOf(
        "smartagent/architect/invariants",
        "architect/invariants"
    ).map(::File).firstOrNull { it.isDirectory } ?: File("smartagent/architect/invariants")

    fun check(input: String): InvariantResult {
        val invariants = loadInvariants()
        val messages = listOf(
            Message("system", loadSystemPrompt()),
            Message("user", buildContext(input, invariants))
        )
        val response = gateway.chat(messages, config.currentModel, "[InvariantAgent]") ?: return InvariantResult(InvariantStatus.VALID)
        response.usage?.let { tokens.addTokenEntry(it) }
        return parseResult(response.content) ?: InvariantResult(InvariantStatus.VALID)
    }

    fun saveUserInvariant(invariant: String) {
        val userFile = File(invariantsDir, "user.md")
        invariantsDir.mkdirs()
        val existing = runCatching { userFile.readText().trim() }.getOrElse { "" }
        val updated = if (existing.isEmpty()) invariant else "$existing\n$invariant"
        userFile.writeText(updated)
    }

    fun clearUserInvariants() {
        runCatching { File(invariantsDir, "user.md").writeText("") }
    }

    fun getUserInvariants(): String =
        runCatching { File(invariantsDir, "user.md").readText().trim() }.getOrElse { "" }

    fun getAllInvariants(): String = loadInvariants()

    private fun loadInvariants(): String = buildString {
        val system = runCatching { File(invariantsDir, "system.md").readText().trim() }.getOrElse { "" }
        val user = runCatching { File(invariantsDir, "user.md").readText().trim() }.getOrElse { "" }
        if (system.isNotEmpty()) {
            appendLine("## СИСТЕМНЫЕ ЗАПРЕТЫ (запрещено использовать):")
            appendLine(system)
        }
        if (user.isNotEmpty()) {
            if (system.isNotEmpty()) appendLine()
            appendLine("## ПОЛЬЗОВАТЕЛЬСКИЕ ЗАПРЕТЫ (запрещено использовать):")
            appendLine(user)
        }
    }.trim()

    private fun buildContext(input: String, invariants: String): String = buildString {
        if (invariants.isNotEmpty()) {
            appendLine("СПИСОК ЗАПРЕТОВ — ВСЁ ПЕРЕЧИСЛЕННОЕ ЗАПРЕЩЕНО К ИСПОЛЬЗОВАНИЮ")
            appendLine(invariants)
            appendLine()
        } else {
            appendLine("СПИСОК ЗАПРЕТОВ: пусто")
            appendLine()
        }
        appendLine("ТЕКСТ ДЛЯ ПРОВЕРКИ")
        appendLine()
        append(input)
    }.trimEnd()

    private fun parseResult(raw: String): InvariantResult? {
        val trimmed = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        runCatching { json.decodeFromString<InvariantResult>(trimmed) }.getOrNull()?.let { return it }
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start >= 0 && end > start) {
            runCatching { json.decodeFromString<InvariantResult>(raw.substring(start, end + 1)) }
                .getOrNull()?.let { return it }
        }
        return null
    }

    private fun loadSystemPrompt(): String =
        runCatching { File(promptDir, "invariant_agent.txt").readText() }
            .getOrElse { FALLBACK_PROMPT }
}

private val FALLBACK_PROMPT = """
Ты — агент проверки инвариантов. Верни ТОЛЬКО JSON:
{"status":"VALID|INVALID|NEW_INVARIANT","reason":"","invariant":""}
INVALID — нарушает инвариант. NEW_INVARIANT — пользователь объявляет новый запрет. VALID — всё в порядке.
""".trimIndent()
