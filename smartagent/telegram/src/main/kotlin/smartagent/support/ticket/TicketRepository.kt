package smartagent.support.ticket

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class TicketRepository(private val filePath: String) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val lock = ReentrantReadWriteLock()

    init {
        File(filePath).parentFile?.mkdirs()
        if (!File(filePath).exists()) save(emptyList())
    }

    fun create(
        userId: Long,
        type: TicketType,
        title: String,
        description: String
    ): Ticket {
        val now = Instant.now().toString()
        val ticket = Ticket(
            id = UUID.randomUUID().toString(),
            userId = userId,
            type = type,
            title = title,
            description = description,
            status = TicketStatus.OPEN,
            createdAt = now,
            updatedAt = now
        )
        lock.write {
            val all = load().toMutableList()
            all.add(ticket)
            save(all)
        }
        return ticket
    }

    fun findById(id: String): Ticket? = lock.read { load().find { it.id == id } }

    fun findByUserId(userId: Long): List<Ticket> =
        lock.read { load().filter { it.userId == userId && it.type == TicketType.USER } }

    fun findGlobal(): List<Ticket> =
        lock.read { load().filter { it.type == TicketType.GLOBAL } }

    fun close(id: String): Ticket? {
        var updated: Ticket? = null
        lock.write {
            val all = load().toMutableList()
            val idx = all.indexOfFirst { it.id == id }
            if (idx >= 0) {
                val closed = all[idx].copy(status = TicketStatus.CLOSED, updatedAt = Instant.now().toString())
                all[idx] = closed
                updated = closed
                save(all)
            }
        }
        return updated
    }

    private fun load(): List<Ticket> =
        runCatching { json.decodeFromString<List<Ticket>>(File(filePath).readText()) }.getOrElse { emptyList() }

    private fun save(tickets: List<Ticket>) {
        File(filePath).writeText(json.encodeToString(tickets))
    }
}
