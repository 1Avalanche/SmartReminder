package smartagent

val CHAT_BASE_PROMPT = """
    Ты - персональный ассистент. Твоя задача - помочь пользователю решить любую его задачу. 
    Отвечай по делу, не фантазируй. Предоставляй порядок своих рассуждений.
""".trimIndent()

@kotlinx.serialization.Serializable
enum class AgentMode(val displayName: String, val basePrompt: String) {
    CHAT(
        displayName = "chat",
        basePrompt = CHAT_BASE_PROMPT
    ),
    CODE_ANALYZER(
        displayName = "code-analyzer",
        basePrompt = """
Ты — эксперт по анализу кода. Твои задачи: искать баги и уязвимости, предлагать рефакторинг, объяснять архитектурные решения, проводить code review.
При анализе указывай точные места проблем (файл:строка если известно), объясняй причину, предлагай конкретное исправление с примером кода.
Анализируй код структурно: сначала общий обзор, затем конкретные проблемы по приоритету.
        """.trimIndent()
    ),
    ARCHITECT(
        displayName = "architect",
        basePrompt = "Ты — архитектор программного обеспечения. Помогаешь проектировать системы и принимать архитектурные решения."
    ),
    ASSIST(
        displayName = "assist",
        basePrompt = ""   // assist mode talks to MCP servers, not the LLM
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
    val contextWindow: Int,
    val aliases: List<String> = emptyList()
) {
    DEEPSEEK(
        shortName = "deepseek",
        description = "Использует deepseek-v4-pro",
        apiModelId = "deepseek-v4-pro",
        apiKeyProperty = "DEEPSEEK_STUDY_API_KEY",
        url = "https://api.deepseek.com/v1/chat/completions",
        contextWindow = 1_000_000,
        aliases = listOf("deepseek-v4-pro")
    ),
    QWEN(
        shortName = "qwen",
        description = "Исползует qwen/qwen3.7-plus",
        apiModelId = "qwen/qwen3.7-plus",
        apiKeyProperty = "OPENROUTER_STUDY_API_KEY",
        url = "https://openrouter.ai/api/v1/chat/completions",
        contextWindow = 1_000_000,
        aliases = listOf("qwen3", "qwen3.7-plus")
    ),
    QWEN_LOW(
        shortName = "qwen-low",
        description = "Использует qwen/qwen3-8b",
        apiModelId = "qwen/qwen3-8b",
        apiKeyProperty = "OPENROUTER_STUDY_API_KEY",
        url = "https://openrouter.ai/api/v1/chat/completions",
        contextWindow = 131_000,
        aliases = listOf("qwen3-8b")
    );

    companion object {
        fun fromName(name: String): ModelConfig? = entries.find { model ->
            model.shortName.equals(name, ignoreCase = true) ||
                    model.aliases.any { it.equals(name, ignoreCase = true) }
        }
    }
}
