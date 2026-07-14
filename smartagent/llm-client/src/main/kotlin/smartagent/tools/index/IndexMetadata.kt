package smartagent.tools.index

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class IndexMetadata(
    val indexedAt: Long,
    val owner: String,
    val repo: String,
    val docPaths: List<String>,
    val currentBranch: String,
    val fileList: List<String>,
    val docCount: Int,
    val chunkCount: Int
) {
    fun isStale(ttlHours: Int = 12): Boolean {
        val ageMs = System.currentTimeMillis() - indexedAt
        return ageMs > ttlHours * 3_600_000L
    }

    companion object {
        private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

        fun load(path: String): IndexMetadata? = runCatching {
            json.decodeFromString<IndexMetadata>(File(path).readText())
        }.getOrNull()

        fun save(metadata: IndexMetadata, path: String) {
            File(path).also { it.parentFile?.mkdirs() }.writeText(json.encodeToString(metadata))
        }
    }
}
