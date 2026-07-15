package smartagent.support.ticket

import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TicketRepositoryTest {

    private lateinit var tempFile: File
    private lateinit var repo: TicketRepository

    @Before
    fun setUp() {
        tempFile = File.createTempFile("tickets-test", ".json")
        repo = TicketRepository(tempFile.absolutePath)
    }

    @After
    fun tearDown() {
        tempFile.delete()
    }

    @Test
    fun `create returns ticket with generated id and OPEN status`() {
        val ticket = repo.create(userId = 100L, type = TicketType.USER, title = "Test", description = "Desc")

        assertTrue(ticket.id.isNotBlank())
        assertEquals(100L, ticket.userId)
        assertEquals(TicketType.USER, ticket.type)
        assertEquals("Test", ticket.title)
        assertEquals(TicketStatus.OPEN, ticket.status)
    }

    @Test
    fun `findById returns ticket after create`() {
        val created = repo.create(100L, TicketType.USER, "Title", "Desc")
        val found = repo.findById(created.id)

        assertNotNull(found)
        assertEquals(created.id, found.id)
    }

    @Test
    fun `findById returns null for unknown id`() {
        assertNull(repo.findById("nonexistent-id"))
    }

    @Test
    fun `findByUserId returns only USER tickets for that user`() {
        repo.create(100L, TicketType.USER, "User ticket", "")
        repo.create(200L, TicketType.USER, "Other user ticket", "")
        repo.create(100L, TicketType.GLOBAL, "Global ticket", "")

        val result = repo.findByUserId(100L)

        assertEquals(1, result.size)
        assertEquals("User ticket", result[0].title)
    }

    @Test
    fun `findGlobal returns only GLOBAL tickets`() {
        repo.create(100L, TicketType.USER, "User", "")
        repo.create(0L, TicketType.GLOBAL, "Global 1", "")
        repo.create(0L, TicketType.GLOBAL, "Global 2", "")

        val result = repo.findGlobal()

        assertEquals(2, result.size)
        assertTrue(result.all { it.type == TicketType.GLOBAL })
    }

    @Test
    fun `close changes status to CLOSED`() {
        val ticket = repo.create(100L, TicketType.USER, "Issue", "")
        val closed = repo.close(ticket.id)

        assertNotNull(closed)
        assertEquals(TicketStatus.CLOSED, closed.status)
        assertEquals(TicketStatus.CLOSED, repo.findById(ticket.id)?.status)
    }

    @Test
    fun `close returns null for unknown id`() {
        assertNull(repo.close("unknown-id"))
    }

    @Test
    fun `data persists across repository instances`() {
        val ticket = repo.create(100L, TicketType.USER, "Persistent", "")

        val repo2 = TicketRepository(tempFile.absolutePath)
        assertNotNull(repo2.findById(ticket.id))
    }

    @Test
    fun `concurrent creates are thread-safe`() {
        val threads = (1..20).map { i ->
            Thread { repo.create(i.toLong(), TicketType.USER, "Ticket $i", "") }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        val all = repo.findGlobal() + (1..20).flatMap { repo.findByUserId(it.toLong()) }
        assertEquals(20, all.size)
    }
}
