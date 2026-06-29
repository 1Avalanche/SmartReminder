package smartagent

import java.io.File

class UserProfileStore(
    val file: File = resolveProfileFile()
) {
    fun load(): String = runCatching { file.readText().trim() }.getOrElse { "" }
    fun save(content: String) { runCatching { file.writeText(content) } }
    fun clear() { runCatching { file.writeText("") } }
}

private fun resolveProfileFile(): File {
    val path = listOf("cli/user_profile.md", "user_profile.md")
        .firstOrNull { File(it).parentFile?.exists() ?: true } ?: "user_profile.md"
    return File(path)
}
