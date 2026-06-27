package smartagent

import kotlinx.serialization.encodeToString
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

internal class FeatureRepository {

    private val baseDir: File = if (File("smartagent").isDirectory) File("smartagent") else File(".")
    private val featuresDir: File = File(baseDir, "architect/features")
    private val activeFeatureFile: File = File(baseDir, "architect/active_feature.txt")

    init {
        featuresDir.mkdirs()
        activeFeatureFile.parentFile?.mkdirs()
    }

    fun createFeature(title: String): Feature {
        val id = generateId()
        val now = nowTimestamp()
        val feature = Feature(id = id, title = title, status = FeatureStatus.ACTIVE, createdAt = now, updatedAt = now)
        saveFeature(feature)
        setActiveFeature(id)
        return feature
    }

    fun getFeature(id: String): Feature? {
        val file = File(featuresDir, "$id.json")
        if (!file.exists()) return null
        return runCatching { prettyJson.decodeFromString<Feature>(file.readText()) }.getOrNull()
    }

    fun getAllFeatures(): List<Feature> {
        if (!featuresDir.exists()) return emptyList()
        return featuresDir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { f -> runCatching { prettyJson.decodeFromString<Feature>(f.readText()) }.getOrNull() }
            ?.sortedBy { it.createdAt }
            ?: emptyList()
    }

    fun getActiveFeature(): Feature? {
        if (!activeFeatureFile.exists()) return null
        val id = activeFeatureFile.readText().trim()
        if (id.isEmpty()) return null
        return getFeature(id)
    }

    fun setActiveFeature(id: String) {
        val current = getActiveFeature()
        if (current != null && current.id != id) {
            updateFeature(current.copy(status = FeatureStatus.PAUSED, updatedAt = nowTimestamp()))
        }
        val target = getFeature(id) ?: return
        if (target.status != FeatureStatus.ACTIVE) {
            updateFeature(target.copy(status = FeatureStatus.ACTIVE, updatedAt = nowTimestamp()))
        }
        activeFeatureFile.writeText(id)
    }

    fun updateFeature(feature: Feature) {
        saveFeature(feature)
    }

    fun pauseFeature(id: String) {
        val feature = getFeature(id) ?: return
        updateFeature(feature.copy(status = FeatureStatus.PAUSED, updatedAt = nowTimestamp()))
        if (activeFeatureFile.exists() && activeFeatureFile.readText().trim() == id) {
            activeFeatureFile.delete()
        }
    }

    fun completeFeature(id: String) {
        val feature = getFeature(id) ?: return
        updateFeature(feature.copy(status = FeatureStatus.COMPLETED, updatedAt = nowTimestamp()))
        if (activeFeatureFile.exists() && activeFeatureFile.readText().trim() == id) {
            activeFeatureFile.delete()
        }
    }

    private fun saveFeature(feature: Feature) {
        featuresDir.mkdirs()
        File(featuresDir, "${feature.id}.json").writeText(prettyJson.encodeToString(feature))
    }

    private fun generateId(): String {
        val existing = getAllFeatures()
        val maxNum = existing.mapNotNull { it.id.removePrefix("feature-").toIntOrNull() }.maxOrNull() ?: 0
        return "feature-%03d".format(maxNum + 1)
    }

    private fun nowTimestamp(): String =
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
}
