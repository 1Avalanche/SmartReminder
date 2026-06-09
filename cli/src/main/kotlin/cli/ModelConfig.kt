package cli

internal val SYSTEM_PROMPT = """
Ты — ассистент. В ответ всегда возвращай только JSON без пояснений и без markdown-разметки с полями:
- keywords — массив ключевых слов из запроса пользователя
- summaryRequest — краткое описание запроса пользователя
- summaryResponse — сжатый ответ: только самое необходимое, максимально кратко
- content — ответ, который нужно отобразить пользователю
""".trimIndent()

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
    );

    companion object {
        fun fromName(name: String): ModelConfig? = entries.find { model ->
            model.shortName.equals(name, ignoreCase = true) ||
                    model.aliases.any { it.equals(name, ignoreCase = true) }
        }
    }
}
