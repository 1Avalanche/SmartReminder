package smartagent.telegram.api

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import smartagent.ModelConfig
import smartagent.agent.assist.AssistOrchestrator
import smartagent.telegram.push.TelegramPushHandler
import smartagent.telegram.review.TelegramReviewHandler
import java.net.InetSocketAddress

class HttpApiServer(
    private val assistOrchestrator: AssistOrchestrator,
    private val reviewHandler: TelegramReviewHandler,
    private val pushHandler: TelegramPushHandler,
    private val model: ModelConfig,
    private val apiKey: String,
    private val port: Int = 8080,
    private val sendTelegram: ((Long, String) -> Unit)? = null
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class ErrorResponse(val error: String)

    fun start() {
        val server = HttpServer.create(InetSocketAddress(port), 0)
        server.createContext("/api/message") { exchange -> handleMessage(exchange) }
        server.executor = null
        server.start()
        println("[HttpApi] Listening on port $port")
    }

    private fun handleMessage(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") {
            respond(exchange, 405, json.encodeToString(ErrorResponse("method not allowed")))
            return
        }

        val key = exchange.requestHeaders.getFirst("X-Api-Key")
        if (key != apiKey) {
            respond(exchange, 401, json.encodeToString(ErrorResponse("unauthorized")))
            return
        }

        val body = runCatching { exchange.requestBody.bufferedReader().readText() }.getOrElse { "" }
        val parsed = runCatching { json.decodeFromString<JsonObject>(body) }.getOrNull()
        val text = parsed?.get("text")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        if (text == null) {
            respond(exchange, 400, json.encodeToString(ErrorResponse("missing text")))
            return
        }
        val chatId = parsed["chatId"]?.jsonPrimitive?.longOrNull ?: 0L

        Thread {
            when {
                text.startsWith("/review") -> {
                    val parsed = reviewHandler.parseCommand(text)
                    if (parsed == null) {
                        println("[HttpApi] Review parse error: ${reviewHandler.parseErrorMessage(text)}")
                    } else {
                        reviewHandler.runAndPublish(parsed.owner, parsed.repo, parsed.prNumber)
                            .onSuccess { result ->
                                if (chatId != 0L) {
                                    val msg = reviewHandler.formatTelegramSummary(result)
                                    runCatching { sendTelegram?.invoke(chatId, msg) }
                                        .onFailure { e -> println("[HttpApi] Telegram send error: ${e.message}") }
                                }
                            }
                            .onFailure { e -> println("[HttpApi] Review error: ${e.message}") }
                    }
                }
                text.startsWith("/push") -> {
                    val parsed = pushHandler.parseCommand(text)
                    if (parsed == null) {
                        println("[HttpApi] Push parse error: ${pushHandler.parseErrorMessage(text)}")
                    } else {
                        pushHandler.handle(parsed)
                            .onSuccess { msg -> println("[HttpApi] Push handled: $msg") }
                            .onFailure { e -> println("[HttpApi] Push error: ${e.message}") }
                    }
                }
                else -> {
                    runCatching { assistOrchestrator.handle(query = text, model = model, chatId = chatId) }
                        .onFailure { e -> println("[HttpApi] Agent error: ${e.message}") }
                }
            }
        }.also { it.isDaemon = true }.start()

        respond(exchange, 200, """{"requestHandled":true}""")
    }

    private fun respond(exchange: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
