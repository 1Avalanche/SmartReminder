package smartagent.agent.toolcalling

import smartagent.mcp_handler.McpTool
import smartagent.mcp_handler.parseSchemaParams

fun formatToolsForPrompt(tools: List<McpTool>): String {
    if (tools.isEmpty()) return "(no tools available)"
    return tools.joinToString("\n\n") { tool ->
        buildString {
            appendLine("Tool: ${tool.name}")
            if (!tool.description.isNullOrBlank()) {
                appendLine("Description: ${tool.description}")
            }
            val params = parseSchemaParams(tool.inputSchema)
            if (params.isNotEmpty()) {
                appendLine("Parameters:")
                params.forEach { p ->
                    val req = if (p.required) "required" else "optional"
                    appendLine("- ${p.name} (${p.type}, $req)")
                }
            }
        }.trimEnd()
    }
}
