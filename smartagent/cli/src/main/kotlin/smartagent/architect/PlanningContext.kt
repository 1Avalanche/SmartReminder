package smartagent.architect

data class PlanningContext(
    val taskTitle: String,
    val taskDescription: String,
    val additionalContext: String? = null,
    val history: String = "",
    val featureSummary: String = ""
)
