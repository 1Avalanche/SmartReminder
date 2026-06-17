package smartagent

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

@Serializable
internal data class OnboardingQuestion(val id: Int, val question: String, val description: String)

@Serializable
internal data class OnboardingState(val answered: List<Int> = emptyList())

internal class ArchitectOnboarding {

    private val promptDir: File = listOf(
        "smartagent/src/main/kotlin/prompts/architect",
        "src/main/kotlin/prompts/architect",
        "prompts/architect"
    ).map(::File).firstOrNull { it.isDirectory } ?: File("smartagent/src/main/kotlin/prompts/architect")

    private val onboardingFile: File = resolveFile("onboarding.json")
    val longMemoryFile: File = resolveFile("arch_settings.md")
    private val workMemoryFile: File = resolveFile("arch_tasks.json")

    private val questions: List<OnboardingQuestion> by lazy {
        runCatching {
            json.decodeFromString<List<OnboardingQuestion>>(File(promptDir, "questions.json").readText())
        }.getOrElse { emptyList() }
    }

    private var state: OnboardingState = loadState()
    private var pendingQuestion: OnboardingQuestion? = null

    val isWaitingForAnswer: Boolean get() = pendingQuestion != null

    fun startSession() {
        val allAnswered = questions.isNotEmpty() && questions.all { it.id in state.answered }
        when {
            allAnswered -> printFile("hello_second.md")
            state.answered.isEmpty() -> { printFile("hello_first.md"); println(); askNext() }
            else -> { printFile("hello_second.md"); println(); askNext() }
        }
    }

    fun handleAnswer(input: String): Boolean {
        val q = pendingQuestion ?: return false
        appendAnswer(q, input)
        markAnswered(q.id)
        askNext()
        return true
    }

    fun clearAll(session: ChatSession? = null) {
        state = OnboardingState()
        pendingQuestion = null
        runCatching { onboardingFile.writeText(json.encodeToString(state)) }
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

    private fun askNext() {
        val next = questions.firstOrNull { it.id !in state.answered }
        pendingQuestion = next
        if (next != null) {
            println("${Colors.LIGHT_VIOLET}${next.question}${Colors.RESET}\n")
        } else {
            printFile("onboarding_finish.md")
        }
    }

    private fun appendAnswer(q: OnboardingQuestion, answer: String) {
        longMemoryFile.appendText("${q.description} $answer\n")
    }

    private fun markAnswered(id: Int) {
        state = state.copy(answered = state.answered + id)
        runCatching { onboardingFile.writeText(json.encodeToString(state)) }
    }

    private fun loadState(): OnboardingState {
        if (!onboardingFile.exists()) return OnboardingState()
        return runCatching {
            json.decodeFromString<OnboardingState>(onboardingFile.readText())
        }.getOrElse { OnboardingState() }
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
