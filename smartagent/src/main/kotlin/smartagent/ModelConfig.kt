package smartagent

internal val SYSTEM_PROMPT = """
    Ты - персональный ассистент. Отвечай кратко и по делу. Не фантазируй.Предоставляй пруфы на подтверждение своих слов.Предоставляй порядок своих рассуждений.
В ответ всегда возвращай только JSON без пояснений и без markdown-разметки с полями:
- content (обязательно) — ответ, который нужно отобразить пользователю.
- summary (опционально) - суммаризация диалога, если была запрошена
""".trimIndent()

@kotlinx.serialization.Serializable
internal enum class AgentMode(val displayName: String, val systemPrompt: String) {
    CHAT(
        displayName = "chat",
        systemPrompt = SYSTEM_PROMPT
    ),
    CODE_ANALYZER(
        displayName = "code-analyzer",
        systemPrompt = """
Ты — эксперт по анализу кода. Твои задачи: искать баги и уязвимости, предлагать рефакторинг, объяснять архитектурные решения, проводить code review.
При анализе указывай точные места проблем (файл:строка если известно), объясняй причину, предлагай конкретное исправление с примером кода.
Анализируй код структурно: сначала общий обзор, затем конкретные проблемы по приоритету.
В ответ всегда возвращай только JSON без пояснений и без markdown-разметки с полями:
- content — ответ, который нужно отобразить пользователю.
        """.trimIndent()
    );

    companion object {
        fun fromName(name: String): AgentMode? =
            entries.find { it.displayName.equals(name, ignoreCase = true) }
    }
}

enum class ModelConfig(
    val shortName: String,
    val description: String,
    val apiModelId: String,
    val apiKeyProperty: String,
    val url: String,
    val aliases: List<String> = emptyList()
) {
    DEEPSEEK(
        shortName = "deepseek",
        description = "Использует deepseek-v4-pro",
        apiModelId = "deepseek-v4-pro",
        apiKeyProperty = "DEEPSEEK_STUDY_API_KEY",
        url = "https://api.deepseek.com/v1/chat/completions",
        aliases = listOf("deepseek-v4-pro")
    ),
    QWEN(
        shortName = "qwen",
        description = "Исползует qwen/qwen3-235b-a22b-thinking-2507",
        apiModelId = "qwen/qwen3-235b-a22b-thinking-2507",
        apiKeyProperty = "OPENROUTER_STUDY_API_KEY",
        url = "https://openrouter.ai/api/v1/chat/completions",
        aliases = listOf("qwen3", "qwen3-235b-a22b-thinking")
    ),
    QWEN_LOW(
        shortName = "qwen-low",
        description = "Использует qwen/qwen3-8b",
        apiModelId = "qwen/qwen3-8b",
        apiKeyProperty = "OPENROUTER_STUDY_API_KEY",
        url = "https://openrouter.ai/api/v1/chat/completions",
        aliases = listOf("qwen3-8b")
    );

    companion object {
        fun fromName(name: String): ModelConfig? = entries.find { model ->
            model.shortName.equals(name, ignoreCase = true) ||
                    model.aliases.any { it.equals(name, ignoreCase = true) }
        }
    }
}
