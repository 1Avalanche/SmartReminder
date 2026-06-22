package smartagent.mcp_handler

data class McpServerConfig(
    val name: String,
    val command: List<String>,
    val workDir: String = System.getProperty("user.dir")
)
