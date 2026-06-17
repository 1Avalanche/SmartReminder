package smartagent

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
internal data class LogEntry(
    val userInput: String,
    val requestPayload: String,
    val apiResponse: String,
    val id: String = java.util.UUID.randomUUID().toString()
)

internal object NetworkLogger {
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    val logFile: File by lazy {
        val path = listOf("cli/network.log", "network.log")
            .firstOrNull { File(it).parentFile?.exists() ?: false }
            ?: "network.log"
        File(path)
    }

    fun log(
        url: String,
        reqHeaders: Map<String, String>,
        reqBody: String,
        statusCode: Int,
        resHeaders: Map<String, String>,
        resBody: String,
        source: String = "[MAIN_AGENT]"
    ) {
        val sb = StringBuilder()
        sb.appendLine(source)
        sb.appendLine("=== ${LocalDateTime.now().format(timestampFormatter)} ===")
        sb.appendLine("URL: $url")
        sb.appendLine("--- Request headers ---")
        reqHeaders.forEach { (k, v) ->
            sb.appendLine("  $k: ${if (k.equals("Authorization", true)) maskAuth(v) else v}")
        }
        sb.appendLine("--- Request body ---")
        sb.appendLine(prettyFormat(reqBody))
        sb.appendLine("--- Response: $statusCode ---")
        sb.appendLine("--- Response headers ---")
        resHeaders.forEach { (k, v) -> sb.appendLine("  $k: $v") }
        sb.appendLine("--- Response body ---")
        sb.appendLine(prettyFormat(resBody))
        sb.appendLine("========================================")
        sb.appendLine()
        logFile.appendText(sb.toString())
    }

    fun logRequest(
        source: String,
        url: String,
        reqHeaders: Map<String, String>,
        reqBody: String,
        statusCode: Int,
        resHeaders: Map<String, String>,
        resBody: String,
        durationMs: Long,
        usage: Usage? = null
    ) {
        val sb = StringBuilder()
        sb.appendLine(source)
        sb.appendLine("=== ${LocalDateTime.now().format(timestampFormatter)} ===")
        sb.appendLine("URL: $url  [${durationMs}ms]")
        if (usage != null) {
            sb.appendLine("Tokens: prompt=${usage.prompt_tokens} completion=${usage.completion_tokens} total=${usage.total_tokens}")
        }
        sb.appendLine("--- Request headers ---")
        reqHeaders.forEach { (k, v) ->
            sb.appendLine("  $k: ${if (k.equals("Authorization", true)) maskAuth(v) else v}")
        }
        sb.appendLine("--- Request body ---")
        sb.appendLine(prettyFormat(reqBody))
        sb.appendLine("--- Response: $statusCode ---")
        sb.appendLine("--- Response headers ---")
        resHeaders.forEach { (k, v) -> sb.appendLine("  $k: $v") }
        sb.appendLine("--- Response body ---")
        sb.appendLine(prettyFormat(resBody))
        sb.appendLine("========================================")
        sb.appendLine()
        logFile.appendText(sb.toString())
    }

    fun logEvent(source: String, message: String) {
        val ts = LocalDateTime.now().format(timestampFormatter)
        logFile.appendText("$source\n=== $ts ===\n$message\n========================================\n\n")
    }

    fun clear() = logFile.writeText("")

    private fun maskAuth(header: String) =
        header.replace(Regex("Bearer \\S+"), "Bearer ***")

    private fun prettyFormat(s: String): String = try {
        prettyJson.encodeToString(prettyJson.parseToJsonElement(s))
    } catch (_: Exception) { s }
}

