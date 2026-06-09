package cli

import java.io.File

internal object Config {
    private val lastModelFile = File(System.getProperty("user.home"), ".config/smartreminder/last_model")

    fun saveLastModel(model: ModelConfig) {
        lastModelFile.parentFile?.mkdirs()
        lastModelFile.writeText(model.shortName)
    }

    fun loadLastModel(): ModelConfig? = try {
        ModelConfig.fromName(lastModelFile.readText().trim())
    } catch (_: Exception) { null }

    val localProperties: Map<String, String> by lazy {
        val props = mutableMapOf<String, String>()
        val file = listOf("local.properties", "../local.properties")
            .firstOrNull { File(it).exists() }
            ?.let { File(it) }
        file?.readLines()?.forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotBlank() && !trimmed.startsWith("#")) {
                val eq = trimmed.indexOf('=')
                if (eq > 0) {
                    props[trimmed.substring(0, eq).trim()] = trimmed.substring(eq + 1).trim()
                }
            }
        }
        props
    }

    fun apiKey(model: ModelConfig): String? =
        localProperties[model.apiKeyProperty]
            ?: System.getenv(model.apiKeyProperty)
            ?: System.getenv(model.apiKeyProperty.replace("_STUDY_", "_"))
}
