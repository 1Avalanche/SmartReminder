package smartagent.mcp_handler

import smartagent.Config

object McpRemoteConfig {
    /** Base URL of the remote HTTP MCP server, e.g. https://your-vps/mcp */
    val serverUrl: String?
        get() = Config.localProperties["MCP_SERVER_URL"] ?: System.getenv("MCP_SERVER_URL")

    /** Optional Bearer token sent as Authorization header. */
    val apiKey: String?
        get() = Config.localProperties["MCP_API_KEY"] ?: System.getenv("MCP_API_KEY")
}
