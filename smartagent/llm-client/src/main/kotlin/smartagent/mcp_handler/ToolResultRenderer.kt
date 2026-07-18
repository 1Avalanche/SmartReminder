package smartagent.mcp_handler

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val prettyJson = Json { prettyPrint = true }

private val BASE64_BLOB = Regex("""[A-Za-z0-9+/\n]{200,}={0,2}""")

private fun stripBase64(text: String): String =
    BASE64_BLOB.replace(text) { "[base64, ${it.value.replace("\n", "").length} байт, пропущено]" }

/**
 * Renders a MCP tools/call result element as human-readable text.
 *
 * Handles three formats:
 * 1. MCP standard: { "content": [{"type": "text", "text": "..."}], "isError": false }
 * 2. Non-standard: { "text": "..." } — some servers return this directly
 * 3. Fallback: prettyJson of whole result
 *
 * Text that is itself valid JSON (object/array) is pretty-printed.
 * isError=true is indicated with "[error]" prefix.
 * Base64 blobs (200+ chars) are replaced with a size placeholder.
 */
fun renderToolResult(result: JsonElement): String {
    val obj = result.jsonObject
    val contentArray = obj["content"]?.jsonArray

    if (contentArray == null) {
        // Non-standard format: server returns {"text": "..."} directly
        val directText = obj["text"]?.jsonPrimitive?.content
        if (directText != null) return stripBase64(tryPrettyJson(directText))
        return stripBase64(prettyJson.encodeToString(JsonElement.serializer(), result))
    }

    if (contentArray.isEmpty()) return ""

    val isError = obj["isError"]?.jsonPrimitive?.content == "true"
    val prefix = if (isError) "[error] " else ""

    val parts = contentArray.map { item ->
        val itemObj = item.jsonObject
        val type = itemObj["type"]?.jsonPrimitive?.content
        when {
            type == "text" -> {
                val text = itemObj["text"]?.jsonPrimitive?.content ?: ""
                prefix + stripBase64(tryPrettyJson(text))
            }
            type == "image" -> prefix + "[бинарное содержимое, пропущено]"
            else -> stripBase64(prettyJson.encodeToString(JsonElement.serializer(), item))
        }
    }

    return parts.joinToString("\n---\n")
}

private fun tryPrettyJson(text: String): String {
    if (text.isBlank()) return text
    return try {
        val parsed = Json.parseToJsonElement(text)
        // Only pretty-print objects/arrays, not bare primitives like "true" or "42"
        if (parsed is JsonPrimitive) text
        else prettyJson.encodeToString(JsonElement.serializer(), parsed)
    } catch (_: Exception) {
        text
    }
}
