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
import java.net.InetSocketAddress

class HttpApiServer(
    private val assistOrchestrator: AssistOrchestrator,
    private val model: ModelConfig,
    private val apiKey: String,
    private val port: Int = 8080
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
            runCatching { assistOrchestrator.handle(query = text, model = model, chatId = chatId) }
                .onFailure { e -> println("[HttpApi] Agent error: ${e.message}") }
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
