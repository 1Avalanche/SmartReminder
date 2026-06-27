package smartagent

import kotlinx.serialization.Serializable

@Serializable
data class Feature(
    val id: String,
    val title: String,
    val status: FeatureStatus,
    val createdAt: String,
    val updatedAt: String,
    val summary: String = ""
)

@kotlinx.serialization.Serializable
enum class FeatureStatus {
    ACTIVE,
    PAUSED,
    COMPLETED
}
