package smartagent.mcp_handler

fun main() {
    val command = listOf("npx", "-y", "@modelcontextprotocol/server-filesystem", ".")
    val workDir = System.getProperty("user.dir")

    println("Starting MCP server: ${command.joinToString(" ")}")
    println("Working dir: $workDir")
    println()

    ProcessTransport(command, workDir).use { transport ->
        // npx may need a moment to start (especially first run downloads the package)
        Thread.sleep(2_000)

        McpClient(transport).use { client ->
            client.initialize()
            println("Connected to MCP server")
            println()

            val tools = client.listTools()
            if (tools.isEmpty()) {
                println("No tools returned.")
                return
            }

            println("Available tools:")
            for (tool in tools) {
                println("  - ${tool.name}: ${tool.description}")
            }

            // Bonus: demo callTool — list current directory
            println()
            println("Demo — calling list_directory on \".\":")
            val result = client.callTool("list_directory", mapOf("path" to workDir))
            result?.let { println(it) }
        }
    }
}
