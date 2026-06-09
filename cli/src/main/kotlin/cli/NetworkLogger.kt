package cli

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

internal data class LogEntry(
    val userInput: String,
    val requestPayload: String,
    val apiResponse: String
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
        resBody: String
    ) {
        val sb = StringBuilder()
        sb.appendLine("=== ${LocalDateTime.now().format(timestampFormatter)} ===")
        sb.appendLine("URL: $url")
        sb.appendLine("--- Request headers ---")
        reqHeaders.forEach { (k, v) ->
            sb.appendLine("  $k: ${if (k.equals("Authorization", true)) maskAuth(v) else v}")
        }
        sb.appendLine("--- Request body ---")
        sb.appendLine(reqBody.replace("{", "\n{").replace("[", "\n[").replace("}", "\n}").replace("]", "\n]"))
        sb.appendLine("--- Response: $statusCode ---")
        sb.appendLine("--- Response headers ---")
        resHeaders.forEach { (k, v) -> sb.appendLine("  $k: $v") }
        sb.appendLine("--- Response body ---")
        sb.appendLine(resBody)
        sb.appendLine("========================================")
        sb.appendLine()
        logFile.appendText(sb.toString())
    }

    fun clear() = logFile.writeText("")

    private fun maskAuth(header: String) =
        header.replace(Regex("Bearer sk-[A-Za-z0-9]+"), "Bearer sk-***")
}
