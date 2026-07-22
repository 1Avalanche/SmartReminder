package smartagent

enum class ModelConfig(
    val shortName: String,
    val description: String,
    val apiModelId: String,
    val apiKeyProperty: String,
    val url: String,
    val aliases: List<String> = emptyList(),
    val isLocal: Boolean = false,
    val urlProperty: String = "",
    val temperature: Double? = null,
    val reasoningEffort: String? = null
) {
    DeepSeekFlash(
        shortName = "deepseek",
        description = "corporate model: deepseek-v4-flash",
        apiModelId = "deepseek-v4-flash",
        apiKeyProperty = "GPU_STACK_API_KEY",
        url = "",
        aliases = listOf("deepseek-v4-flash, deepseek"),
        urlProperty = "GPU_STACK_URL",
        temperature = 0.1,
        reasoningEffort = "high"
    ),
    Qwen(
        shortName = "qwen",
        description = "corporate model: qwen3.5-397b-a17b",
        apiModelId = "qwen3.5-397b-a17b",
        apiKeyProperty = "GPU_STACK_API_KEY",
        url = "",
        aliases = listOf("qwen3.5-397b-a17b, qwen"),
        urlProperty = "GPU_STACK_URL",
        temperature = 0.2,
        reasoningEffort = null
    );

    companion object {
        fun fromName(name: String): ModelConfig? = entries.find { model ->
            model.shortName.equals(name, ignoreCase = true) ||
                    model.aliases.any { it.equals(name, ignoreCase = true) }
        }
    }
}
