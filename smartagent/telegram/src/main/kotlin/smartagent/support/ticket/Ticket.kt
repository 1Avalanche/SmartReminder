package smartagent.support.ticket

import kotlinx.serialization.Serializable

enum class TicketType { USER, GLOBAL }

enum class TicketStatus { OPEN, CLOSED }

@Serializable
data class Ticket(
    val id: String,
    val userId: Long,
    val type: TicketType,
    val title: String,
    val description: String,
    val status: TicketStatus,
    val createdAt: String,
    val updatedAt: String
)
