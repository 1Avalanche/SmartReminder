package smartagent.investigator.agents

internal class FileContentCache {
    private val entries = mutableMapOf<String, String>()

    fun get(path: String): String? = entries[path]

    fun put(path: String, content: String) {
        entries[path] = content
    }

    val size: Int get() = entries.size
}
