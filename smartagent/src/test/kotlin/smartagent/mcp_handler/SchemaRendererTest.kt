package smartagent.mcp_handler

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SchemaRendererTest {

    private fun schema(
        vararg props: Pair<String, String>,
        required: List<String> = emptyList()
    ) = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            props.forEach { (name, type) ->
                put(name, buildJsonObject { put("type", type) })
            }
        })
        if (required.isNotEmpty()) {
            put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
        }
    }

    // ─── parseSchemaParams ────────────────────────────────────────────────────

    @Test
    fun `null schema returns empty`() {
        assertTrue(parseSchemaParams(null).isEmpty())
    }

    @Test
    fun `schema without properties returns empty`() {
        val s = buildJsonObject { put("type", "object") }
        assertTrue(parseSchemaParams(s).isEmpty())
    }

    @Test
    fun `single required property`() {
        val params = parseSchemaParams(schema("owner" to "string", required = listOf("owner")))
        assertEquals(1, params.size)
        assertEquals(ToolParam("owner", "string", required = true), params[0])
    }

    @Test
    fun `optional property not in required list`() {
        val params = parseSchemaParams(schema("limit" to "integer"))
        assertEquals(1, params.size)
        assertEquals(ToolParam("limit", "integer", required = false), params[0])
    }

    @Test
    fun `multiple properties mixed required`() {
        val params = parseSchemaParams(
            schema("owner" to "string", "repo" to "string", "state" to "string",
                required = listOf("owner", "repo"))
        )
        assertEquals(3, params.size)
        val byName = params.associateBy { it.name }
        assertTrue(byName["owner"]!!.required)
        assertTrue(byName["repo"]!!.required)
        assertTrue(!byName["state"]!!.required)
    }

    @Test
    fun `missing type defaults to any`() {
        val s = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("x", buildJsonObject {})  // no "type" field
            })
        }
        val params = parseSchemaParams(s)
        assertEquals("any", params[0].type)
    }

    // ─── renderToolSchema ─────────────────────────────────────────────────────

    @Test
    fun `null schema renders empty`() {
        assertTrue(renderToolSchema(null).isEmpty())
    }

    @Test
    fun `required param renders with required tag`() {
        val lines = renderToolSchema(schema("owner" to "string", required = listOf("owner")))
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("owner"))
        assertTrue(lines[0].contains("required"))
    }

    @Test
    fun `optional param renders with optional tag`() {
        val lines = renderToolSchema(schema("limit" to "integer"))
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("optional"))
    }

    @Test
    fun `multiple params all rendered`() {
        val lines = renderToolSchema(
            schema("owner" to "string", "repo" to "string", required = listOf("owner"))
        )
        assertEquals(2, lines.size)
    }

    @Test
    fun `output format matches dash-name-type-req`() {
        val lines = renderToolSchema(schema("owner" to "string", required = listOf("owner")))
        assertEquals("- owner (string, required)", lines[0])
    }

    @Test
    fun `optional format`() {
        val lines = renderToolSchema(schema("state" to "string"))
        assertEquals("- state (string, optional)", lines[0])
    }
}
