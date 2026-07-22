package smartagent

enum class ModelConfig(
    val shortName: String,
    val description: String,
    val apiModelId: String,
    val apiKeyProperty: String,
    val url: String,
    val contextWindow: Int,
    val aliases: List<String> = emptyList(),
    val isLocal: Boolean = false,
    val urlProperty: String = "",
    val temperature: Double? = null,
    val reasoningEffort: String? = null
) {
    CORPORATE(
        shortName = "corporate",
        description = "corporate model",
        apiModelId = "qwen3.5-397b-a17b",
        apiKeyProperty = "GPU_STACK_API_KEY",
        url = "",
        contextWindow = 1_000_000,
        aliases = listOf("corporate"),
        urlProperty = "GPU_STACK_URL",
        temperature = 0.2,
        reasoningEffort = "high"
    );

    companion object {
        fun fromName(name: String): ModelConfig? = entries.find { model ->
            model.shortName.equals(name, ignoreCase = true) ||
                    model.aliases.any { it.equals(name, ignoreCase = true) }
        }
    }
}
