package smartagent.agent.toolcalling

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ToolFallbackStrategyTest {

    @Test
    fun `tavily_crawl falls back to tavily_search then fetch_url`() {
        assertEquals(listOf("tavily_search", "tavily-search", "fetch_url"), ToolFallbackStrategy.getFallbacks("tavily_crawl"))
    }

    @Test
    fun `tavily_extract falls back to fetch_url then extract_text`() {
        assertEquals(listOf("fetch_url", "extract_text"), ToolFallbackStrategy.getFallbacks("tavily_extract"))
    }

    @Test
    fun `fetch_url falls back to tavily search variants`() {
        val fallbacks = ToolFallbackStrategy.getFallbacks("fetch_url")
        assert(fallbacks.isNotEmpty())
    }

    @Test
    fun `unknown tool returns empty fallback list`() {
        assertEquals(emptyList(), ToolFallbackStrategy.getFallbacks("create_reminder"))
        assertEquals(emptyList(), ToolFallbackStrategy.getFallbacks("nonexistent"))
    }

    @Test
    fun `findAvailableFallback returns first available`() {
        val available = setOf("fetch_url", "extract_text")
        val result = ToolFallbackStrategy.findAvailableFallback("tavily_crawl", available)
        assertEquals("fetch_url", result)
    }

    @Test
    fun `findAvailableFallback skips unavailable tools`() {
        val available = setOf("extract_text")  // no tavily_search or fetch_url
        val result = ToolFallbackStrategy.findAvailableFallback("tavily_crawl", available)
        assertNull(result)  // extract_text not in tavily_crawl fallbacks
    }

    @Test
    fun `findAvailableFallback returns null when no fallback exists`() {
        val result = ToolFallbackStrategy.findAvailableFallback("create_reminder", setOf("fetch_url"))
        assertNull(result)
    }

    @Test
    fun `hyphenated and underscore tool names both covered`() {
        val available = setOf("fetch_url")
        assertEquals("fetch_url", ToolFallbackStrategy.findAvailableFallback("tavily-crawl", available))
        assertEquals("fetch_url", ToolFallbackStrategy.findAvailableFallback("tavily_crawl", available))
    }
}
