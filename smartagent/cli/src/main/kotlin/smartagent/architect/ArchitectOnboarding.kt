package smartagent.architect

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import smartagent.ChatSession
import smartagent.Colors
import smartagent.json
import smartagent.prettyJson
import java.io.File

internal class ArchitectOnboarding {

    private val promptDir: File = listOf(
        "cli/src/main/kotlin/prompts/architect",
        "smartagent/cli/src/main/kotlin/prompts/architect",
        "src/main/kotlin/prompts/architect",
        "prompts/architect"
    ).map(::File).firstOrNull { it.isDirectory } ?: File("cli/src/main/kotlin/prompts/architect")

    val longMemoryFile: File = resolveFile("arch_settings.md")
    private val workMemoryFile: File = resolveFile("arch_tasks.json")

    fun startSession(hasActiveFeature: Boolean = false) {
        if (hasActiveFeature) printFile("hello_second.md") else printFile("hello_first.md")
    }

    fun clearAll(session: ChatSession? = null) {
        runCatching { longMemoryFile.writeText("") }
        runCatching { workMemoryFile.writeText("[]") }
        session?.clearProfile()
    }

    fun appendDecision(decision: String) {
        longMemoryFile.appendText("$decision\n")
    }

    fun upsertWorkTask(taskTitle: String, taskDecision: String) {
        val tasks = loadWorkMemory().toMutableList()
        val idx = tasks.indexOfFirst { it.containsKey(taskTitle) }
        val entry = mapOf(taskTitle to taskDecision)
        if (idx >= 0) tasks[idx] = entry else tasks.add(entry)
        saveWorkMemory(tasks)
    }

    fun buildSystemPrompt(): String {
        val system = runCatching { File(promptDir, "system.md").readText() }.getOrElse { "" }
        val longMemory = runCatching { longMemoryFile.readText().trim() }.getOrElse { "" }
        return buildString {
            append(system)
            if (longMemory.isNotEmpty()) {
                append("\n\n")
                append(longMemory)
            }
        }
    }

    fun printHelloSecond() = printFile("hello_second.md")

    fun buildWorkMemoryText(): String {
        if (!workMemoryFile.exists()) return ""
        val text = runCatching { workMemoryFile.readText().trim() }.getOrElse { return "" }
        return if (text.isEmpty() || text == "[]") "" else text
    }

    private fun loadWorkMemory(): List<Map<String, String>> {
        if (!workMemoryFile.exists()) return emptyList()
        return runCatching {
            val array = json.parseToJsonElement(workMemoryFile.readText()) as? JsonArray ?: return emptyList()
            array.map { element ->
                val obj = element.jsonObject
                obj.entries.associate { (k, v) -> k to v.jsonPrimitive.content }
            }
        }.getOrElse { emptyList() }
    }

    private fun saveWorkMemory(tasks: List<Map<String, String>>) {
        val array = JsonArray(tasks.map { task ->
            JsonObject(task.mapValues { (_, v) -> JsonPrimitive(v) })
        })
        runCatching { workMemoryFile.writeText(prettyJson.encodeToString(JsonArray.serializer(), array)) }
    }

    private fun printFile(name: String) {
        val text = runCatching { File(promptDir, name).readText() }.getOrElse { "(not found: $name)" }
        println("${Colors.LIGHT_VIOLET}$text${Colors.RESET}")
    }

    private fun resolveFile(name: String): File =
        listOf("smartagent/$name", name)
            .map(::File)
            .firstOrNull { it.parentFile?.exists() ?: true } ?: File(name)
}
