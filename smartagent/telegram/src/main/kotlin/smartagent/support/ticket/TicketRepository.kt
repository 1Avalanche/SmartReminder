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
        val file = File(filePath)
        file.parentFile?.mkdirs()
        println("[TicketRepo] path=$filePath exists=${file.exists()} size=${if (file.exists()) file.length() else -1}")
        if (!file.exists()) {
            val defaults = loadDefaults()
            println("[TicketRepo] file not found → seeding ${defaults.size} default tickets")
            save(defaults)
        } else {
            val current = load()
            val globalCount = current.count { it.type == TicketType.GLOBAL }
            println("[TicketRepo] loaded ${current.size} tickets total, $globalCount GLOBAL")
            if (globalCount == 0) {
                val defaults = loadDefaults()
                println("[TicketRepo] no GLOBAL tickets in file → merging ${defaults.size} defaults")
                save(current + defaults)
            }
        }
    }

    private fun loadDefaults(): List<Ticket> {
        val stream = TicketRepository::class.java.getResourceAsStream("/default-global-tickets.json")
        if (stream == null) {
            println("[TicketRepo] WARNING: default-global-tickets.json resource not found in classpath")
            return emptyList()
        }
        return runCatching {
            val text = stream.bufferedReader().readText()
            val tickets = json.decodeFromString<List<Ticket>>(text)
            println("[TicketRepo] loaded ${tickets.size} tickets from defaults resource")
            tickets
        }.getOrElse { e ->
            println("[TicketRepo] ERROR parsing defaults: ${e.message}")
            emptyList()
        }
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
        runCatching { json.decodeFromString<List<Ticket>>(File(filePath).readText()) }.getOrElse { e ->
            println("[TicketRepo] ERROR deserializing tickets: ${e.message}")
            emptyList()
        }

    private fun save(tickets: List<Ticket>) {
        File(filePath).writeText(json.encodeToString(tickets))
    }
}
