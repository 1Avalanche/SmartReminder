package smartagent.support.mcp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import smartagent.mcp_handler.McpServerConfig
import smartagent.mcp_handler.McpSession
import smartagent.mcp_handler.McpTool
import smartagent.mcp_handler.TransportMode
import smartagent.support.ticket.TicketRepository
import smartagent.support.ticket.TicketStatus
import smartagent.support.ticket.TicketType

class TicketMcpSession(private val repo: TicketRepository) : McpSession(
    name = "ticket-service",
    config = McpServerConfig(
        name = "ticket-service",
        command = emptyList(),
        transportMode = TransportMode.PROCESS,
        autoConnect = false
    )
) {

    private val json = Json { ignoreUnknownKeys = true }

    override val isConnected: Boolean = true

    override fun listTools(): List<McpTool> = TOOLS

    override fun callTool(toolName: String, arguments: Map<String, JsonElement>): JsonElement? {
        return when (toolName) {
            "create_ticket" -> handleCreateTicket(arguments)
            "get_ticket" -> handleGetTicket(arguments)
            "list_user_tickets" -> handleListUserTickets(arguments)
            "list_global_tickets" -> handleListGlobalTickets(arguments)
            "close_ticket" -> handleCloseTicket(arguments)
            else -> JsonPrimitive("Unknown tool: $toolName")
        }
    }

    private fun handleCreateTicket(args: Map<String, JsonElement>): JsonElement {
        val userId = args["userId"]?.jsonPrimitive?.longOrNull
            ?: return JsonPrimitive("Error: userId is required")
        val title = args["title"]?.jsonPrimitive?.content
            ?: return JsonPrimitive("Error: title is required")
        val description = args["description"]?.jsonPrimitive?.content ?: ""
        val typeStr = args["type"]?.jsonPrimitive?.content ?: "USER"
        val type = runCatching { TicketType.valueOf(typeStr) }.getOrDefault(TicketType.USER)

        val ticket = repo.create(userId, type, title, description)
        return buildJsonObject {
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", json.encodeToString(ticket))
                })
            })
        }
    }

    private fun handleGetTicket(args: Map<String, JsonElement>): JsonElement {
        val id = args["id"]?.jsonPrimitive?.content
            ?: return JsonPrimitive("Error: id is required")
        val ticket = repo.findById(id)
        val text = if (ticket != null) json.encodeToString(ticket) else "Ticket not found: $id"
        return buildJsonObject {
            put("content", buildJsonArray {
                add(buildJsonObject { put("type", "text"); put("text", text) })
            })
        }
    }

    private fun handleListUserTickets(args: Map<String, JsonElement>): JsonElement {
        val userId = args["userId"]?.jsonPrimitive?.longOrNull
            ?: return JsonPrimitive("Error: userId is required")
        val statusFilter = args["status"]?.jsonPrimitive?.content
        val tickets = repo.findByUserId(userId).let { list ->
            if (statusFilter != null) {
                val s = runCatching { TicketStatus.valueOf(statusFilter) }.getOrNull()
                if (s != null) list.filter { it.status == s } else list
            } else list
        }
        return buildJsonObject {
            put("content", buildJsonArray {
                add(buildJsonObject { put("type", "text"); put("text", json.encodeToString(tickets)) })
            })
        }
    }

    private fun handleListGlobalTickets(args: Map<String, JsonElement>): JsonElement {
        val statusFilter = args["status"]?.jsonPrimitive?.content
        val tickets = repo.findGlobal().let { list ->
            if (statusFilter != null) {
                val s = runCatching { TicketStatus.valueOf(statusFilter) }.getOrNull()
                if (s != null) list.filter { it.status == s } else list
            } else list
        }
        return buildJsonObject {
            put("content", buildJsonArray {
                add(buildJsonObject { put("type", "text"); put("text", json.encodeToString(tickets)) })
            })
        }
    }

    private fun handleCloseTicket(args: Map<String, JsonElement>): JsonElement {
        val id = args["id"]?.jsonPrimitive?.content
            ?: return JsonPrimitive("Error: id is required")
        val ticket = repo.close(id)
        val text = if (ticket != null) "Ticket closed: ${json.encodeToString(ticket)}" else "Ticket not found: $id"
        return buildJsonObject {
            put("content", buildJsonArray {
                add(buildJsonObject { put("type", "text"); put("text", text) })
            })
        }
    }

    companion object {
        private fun prop(vararg required: String) = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                required.forEach { name ->
                    put(name, buildJsonObject { put("type", "string") })
                }
            })
            put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
        }

        val TOOLS: List<McpTool> = listOf(
            McpTool(
                name = "create_ticket",
                description = "Create a new support ticket. Use type=USER for user-specific issues, type=GLOBAL for known app-wide problems.",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("userId", buildJsonObject { put("type", "string"); put("description", "Telegram chatId of the user") })
                        put("type", buildJsonObject { put("type", "string"); put("enum", buildJsonArray { add(JsonPrimitive("USER")); add(JsonPrimitive("GLOBAL")) }) })
                        put("title", buildJsonObject { put("type", "string"); put("description", "Short problem title") })
                        put("description", buildJsonObject { put("type", "string"); put("description", "Detailed description of the issue") })
                    })
                    put("required", buildJsonArray { add(JsonPrimitive("userId")); add(JsonPrimitive("title")) })
                }
            ),
            McpTool(
                name = "get_ticket",
                description = "Retrieve a ticket by its ID.",
                inputSchema = prop("id")
            ),
            McpTool(
                name = "list_user_tickets",
                description = "List all USER tickets for a specific Telegram user. Optionally filter by status (OPEN or CLOSED).",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("userId", buildJsonObject { put("type", "string"); put("description", "Telegram chatId") })
                        put("status", buildJsonObject { put("type", "string"); put("enum", buildJsonArray { add(JsonPrimitive("OPEN")); add(JsonPrimitive("CLOSED")) }) })
                    })
                    put("required", buildJsonArray { add(JsonPrimitive("userId")) })
                }
            ),
            McpTool(
                name = "list_global_tickets",
                description = "List all GLOBAL tickets (known app-wide issues). Optionally filter by status (OPEN or CLOSED).",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("status", buildJsonObject { put("type", "string"); put("enum", buildJsonArray { add(JsonPrimitive("OPEN")); add(JsonPrimitive("CLOSED")) }) })
                    })
                }
            ),
            McpTool(
                name = "close_ticket",
                description = "Close an existing ticket by ID.",
                inputSchema = prop("id")
            )
        )
    }
}
