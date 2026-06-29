package smartagent.mcp_handler

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ToolParam(val name: String, val type: String, val required: Boolean)

/** Parses a JSON Schema `inputSchema` object into a flat parameter list. */
fun parseSchemaParams(schema: JsonElement?): List<ToolParam> {
    val obj = schema?.jsonObject ?: return emptyList()
    val properties = obj["properties"]?.jsonObject ?: return emptyList()
    val required = obj["required"]?.jsonArray
        ?.mapNotNull { it.jsonPrimitive.content }
        ?.toSet() ?: emptySet()

    return properties.entries.map { (name, def) ->
        val type = def.jsonObject["type"]?.jsonPrimitive?.content ?: "any"
        ToolParam(name = name, type = type, required = name in required)
    }
}

/**
 * Renders inputSchema as human-readable parameter lines.
 * Returns empty list when schema is null or has no properties.
 */
fun renderToolSchema(schema: JsonElement?): List<String> {
    val params = parseSchemaParams(schema)
    if (params.isEmpty()) return emptyList()
    return params.map { p ->
        val req = if (p.required) "required" else "optional"
        "- ${p.name} (${p.type}, $req)"
    }
}
