package smartagent.architect

import kotlinx.serialization.Serializable

@Serializable
data class Task(
    val id: String,
    val featureId: String,
    val title: String,
    val status: TaskStatus,
    val stage: Stage,
    val currentStep: String? = null,
    val expectedAction: String? = null,
    val summary: String = "",
    val createdAt: String,
    val updatedAt: String
)

@Serializable
enum class TaskStatus {
    ACTIVE,
    PAUSED,
    COMPLETED
}

@Serializable
enum class Stage {
    PLANNING,
    EXECUTION,
    VALIDATION,
    DONE,
    PAUSED;

    fun displayName(): String = when (this) {
        PLANNING -> "Планирование"
        EXECUTION -> "Проектирование"
        VALIDATION -> "Проверка"
        DONE -> "Завершено"
        PAUSED -> "Приостановлено"
    }
}
