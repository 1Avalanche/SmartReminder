package smartagent

import java.io.File
import kotlinx.serialization.encodeToString

internal class ChatSession {
    companion object {
        const val SLIDING_WINDOW_SIZE = 6
    }

    var currentModel: ModelConfig = ModelConfig.DEEPSEEK
        private set

    var currentMode: AgentMode = AgentMode.CHAT
        private set

    var contextStrategy: ContextStrategy = ContextStrategy.SLIDING_WINDOW

    val currentSystemPrompt: String
        get() = "${currentMode.basePrompt}\n${contextStrategy.formatInstruction}"

    var repoContext: RepoContext? = null

    private val history = mutableListOf<LogEntry>()
    private val fileContext = mutableListOf<Pair<String, String>>()
    private val facts = mutableListOf<Fact>()
    private val branches = mutableMapOf<String, MutableList<LogEntry>>()
    var activeBranch: String? = null
        private set

    private val contextFile: File by lazy {
        val path = listOf("cli/context.json", "context.json")
            .firstOrNull { File(it).parentFile?.exists() ?: true }
            ?: "context.json"
        File(path)
    }

    private val tokensFile: File by lazy {
        val path = listOf("cli/tokens.json", "tokens.json")
            .firstOrNull { File(it).parentFile?.exists() ?: true }
            ?: "tokens.json"
        File(path)
    }

    private val tokenEntries = mutableListOf<TokenEntry>()

    init {
        if (contextFile.exists()) {
            val text = contextFile.readText()
            runCatching {
                json.decodeFromString<ContextFile>(text)
            }.getOrNull()?.let { ctx ->
                history.addAll(ctx.history)
                contextStrategy = ctx.contextStrategy
                currentMode = ctx.agentMode
                facts.addAll(ctx.facts)
                ctx.branches.forEach { branch ->
                    branches[branch.name] = branch.history.toMutableList()
                }
                activeBranch = ctx.activeBranch
            } ?: runCatching {
                json.decodeFromString<List<LogEntry>>(text)
            }.getOrNull()?.let { history.addAll(it) }
        }
        if (tokensFile.exists()) {
            runCatching {
                json.decodeFromString<List<TokenEntry>>(tokensFile.readText())
            }.getOrNull()?.let { tokenEntries.addAll(it) }
        }
    }

    fun switchModel(model: ModelConfig) {
        currentModel = model
    }

    fun switchMode(mode: AgentMode) {
        history.clear()
        facts.clear()
        branches.clear()
        activeBranch = null
        currentMode = mode
        saveContext()
    }

    fun switchContextStrategy(strategy: ContextStrategy) {
        history.clear()
        facts.clear()
        branches.clear()
        activeBranch = null
        contextStrategy = strategy
        saveContext()
    }

    fun createCheckpoint(branch1: String, branch2: String) {
        branches[branch1] = history.toMutableList()
        branches[branch2] = history.toMutableList()
        activeBranch = branch1
        contextStrategy = ContextStrategy.BRANCHING
        saveContext()
    }

    fun switchBranch(name: String): Boolean {
        if (!branches.containsKey(name)) return false
        activeBranch = name
        saveContext()
        return true
    }

    fun getBranchNames(): List<String> = branches.keys.toList()

    fun clear() {
        history.clear()
        fileContext.clear()
        facts.clear()
        branches.clear()
        activeBranch = null
        tokenEntries.clear()
        NetworkLogger.clear()
        saveContext()
        runCatching { tokensFile.writeText("[]") }
    }

    fun addFileToContext(path: String, content: String) {
        fileContext.removeIf { it.first == path }
        fileContext.add(Pair(path, content))
    }

    fun clearFileContext() = fileContext.clear()

    fun getFileContextPaths(): List<String> = fileContext.map { it.first }

    fun buildFileContextMessages(): List<Message> {
        if (fileContext.isEmpty()) return emptyList()
        val sb = StringBuilder("Файлы из репозитория для контекста:\n\n")
        fileContext.forEach { (path, content) ->
            sb.appendLine("### $path")
            sb.appendLine("```")
            sb.appendLine(content)
            sb.appendLine("```")
        }
        return listOf(Message("user", sb.toString().trimEnd()))
    }

    fun addLogEntry(entry: LogEntry) {
        val branch = activeBranch
        if (branch != null && contextStrategy == ContextStrategy.BRANCHING) {
            branches.getOrPut(branch) { mutableListOf() }.add(entry)
        } else {
            history.add(entry)
            if (contextStrategy == ContextStrategy.SLIDING_WINDOW && history.size > SLIDING_WINDOW_SIZE) {
                history.removeFirst()
            }
        }
        saveContext()
    }

    fun addTokenEntry(usage: Usage) {
        val entry = TokenEntry(
            request = tokenEntries.size + 1,
            prompt = usage.prompt_tokens,
            completion = usage.completion_tokens,
            total = usage.total_tokens
        )
        tokenEntries.add(entry)
        runCatching { tokensFile.writeText(json.encodeToString(tokenEntries.toList())) }
    }

    fun updateFacts(newFacts: List<Fact>) {
        facts.clear()
        facts.addAll(newFacts)
        saveContext()
    }

    fun getFacts(): List<Fact> = facts.toList()

    fun getTokenEntries(): List<TokenEntry> = tokenEntries.toList()

    fun getHistory(): List<LogEntry> {
        val branch = activeBranch
        return if (branch != null && contextStrategy == ContextStrategy.BRANCHING) {
            branches[branch]?.toList() ?: emptyList()
        } else {
            history.toList()
        }
    }

    fun buildContextContent(): String = when (contextStrategy) {
        ContextStrategy.SLIDING_WINDOW -> buildContextSlidingWindow()
        ContextStrategy.STICKY_FACTS   -> buildContextStickyFacts()
        ContextStrategy.BRANCHING      -> buildContextBranching()
    }

    private fun buildContextSlidingWindow(): String {
        val lastTen = history.takeLast(SLIDING_WINDOW_SIZE)
            .mapNotNull { entry ->
                try { json.decodeFromString<StructuredResponse>(entry.apiResponse) }
                catch (_: Exception) { null }
            }
        if (lastTen.isEmpty()) return ""
        return buildString {
            appendLine("История сообщений:")
            lastTen.forEach {
                appendLine("Вопрос: ${it.summaryRequest}")
                appendLine("Ответ: ${it.summaryResponse}")
                appendLine()
            }
        }.trimEnd()
    }

    private fun buildContextStickyFacts(): String {
        if (facts.isEmpty()) return ""
        return buildString {
            appendLine("Факты:")
            facts.forEach { appendLine("- ${it.name}: ${it.value}") }
        }.trimEnd()
    }

    private fun buildContextBranching(): String {
        val source = activeBranch?.let { branches[it] } ?: history
        val entries = source.mapNotNull { entry ->
            try { json.decodeFromString<StructuredResponse>(entry.apiResponse) }
            catch (_: Exception) { null }
        }
        if (entries.isEmpty()) return ""
        return buildString {
            appendLine("История сообщений:")
            entries.forEach {
                appendLine("Вопрос: ${it.summaryRequest}")
                appendLine("Ответ: ${it.summaryResponse}")
                appendLine()
            }
        }.trimEnd()
    }

    fun parseResponse(raw: String): Pair<String, StructuredResponse?> {
        val trimmed = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return try {
            val parsed = json.decodeFromString<StructuredResponse>(trimmed)
            Pair(parsed.content, parsed)
        } catch (_: Exception) {
            Pair(raw, null)
        }
    }

    private fun saveContext() {
        runCatching {
            val branchList = branches.map { (name, hist) -> Branch(name, hist.toList()) }
            contextFile.writeText(
                json.encodeToString(
                    ContextFile(
                        history = history.toList(),
                        contextStrategy = contextStrategy,
                        agentMode = currentMode,
                        facts = facts.toList(),
                        branches = branchList,
                        activeBranch = activeBranch
                    )
                )
            )
        }
    }
}
