package smartagent.support.mcp

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import smartagent.support.ticket.TicketRepository
import smartagent.support.ticket.TicketType
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TicketMcpSessionTest {

    private lateinit var tempFile: File
    private lateinit var repo: TicketRepository
    private lateinit var session: TicketMcpSession

    @Before
    fun setUp() {
        tempFile = File.createTempFile("tickets-mcp-test", ".json")
        repo = TicketRepository(tempFile.absolutePath)
        session = TicketMcpSession(repo)
    }

    @After
    fun tearDown() {
        tempFile.delete()
    }

    @Test
    fun `isConnected is always true`() {
        assertTrue(session.isConnected)
    }

    @Test
    fun `listTools returns exactly 5 tools`() {
        val tools = session.listTools()
        assertEquals(5, tools.size)
    }

    @Test
    fun `listTools tool ids match expected names`() {
        val names = session.listTools().map { it.name }.toSet()
        assertEquals(
            setOf("create_ticket", "get_ticket", "list_user_tickets", "list_global_tickets", "close_ticket"),
            names
        )
    }

    @Test
    fun `create_ticket returns ticket with id`() {
        val result = session.callTool(
            "create_ticket",
            mapOf(
                "userId" to JsonPrimitive("42"),
                "title" to JsonPrimitive("Login broken"),
                "description" to JsonPrimitive("Cannot log in")
            )
        )
        assertNotNull(result)
        val text = result.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(text.contains("Login broken"))
        assertTrue(text.contains("id"))
    }

    @Test
    fun `list_user_tickets returns only OPEN tickets for userId`() {
        repo.create(42L, TicketType.USER, "Issue 1", "")
        repo.create(42L, TicketType.USER, "Issue 2", "")
        repo.create(99L, TicketType.USER, "Other user", "")

        val result = session.callTool(
            "list_user_tickets",
            mapOf("userId" to JsonPrimitive("42"), "status" to JsonPrimitive("OPEN"))
        )
        assertNotNull(result)
        val text = result.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(text.contains("Issue 1"))
        assertTrue(text.contains("Issue 2"))
        assertTrue(!text.contains("Other user"))
    }

    @Test
    fun `list_global_tickets returns global tickets`() {
        repo.create(0L, TicketType.GLOBAL, "Known outage", "Server down")

        val result = session.callTool("list_global_tickets", emptyMap())
        assertNotNull(result)
        val text = result.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(text.contains("Known outage"))
    }

    @Test
    fun `get_ticket returns ticket by id`() {
        val ticket = repo.create(42L, TicketType.USER, "My Issue", "Details")

        val result = session.callTool("get_ticket", mapOf("id" to JsonPrimitive(ticket.id)))
        assertNotNull(result)
        val text = result.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(text.contains("My Issue"))
    }

    @Test
    fun `close_ticket closes the ticket`() {
        val ticket = repo.create(42L, TicketType.USER, "To close", "")

        val result = session.callTool("close_ticket", mapOf("id" to JsonPrimitive(ticket.id)))
        assertNotNull(result)
        val text = result.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(text.contains("closed") || text.contains("CLOSED"))
    }

    @Test
    fun `unknown tool returns error message`() {
        val result = session.callTool("nonexistent_tool", emptyMap())
        assertNotNull(result)
        assertTrue(result.jsonPrimitive.content.contains("Unknown tool"))
    }
}
