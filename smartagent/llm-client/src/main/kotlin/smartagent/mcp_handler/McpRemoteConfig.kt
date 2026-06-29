package smartagent.mcp_handler

import smartagent.Config

data class RemoteServerEntry(val name: String, val url: String, val apiKey: String?)

object McpRemoteConfig {
    val servers: List<RemoteServerEntry> = load(Config.localProperties, System.getenv())

    internal fun load(props: Map<String, String>, env: Map<String, String>): List<RemoteServerEntry> {
        fun get(key: String) = props[key] ?: env[key]

        return buildList {
            val myUrl = get("MCP_SERVER_URL_MY")
            val myKey = get("MCP_API_KEY_MY")
            if (myUrl != null) add(RemoteServerEntry(name = "my-mcp", url = myUrl, apiKey = myKey))

            val tavilyBase = get("MCP_SERVER_URL_TAVILY")
            val tavilyKey = get("TAVILY_API_KEY")
            if (tavilyBase != null) {
                val tavilyUrl = if (tavilyKey != null) appendQueryKey(tavilyBase, "tavilyApiKey", tavilyKey) else tavilyBase
                add(RemoteServerEntry(name = "tavily-mcp", url = tavilyUrl, apiKey = null))
            }
        }
    }

    internal fun appendQueryKey(baseUrl: String, paramName: String, paramValue: String): String {
        if (baseUrl.contains("$paramName=")) return baseUrl
        val separator = if ('?' in baseUrl) "&" else "?"
        return "$baseUrl$separator$paramName=$paramValue"
    }
}
