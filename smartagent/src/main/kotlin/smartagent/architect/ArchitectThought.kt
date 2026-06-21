package smartagent.architect

import kotlinx.serialization.Serializable

@Serializable
enum class ArchitectAction {
    ANSWER,
    CREATE_TASK,
    UPDATE_TASK,
    SWITCH_TASK
}

@Serializable
data class ArchitectThought(
    val response: String,
    val action: ArchitectAction,
    val taskTitle: String? = null,
    val taskDescription: String? = null,
    val additionalContext: String? = null
)
