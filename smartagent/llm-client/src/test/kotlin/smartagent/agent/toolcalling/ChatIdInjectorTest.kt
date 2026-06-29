package smartagent.agent.toolcalling

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatIdInjectorTest {

    // ─── enrich ───────────────────────────────────────────────────────────────

    @Test
    fun `injects chat_id for allowed tool`() {
        val args = mutableMapOf<String, JsonElement>("text" to JsonPrimitive("hello"))
        val injected = ChatIdInjector.enrich("create_reminder", args, chatId = 42L)
        assertTrue(injected)
        assertEquals(JsonPrimitive("42"), args["chat_id"])
    }

    @Test
    fun `does NOT inject for external tool`() {
        val args = mutableMapOf<String, JsonElement>("query" to JsonPrimitive("AI agents"))
        val injected = ChatIdInjector.enrich("tavily-search", args, chatId = 42L)
        assertFalse(injected)
        assertFalse("chat_id" in args)
    }

    @Test
    fun `does NOT inject when chatId is null`() {
        val args = mutableMapOf<String, JsonElement>("text" to JsonPrimitive("hello"))
        val injected = ChatIdInjector.enrich("create_reminder", args, chatId = null)
        assertFalse(injected)
        assertFalse("chat_id" in args)
    }

    @Test
    fun `does NOT overwrite existing chat_id`() {
        val args = mutableMapOf<String, JsonElement>(
            "text" to JsonPrimitive("hello"),
            "chat_id" to JsonPrimitive("99")
        )
        val injected = ChatIdInjector.enrich("create_reminder", args, chatId = 42L)
        assertFalse(injected)
        assertEquals(JsonPrimitive("99"), args["chat_id"])
    }

    @Test
    fun `all allowedTools receive injection`() {
        ChatIdInjector.allowedTools.forEach { toolName ->
            val args = mutableMapOf<String, JsonElement>()
            val injected = ChatIdInjector.enrich(toolName, args, chatId = 1L)
            assertTrue(injected, "Expected injection for $toolName")
        }
    }

    @Test
    fun `tavily tools are NOT in allowlist`() {
        listOf("tavily-search", "tavily-extract", "web_search", "extract_text", "fetch_url").forEach { tool ->
            assertFalse(tool in ChatIdInjector.allowedTools, "$tool must not be in allowlist")
        }
    }

    // ─── stripUnknownArgs ─────────────────────────────────────────────────────

    @Test
    fun `strips chat_id when not in schema properties`() {
        val schema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("query", buildJsonObject { put("type", "string") })
            })
        }
        val args = mapOf<String, JsonElement>("query" to JsonPrimitive("AI agents"), "chat_id" to JsonPrimitive("42"))
        val result = ChatIdInjector.stripUnknownArgs(schema, args)
        assertEquals(mapOf<String, JsonElement>("query" to JsonPrimitive("AI agents")), result)
    }

    @Test
    fun `keeps chat_id when schema has chat_id property`() {
        val schema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("text", buildJsonObject { put("type", "string") })
                put("chat_id", buildJsonObject { put("type", "string") })
            })
        }
        val args = mapOf<String, JsonElement>("text" to JsonPrimitive("hello"), "chat_id" to JsonPrimitive("42"))
        val result = ChatIdInjector.stripUnknownArgs(schema, args)
        assertEquals(args, result)
    }

    @Test
    fun `no-ops when schema is null`() {
        val args = mapOf<String, JsonElement>("query" to JsonPrimitive("AI"), "chat_id" to JsonPrimitive("42"))
        val result = ChatIdInjector.stripUnknownArgs(null, args)
        assertEquals(args, result)
    }

    @Test
    fun `no-ops when schema has no properties key`() {
        val schema = buildJsonObject { put("type", "object") }
        val args = mapOf<String, JsonElement>("query" to JsonPrimitive("AI"), "chat_id" to JsonPrimitive("42"))
        val result = ChatIdInjector.stripUnknownArgs(schema, args)
        assertEquals(args, result)
    }

    @Test
    fun `preserves all known args`() {
        val schema = buildJsonObject {
            put("properties", buildJsonObject {
                put("a", buildJsonObject {})
                put("b", buildJsonObject {})
            })
        }
        val args = mapOf<String, JsonElement>("a" to JsonPrimitive("1"), "b" to JsonPrimitive("2"))
        val result = ChatIdInjector.stripUnknownArgs(schema, args)
        assertEquals(args, result)
    }
}
