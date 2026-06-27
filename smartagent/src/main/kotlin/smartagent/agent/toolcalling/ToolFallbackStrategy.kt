package smartagent.agent.toolcalling

/**
 * Defines fallback chains between MCP tools.
 * When a tool fails, the engine consults this map to find the next candidate.
 * Tool names must match exactly what MCP servers expose.
 */
object ToolFallbackStrategy {

    private val rules: Map<String, List<String>> = mapOf(
        "tavily_crawl"    to listOf("tavily_search", "tavily-search", "fetch_url"),
        "tavily-crawl"    to listOf("tavily_search", "tavily-search", "fetch_url"),
        "tavily_extract"  to listOf("fetch_url", "extract_text"),
        "tavily-extract"  to listOf("fetch_url", "extract_text"),
        "tavily_search"   to listOf("fetch_url"),
        "tavily-search"   to listOf("fetch_url"),
        "fetch_url"       to listOf("tavily_search", "tavily-search")
    )

    fun getFallbacks(toolName: String): List<String> = rules[toolName] ?: emptyList()

    fun findAvailableFallback(toolName: String, availableTools: Set<String>): String? =
        getFallbacks(toolName).firstOrNull { it in availableTools }
}
