package smartagent.agent.toolcalling

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ToolFailureEngineTest {

    private fun engine(vararg tools: String) = ToolFailureEngine(tools.toSet())

    // ─── failure recording ────────────────────────────────────────────────────

    @Test
    fun `recordFailure classifies and increments count`() {
        val e = engine("tavily_search")
        val type = e.recordFailure("tavily_search", "initialize timed out")
        assertEquals(ToolFailureType.TIMEOUT, type)
        val (_, failures, lastType) = e.getState("tavily_search")
        assertEquals(1, failures)
        assertEquals(ToolFailureType.TIMEOUT, lastType)
    }

    @Test
    fun `TIMEOUT disables tool`() {
        val e = engine("fetch_url")
        e.recordFailure("fetch_url", "request timed out")
        assertTrue(e.isDisabled("fetch_url"))
        assertTrue("fetch_url" in e.disabledTools)
        assertFalse("fetch_url" in e.availableTools)
    }

    @Test
    fun `BLOCKED_CONTENT disables tool`() {
        val e = engine("fetch_url")
        e.recordFailure("fetch_url", "403 forbidden — bot protection")
        assertTrue(e.isDisabled("fetch_url"))
    }

    @Test
    fun `VALIDATION_ERROR does not disable tool`() {
        val e = engine("tavily_search")
        e.recordFailure("tavily_search", "validation failed: unexpected field")
        assertFalse(e.isDisabled("tavily_search"))
        assertTrue("tavily_search" in e.availableTools)
    }

    @Test
    fun `NETWORK_ERROR does not disable tool`() {
        val e = engine("fetch_url")
        e.recordFailure("fetch_url", "Failed to connect to host")
        assertFalse(e.isDisabled("fetch_url"))
    }

    @Test
    fun `recordSuccess increments success count`() {
        val e = engine("tavily_search")
        e.recordSuccess("tavily_search")
        e.recordSuccess("tavily_search")
        val (successes, _, _) = e.getState("tavily_search")
        assertEquals(2, successes)
    }

    // ─── identical retry detection ────────────────────────────────────────────

    @Test
    fun `isAlreadyCalled returns false before first call`() {
        val e = engine("tavily_search")
        assertFalse(e.isAlreadyCalled("tavily_search", mapOf("query" to "AI")))
    }

    @Test
    fun `isAlreadyCalled returns true after markCalled`() {
        val e = engine("tavily_search")
        val args = mapOf("query" to "AI agents")
        e.markCalled("tavily_search", args)
        assertTrue(e.isAlreadyCalled("tavily_search", args))
    }

    @Test
    fun `isAlreadyCalled false for different args`() {
        val e = engine("tavily_search")
        e.markCalled("tavily_search", mapOf("query" to "AI"))
        assertFalse(e.isAlreadyCalled("tavily_search", mapOf("query" to "robots")))
    }

    @Test
    fun `isAlreadyCalled false for different tool same args`() {
        val e = engine("tavily_search", "fetch_url")
        e.markCalled("tavily_search", mapOf("query" to "AI"))
        assertFalse(e.isAlreadyCalled("fetch_url", mapOf("query" to "AI")))
    }

    @Test
    fun `isAlreadyCalled independent of arg order`() {
        val e = engine("some_tool")
        e.markCalled("some_tool", mapOf("b" to "2", "a" to "1"))
        assertTrue(e.isAlreadyCalled("some_tool", mapOf("a" to "1", "b" to "2")))
    }

    // ─── replan messages ──────────────────────────────────────────────────────

    @Test
    fun `buildReplanMessage includes failure type and error`() {
        val e = engine("tavily_crawl", "fetch_url")
        e.recordFailure("tavily_crawl", "request timed out")
        val msg = e.buildReplanMessage("tavily_crawl", ToolFailureType.TIMEOUT, "request timed out")
        assertTrue(msg.contains("tavily_crawl"))
        assertTrue(msg.contains("TIMEOUT"))
        assertTrue(msg.contains("timed out"))
    }

    @Test
    fun `buildReplanMessage includes fallback suggestion when available`() {
        val e = engine("tavily_crawl", "fetch_url")
        val msg = e.buildReplanMessage("tavily_crawl", ToolFailureType.TIMEOUT, "timed out")
        assertTrue(msg.contains("fetch_url") || msg.contains("tavily_search"))
    }

    @Test
    fun `buildReplanMessage mentions no fallback when unavailable`() {
        val e = engine("tavily_crawl")  // fetch_url not in available tools
        val msg = e.buildReplanMessage("tavily_crawl", ToolFailureType.TIMEOUT, "timed out")
        assertTrue(msg.contains("FINAL_ANSWER") || msg.contains("no") || msg.contains("No"))
    }

    @Test
    fun `buildReplanMessage lists disabled tools`() {
        val e = engine("fetch_url", "tavily_search")
        e.recordFailure("fetch_url", "request timed out")
        val msg = e.buildReplanMessage("fetch_url", ToolFailureType.TIMEOUT, "timed out")
        assertTrue(msg.contains("fetch_url"))
    }

    @Test
    fun `buildIdenticalRetryMessage contains tool name`() {
        val e = engine("tavily_search")
        val msg = e.buildIdenticalRetryMessage("tavily_search")
        assertTrue(msg.contains("tavily_search"))
    }

    // ─── available tools tracking ─────────────────────────────────────────────

    @Test
    fun `available tools excludes disabled`() {
        val e = engine("tavily_search", "fetch_url", "create_reminder")
        e.recordFailure("fetch_url", "timed out")
        val available = e.availableTools
        assertFalse("fetch_url" in available)
        assertTrue("tavily_search" in available)
        assertTrue("create_reminder" in available)
    }

    @Test
    fun `all tools available at start`() {
        val e = engine("tool_a", "tool_b")
        assertEquals(setOf("tool_a", "tool_b"), e.availableTools)
    }
}
