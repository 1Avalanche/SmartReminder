package smartagent.agent.toolcalling

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject

/**
 * Manages chat_id injection for MCP tool calls.
 *
 * chat_id is ONLY valid for tools that explicitly require it (internal/Telegram tools).
 * All external tools (e.g. Tavily) must NOT receive chat_id.
 */
object ChatIdInjector {

    val allowedTools: Set<String> = setOf(
        "create_reminder",
        "list_pending_reminders",
        "get_reminder_summary",
        "debug_reminders",
        "telegram_send_message"
    )

    /**
     * Injects chat_id into [args] if [toolName] is in [allowedTools] and [chatId] is non-null.
     * Returns true if chat_id was injected.
     */
    fun enrich(toolName: String, args: MutableMap<String, String>, chatId: Long?): Boolean {
        if (chatId == null) return false
        if (toolName !in allowedTools) return false
        if ("chat_id" in args) return false
        args["chat_id"] = chatId.toString()
        return true
    }

    /**
     * Strips keys from [args] that are not declared in the tool's inputSchema properties.
     * No-ops if schema is null or has no properties — unknown schema means no stripping.
     */
    fun stripUnknownArgs(inputSchema: JsonElement?, args: Map<String, String>): Map<String, String> {
        val properties = inputSchema?.jsonObject?.get("properties")?.jsonObject ?: return args
        return args.filter { (key, _) -> key in properties }
    }
}
