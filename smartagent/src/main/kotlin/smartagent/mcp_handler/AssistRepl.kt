package smartagent.mcp_handler

import kotlinx.serialization.json.Json
import smartagent.Colors

/**
 * Handles /mcp commands. Available in any mode.
 * Called from Main.kt with the leading "/" already stripped.
 * Understands: mcp list | mcp <name> init | mcp <name> tools | mcp <name> stop
 *              mcp <name> tool <tool_name> [key=value ...]
 */
object AssistRepl {

    private val prettyJson = Json { prettyPrint = true }

    fun handle(input: String) {
        val tokens = input.trim().split(Regex("\\s+"))
        if (tokens.isEmpty() || tokens[0].lowercase() != "mcp") {
            hint(); return
        }
        when {
            tokens.size == 2 && tokens[1] == "list"  -> cmdList()
            tokens.size == 3 && tokens[2] == "init"  -> cmdInit(tokens[1])
            tokens.size == 3 && tokens[2] == "tools" -> cmdTools(tokens[1])
            tokens.size == 3 && tokens[2] == "stop"  -> cmdStop(tokens[1])
            tokens.size >= 4 && tokens[2] == "tool"  -> cmdTool(tokens[1], tokens[3], tokens.drop(4))
            else -> hint()
        }
    }

    // ─── Commands ─────────────────────────────────────────────────────────────

    private fun cmdList() {
        val servers = McpManager.allServers
        if (servers.isEmpty()) {
            println("${Colors.DARK_GRAY}No MCP servers registered.${Colors.RESET}")
            return
        }
        println()
        println("${Colors.LIGHT_YELLOW}Available MCP servers:${Colors.RESET}")
        println()
        servers.forEach { cfg ->
            val statusTag = if (McpManager.isConnected(cfg.name)) {
                "${Colors.LIGHT_GREEN}connected${Colors.RESET}"
            } else {
                "${Colors.DARK_GRAY}disconnected${Colors.RESET}"
            }
            val transportTag = "${Colors.DARK_GRAY}${cfg.transportMode.name.lowercase()}${Colors.RESET}"
            println("  ${Colors.BRIGHT_WHITE}${cfg.name}${Colors.RESET}  ($statusTag, $transportTag)")
            val detail = when (cfg.transportMode) {
                TransportMode.PROCESS -> "cmd: ${cfg.command.joinToString(" ")}"
                TransportMode.HTTP    -> "url: ${cfg.httpUrl}"
            }
            println("  ${Colors.DARK_GRAY}$detail${Colors.RESET}")
            println()
        }
    }

    private fun cmdInit(name: String) {
        if (McpManager.isConnected(name)) {
            println("${Colors.LIGHT_GREEN}$name already connected.${Colors.RESET}")
            println("${Colors.DARK_GRAY}Use '/mcp $name init --force' to restart.${Colors.RESET}")
            return
        }
        println("${Colors.DARK_GRAY}Starting server \"$name\"...${Colors.RESET}")
        try {
            McpManager.initServer(name)
            println("${Colors.LIGHT_GREEN}Connected to $name${Colors.RESET}")
            // Drain server stderr synchronously — before REPL prints the next "> " prompt
            McpManager.getSession(name)?.drainServerOutput()?.forEach { line ->
                println("${Colors.DARK_GRAY}[server] $line${Colors.RESET}")
            }
        } catch (e: IllegalArgumentException) {
            println("${Colors.LIGHT_YELLOW}${e.message}${Colors.RESET}")
        } catch (e: Exception) {
            println("${Colors.LIGHT_YELLOW}Failed to connect to $name: ${e.message}${Colors.RESET}")
        }
    }

    private fun cmdTools(name: String) {
        val session = McpManager.getSession(name)
        if (session == null || !session.isConnected) {
            println("${Colors.LIGHT_YELLOW}$name not connected. Run: /mcp $name init${Colors.RESET}")
            return
        }
        val tools = session.listTools()
        if (tools.isEmpty()) {
            println("${Colors.DARK_GRAY}No tools returned from $name.${Colors.RESET}")
            return
        }
        println()
        println("${Colors.LIGHT_YELLOW}Tools for $name:${Colors.RESET}")
        println()
        tools.forEach { tool ->
            println("  ${Colors.BRIGHT_WHITE}${tool.name}${Colors.RESET}")
            if (!tool.description.isNullOrBlank()) {
                println("  ${Colors.DARK_GRAY}description: ${tool.description}${Colors.RESET}")
            }
            val params = renderToolSchema(tool.inputSchema)
            if (params.isNotEmpty()) {
                println("  ${Colors.DARK_GRAY}parameters:${Colors.RESET}")
                params.forEach { line ->
                    println("  ${Colors.LIGHT_GRAY}  $line${Colors.RESET}")
                }
            }
            println()
        }
    }

    private fun cmdStop(name: String) {
        val session = McpManager.getSession(name)
        if (session == null || !session.isConnected) {
            println("${Colors.DARK_GRAY}$name is not running.${Colors.RESET}")
            return
        }
        session.close()
        println("${Colors.LIGHT_YELLOW}$name stopped.${Colors.RESET}")
    }

    private fun cmdTool(server: String, toolName: String, rawArgs: List<String>) {
        val args = parseToolArgs(rawArgs)
        when (val result = McpToolRouter.callTool(server, toolName, args)) {
            is ToolCallResult.Success -> {
                println()
                println("${Colors.LIGHT_GREEN}Tool result:${Colors.RESET}")
                println(renderToolResult(result.result))
                println()
            }
            is ToolCallResult.Error -> {
                println()
                println("${Colors.LIGHT_YELLOW}Tool error:${Colors.RESET}")
                println("${Colors.DARK_GRAY}${result.message}${Colors.RESET}")
                println()
            }
        }
    }

    // ─── Help ─────────────────────────────────────────────────────────────────

    private fun hint() {
        println("${Colors.LIGHT_YELLOW}MCP commands:${Colors.RESET}")
        println("${Colors.DARK_GRAY}  /mcp list                                — list registered servers and their status${Colors.RESET}")
        println("${Colors.DARK_GRAY}  /mcp <name> init                         — start and connect to a server${Colors.RESET}")
        println("${Colors.DARK_GRAY}  /mcp <name> tools                        — list tools exposed by server${Colors.RESET}")
        println("${Colors.DARK_GRAY}  /mcp <name> tool <tool_name> [key=value] — call a tool with arguments${Colors.RESET}")
        println("${Colors.DARK_GRAY}  /mcp <name> stop                         — disconnect from server${Colors.RESET}")
    }
}
