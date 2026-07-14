package smartagent.tools

import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolRegistryTest {

    @After
    fun tearDown() {
        ToolRegistry.clear()
    }

    @Test
    fun `register and get tool`() {
        val tool = stubTool("ping")
        ToolRegistry.register(tool)
        assertEquals(tool, ToolRegistry.get("ping"))
    }

    @Test
    fun `has returns false for missing tool`() {
        assertFalse(ToolRegistry.has("missing"))
    }

    @Test
    fun `has returns true after register`() {
        ToolRegistry.register(stubTool("foo"))
        assertTrue(ToolRegistry.has("foo"))
    }

    @Test
    fun `get throws for unknown id`() {
        assertFailsWith<IllegalStateException> { ToolRegistry.get("nope") }
    }

    @Test
    fun `register replaces existing tool with same id`() {
        ToolRegistry.register(stubTool("x", "first"))
        ToolRegistry.register(stubTool("x", "second"))
        assertEquals("second", ToolRegistry.get("x").execute(emptyMap()))
    }

    @Test
    fun `getAll returns all registered tools`() {
        ToolRegistry.register(stubTool("a"))
        ToolRegistry.register(stubTool("b"))
        val ids = ToolRegistry.getAll().map { it.id }.toSet()
        assertTrue(ids.containsAll(setOf("a", "b")))
    }

    @Test
    fun `clear removes all tools`() {
        ToolRegistry.register(stubTool("z"))
        ToolRegistry.clear()
        assertFalse(ToolRegistry.has("z"))
    }

    private fun stubTool(id: String, result: String = id) = object : Tool {
        override val id = id
        override val description = "stub"
        override fun execute(args: Map<String, Any>) = result
    }
}
