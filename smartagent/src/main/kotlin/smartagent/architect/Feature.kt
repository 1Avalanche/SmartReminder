package smartagent.architect

import kotlinx.serialization.Serializable

@Serializable
data class Feature(
    val id: String,
    val title: String,
    val summary: String = "",
    val status: FeatureStatus,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
enum class FeatureStatus {
    ACTIVE,
    PAUSED,
    COMPLETED
}
