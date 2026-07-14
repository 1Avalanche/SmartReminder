package smartagent.doc

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import smartagent.Document
import smartagent.DocumentMetadata

private val lenientJson = Json { ignoreUnknownKeys = true }

class DocumentWalker(
    private val owner: String,
    private val repo: String,
    private val branch: String,
    private val fetchContent: (path: String) -> String?
) {
    fun walk(startPaths: List<String>): List<Document> {
        val documents = mutableListOf<Document>()
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        startPaths.forEach { if (visited.add(it)) queue.add(it) }
        while (queue.isNotEmpty()) {
            processPath(queue.removeFirst(), documents, queue, visited)
        }
        return documents
    }

    private fun processPath(path: String, into: MutableList<Document>, queue: ArrayDeque<String>, visited: MutableSet<String>) {
        val raw = fetchContent(path) ?: return
        val trimmed = raw.trim()
        when {
            trimmed.startsWith("[") -> expandDirectory(trimmed, queue, visited)
            isDocFile(path.substringAfterLast("/")) -> into.add(fileDocument(path, raw))
            else -> println("[DocIndex] Skipped non-doc path: $path")
        }
    }

    private fun expandDirectory(json: String, queue: ArrayDeque<String>, visited: MutableSet<String>) {
        val entries = runCatching {
            lenientJson.decodeFromString<List<GitHubEntry>>(json)
        }.getOrElse { return }

        for (entry in entries) {
            if (!visited.add(entry.path)) continue
            when {
                entry.type == "file" && isDocFile(entry.name) -> queue.add(entry.path)
                entry.type == "dir" -> queue.add(entry.path)
            }
        }
    }

    private fun fileDocument(path: String, content: String) = Document(
        id = "file:$owner/$repo/$path",
        title = path,
        content = content,
        metadata = DocumentMetadata(
            extension = path.substringAfterLast(".", ""),
            source = "github:$owner/$repo/$path"
        )
    )

    private fun isDocFile(name: String): Boolean {
        val ext = name.substringAfterLast(".", "").lowercase()
        return ext in setOf("md", "mdx", "txt", "rst", "adoc")
    }

    @Serializable
    private data class GitHubEntry(val name: String, val path: String, val type: String)
}
