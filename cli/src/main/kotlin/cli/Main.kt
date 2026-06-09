package cli

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

private object Colors {
    const val RESET = "\u001B[0m"
    const val BRIGHT_GREEN = "\u001B[92m"
    const val LIGHT_GREEN = "\u001B[38;5;158m"
    const val LIGHT_YELLOW = "\u001B[38;5;230m"
    const val DARK_GRAY = "\u001B[90m"
    const val LIGHT_GRAY = "\u001B[90m"
    const val BRIGHT_WHITE = "\u001B[97m"
    const val LIGHT_VIOLET = "\u001B[38;5;183m"
}

@Serializable
data class Message(val role: String, val content: String)

@Serializable
data class ChatRequest(val model: String, val messages: List<Message>)

@Serializable
data class Choice(val message: Message, val index: Int = 0)

@Serializable
data class ChatResponse(val choices: List<Choice>)

@Serializable
data class StructuredResponse(
    val keywords: List<String> = emptyList(),
    val summary: String = "",
    val content: String = ""
)

private val SYSTEM_PROMPT = """
Ты — ассистент. В ответ всегда возвращай только JSON без пояснений и без markdown-разметки с полями:
- keywords — массив ключевых слов из запроса пользователя
- summary — краткое описание запроса пользователя
- content — ответ, который нужно отобразить пользователю
""".trimIndent()

enum class ModelConfig(
    val shortName: String,
    val description: String,
    val apiModelId: String,
    val apiKeyProperty: String,
    val url: String
) {
    DEEPSEEK(
        shortName = "deepseek",
        description = "Использует deepseek-v4-pro",
        apiModelId = "deepseek-v4-pro",
        apiKeyProperty = "DEEPSEEK_STUDY_API_KEY",
        url = "https://api.deepseek.com/v1/chat/completions"
    ),
    QWEN(
        shortName = "qwen",
        description = "Исползует qwen/qwen3-235b-a22b-thinking-2507",
        apiModelId = "qwen/qwen3-235b-a22b-thinking-2507",
        apiKeyProperty = "QWEN_STUDY_API_KEY",
        url = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
    );
}

private val json = Json { ignoreUnknownKeys = true }

private val client = OkHttpClient.Builder()
    .connectTimeout(60, TimeUnit.SECONDS)
    .readTimeout(120, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .build()

private var currentModel = ModelConfig.DEEPSEEK
private val messages = mutableListOf(Message("system", SYSTEM_PROMPT))

private data class LogEntry(
    val userInput: String,
    val requestPayload: String,
    val apiResponse: String
)
private val historyLog = mutableListOf<LogEntry>()

private val networkLogFile: File by lazy {
    val path = listOf("cli/network.log", "network.log")
        .firstOrNull { java.io.File(it).parentFile?.exists() ?: false }
        ?: "network.log"
    File(path)
}

private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

private fun maskAuth(header: String): String {
    return header.replace(Regex("Bearer sk-[A-Za-z0-9]+"), "Bearer sk-***")
}

private fun logNetworkCall(
    url: String,
    requestHeaders: Map<String, String>,
    requestBody: String,
    statusCode: Int,
    responseHeaders: Map<String, String>,
    responseBody: String
) {
    val sb = StringBuilder()
    sb.appendLine("=== ${LocalDateTime.now().format(timestampFormatter)} ===")
    sb.appendLine("URL: $url")
    sb.appendLine("--- Request headers ---")
    requestHeaders.forEach { (k, v) -> sb.appendLine("  $k: ${if (k.equals("Authorization", true)) maskAuth(v) else v}" ) }
    sb.appendLine("--- Request body ---")
    sb.appendLine(requestBody
        .replace("{", "\n{")
        .replace("[", "\n[")
        .replace("}", "\n}")
        .replace("]", "\n]")
    )
    sb.appendLine("--- Response: $statusCode ---")
    sb.appendLine("--- Response headers ---")
    responseHeaders.forEach { (k, v) -> sb.appendLine("  $k: $v") }
    sb.appendLine("--- Response body ---")
    sb.appendLine(responseBody)
    sb.appendLine("========================================")
    sb.appendLine()

    networkLogFile.appendText(sb.toString())
}

private val localProperties: Map<String, String> by lazy {
    val props = mutableMapOf<String, String>()
    val file = listOf("local.properties", "../local.properties")
        .firstOrNull { java.io.File(it).exists() }
        ?.let { java.io.File(it) }
    if (file != null) {
        file.readLines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotBlank() && !trimmed.startsWith("#")) {
                val eq = trimmed.indexOf('=')
                if (eq > 0) {
                    val key = trimmed.substring(0, eq).trim()
                    val value = trimmed.substring(eq + 1).trim()
                    props[key] = value
                }
            }
        }
    }
    props
}

private fun apiKey(model: ModelConfig): String? {
    return localProperties[model.apiKeyProperty]
        ?: System.getenv(model.apiKeyProperty)
        ?: System.getenv(model.apiKeyProperty.replace("_STUDY_", "_"))
}

fun main(args: Array<String>) {
    var modelArg: String? = null

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--model", "-m" -> {
                if (i + 1 < args.size) {
                    modelArg = args[++i]
                } else {
                    println("Error: --model requires a value")
                    return
                }
            }
            "--help", "-h" -> {
                printHelp()
                return
            }
            else -> {
                println("Unknown option: ${args[i]}")
                printHelp()
                return
            }
        }
        i++
    }

    if (modelArg != null) {
        currentModel = when (modelArg.lowercase()) {
            "deepseek", "deepseek-v4-pro" -> ModelConfig.DEEPSEEK
            "qwen", "qwen3", "qwen3-235b-a22b-thinking" -> ModelConfig.QWEN
            else -> {
                println("Unknown model: $modelArg. Available: deepseek, qwen")
                return
            }
        }
    }

    println("${Colors.BRIGHT_GREEN}ChatAgent готов к работе!${Colors.RESET}")
    println("${Colors.DARK_GRAY}Model: ${currentModel.shortName}")
    println("Type /help for commands, /exit to quit.${Colors.RESET}")
    println()

    while (true) {
        print("> ")
        System.out.flush()
        val input = readlnOrNull() ?: break

        when {
            input.isBlank() -> continue
            input == "/exit" || input == "/quit" -> {
                println("Goodbye!")
                break
            }
            input == "/help" -> { showHelp(); println() }
            input == "/history" || input == "/hist" -> showHistory()
            input == "/clear" -> {
                messages.clear()
                messages.add(Message("system", SYSTEM_PROMPT))
                historyLog.clear()
                networkLogFile.writeText("")
                println("Chat history cleared.")
            }
            input == "/models" -> {
                println(Colors.LIGHT_YELLOW + "Available models names:" + Colors.RESET)
                ModelConfig.entries.forEachIndexed { index, model ->
                    val check = if (model == currentModel) "✅ " else ""
                    println(Colors.LIGHT_YELLOW + "  ${index + 1}. $check${model.shortName}" + Colors.RESET)
                    println(Colors.LIGHT_GRAY + "     ${model.description}" + Colors.RESET)
                }
                println()
            }
            input.startsWith("/model ") -> {
                val name = input.removePrefix("/model ").trim()
                val found = ModelConfig.entries.find {
                    it.shortName.equals(name, ignoreCase = true)
                }
                if (found != null) {
                    currentModel = found
                    println("Switched to model: ${currentModel.shortName}")
                } else {
                    println("Unknown model: $name. Type /models to see available models.")
                }
            }
            input.startsWith("/") -> println("Unknown command: $input")
            else -> sendMessage(input)
        }
    }
}

private fun showHelp() {
    println(Colors.LIGHT_YELLOW + """
Commands:
  /exit, /quit           Exit the program
  /help                  Show this help
  /history, /hist       Show full chat history (JSON)
  /clear                 Clear chat history
  /models                List available models
  /model <name>          Switch model (deepseek, qwen)
  <message>              Send a message to the current model
    """.trimIndent() + Colors.RESET)
}

private fun showHistory() {
    if (historyLog.isEmpty()) {
        println("Chat history is empty.")
        return
    }
    historyLog.forEachIndexed { i, entry ->
        println("--- Exchange ${i + 1} ---")
        println("${Colors.LIGHT_GREEN}Пользователь ввел:${Colors.RESET} ${entry.userInput}")
        println("${Colors.LIGHT_YELLOW}В запрос ушло:${Colors.RESET} ${entry.requestPayload}")
        println("${Colors.LIGHT_VIOLET}Ответ от API:${Colors.RESET} ${entry.apiResponse}")
        println()
    }
}

private fun printHelp() {
    println("""
Usage: cli [options]

Options:
  -m, --model <model>   Model to use (default: deepseek)
                         Available: deepseek, qwen
  -h, --help            Show this help
    """.trimIndent())
}

private fun parseResponse(raw: String): Pair<String, StructuredResponse?> {
    val trimmed = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    return try {
        val parsed = json.decodeFromString<StructuredResponse>(trimmed)
        Pair(parsed.content, parsed)
    } catch (_: Exception) {
        Pair(raw, null)
    }
}

private fun buildContext(): List<Message> {
    val lastTen = historyLog.takeLast(10)
        .mapNotNull { entry ->
            try {
                json.decodeFromString<StructuredResponse>(entry.apiResponse)
            } catch (_: Exception) { null }
        }
    if (lastTen.isEmpty()) return emptyList()

    val keywords = lastTen.flatMap { it.keywords }.distinct().joinToString(", ")
    val summaries = lastTen.joinToString("; ") { it.summary }
    return listOf(
        Message("assistant", "keywords: $keywords"),
        Message("assistant", "ранее пользователь спрашивал о: $summaries")
    )
}

private fun sendMessage(text: String) {
    val apiKey = apiKey(currentModel)
    if (apiKey.isNullOrBlank()) {
        println("Error: ${currentModel.apiKeyProperty} not found in local.properties or environment")
        return
    }

    val done = AtomicBoolean(false)
    val frames = listOf("|", "/", "-", "\\")
    val loadingThread = thread(isDaemon = true) {
        var idx = 0
        while (!done.get()) {
            print("\rдумаю ${frames[idx]}")
            System.out.flush()

            idx = (idx + 1) % frames.size
            Thread.sleep(100)
        }
    }


    var requestBody = ""
    try {
        val fullMessages = listOf(Message("system", SYSTEM_PROMPT)) + buildContext() + Message("user", text)
        requestBody = json.encodeToString(ChatRequest(currentModel.apiModelId, fullMessages))
        val request = Request.Builder()
            .url(currentModel.url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
//        done.set(true)
//        loadingThread.join(1000)
//        System.err.print("\r\u001B[K")
//        System.err.flush()

        val body = response.body?.string()
        val reqHeaders = buildMap { request.headers.forEach { (name, value) -> put(name, value) } }
        val resHeaders = buildMap { response.headers.forEach { (name, value) -> put(name, value) } }

        if (!response.isSuccessful) {
            logNetworkCall(currentModel.url, reqHeaders, requestBody, response.code, resHeaders, body ?: "")
            println("Error ${response.code}: ${body ?: "Unknown error"}")
            historyLog.add(LogEntry(text, requestBody, body ?: "HTTP ${response.code}"))
            return
        }

        val chatResponse = json.decodeFromString<ChatResponse>(body!!)
        val reply = chatResponse.choices.firstOrNull()?.message?.content ?: ""

        if (reply.isBlank()) {
            logNetworkCall(currentModel.url, reqHeaders, requestBody, response.code, resHeaders, body)
            println("Warning: empty response from the model")
            historyLog.add(LogEntry(text, requestBody, body))
        } else {
            val (displayText, structured) = parseResponse(reply)
            val responseForLog = structured?.let { json.encodeToString(it) } ?: reply
            logNetworkCall(currentModel.url, reqHeaders, requestBody, response.code, resHeaders, responseForLog)

            done.set(true)
            loadingThread.join(100)
            System.err.print("\r\u001B[K")
            System.err.flush()

            println("\n${Colors.LIGHT_VIOLET}${displayText}${Colors.RESET}")
            println()
            historyLog.add(LogEntry(text, requestBody, responseForLog))
            messages.add(Message("user", text))
        }
    } catch (e: Exception) {
        done.set(true)
        loadingThread.join(1000)
        System.err.print("\r\u001B[K")
        System.err.flush()
        logNetworkCall(currentModel.url, emptyMap(), requestBody, 0, emptyMap(), "Error: ${e.message}")
        println("Error: ${e.message}")
        historyLog.add(LogEntry(text, requestBody, "Error: ${e.message}"))
    }
}
