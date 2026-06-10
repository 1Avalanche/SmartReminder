package smartagent

import java.io.File

internal object Config {
    private val home = System.getProperty("user.home")
    private val configFile = File("$home/.config/smartagent/local.properties")
    private val lastModelFile = File("$home/.config/smartreminder/last_model")

    fun saveLastModel(model: ModelConfig) {
        lastModelFile.parentFile?.mkdirs()
        lastModelFile.writeText(model.shortName)
    }

    fun loadLastModel(): ModelConfig? = try {
        ModelConfig.fromName(lastModelFile.readText().trim())
    } catch (_: Exception) { null }

    val localProperties: Map<String, String> by lazy {
        val props = mutableMapOf<String, String>()
        val file = listOf(
            "local.properties",
            "../local.properties",
            configFile.absolutePath
        ).firstOrNull { File(it).exists() }?.let { File(it) }
        file?.readLines()?.forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotBlank() && !trimmed.startsWith("#")) {
                val eq = trimmed.indexOf('=')
                if (eq > 0) props[trimmed.substring(0, eq).trim()] = trimmed.substring(eq + 1).trim()
            }
        }
        props
    }

    fun apiKey(model: ModelConfig): String? =
        localProperties[model.apiKeyProperty]
            ?: System.getenv(model.apiKeyProperty)
            ?: System.getenv(model.apiKeyProperty.replace("_STUDY_", "_"))

    fun hasAnyKey(): Boolean = ModelConfig.entries.any { apiKey(it) != null }

    fun saveKey(property: String, value: String) {
        configFile.parentFile?.mkdirs()
        val lines = if (configFile.exists()) configFile.readLines().toMutableList() else mutableListOf()
        val idx = lines.indexOfFirst { it.startsWith("$property=") }
        if (idx >= 0) lines[idx] = "$property=$value" else lines += "$property=$value"
        configFile.writeText(lines.joinToString("\n"))
    }
}
