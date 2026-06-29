package smartagent.mcp_handler

import kotlinx.serialization.json.*

object JsonRpcSerializer {
    private val json = Json { ignoreUnknownKeys = true }

    fun buildRequest(id: Int, method: String, params: JsonElement? = null): String =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            if (params != null) put("params", params)
        }.toString()

    fun buildNotification(method: String, params: JsonElement? = null): String =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            if (params != null) put("params", params)
        }.toString()

    fun parse(line: String): JsonObject? = try {
        json.parseToJsonElement(line).jsonObject
    } catch (e: Exception) {
        null
    }

    // Notifications have no "id" field — must not be replied to
    fun isNotification(obj: JsonObject): Boolean = !obj.containsKey("id")
}
