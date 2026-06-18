package smartagent

import kotlinx.serialization.encodeToString
import java.io.File

internal class ChatSession {
    val config = SessionConfig()
    val tokens = TokenTracker()
    val history = ConversationHistory(onChange = ::saveContext)
    val profile = UserProfileStore()

    private val contextFile: File by lazy {
        val path = listOf("cli/context.json", "context.json")
            .firstOrNull { File(it).parentFile?.exists() ?: true } ?: "context.json"
        File(path)
    }

    init {
        if (contextFile.exists()) {
            val text = contextFile.readText()
            runCatching { json.decodeFromString<ContextFile>(text) }.getOrNull()?.let { ctx ->
                history.loadFrom(ctx.history, ctx.summary, ctx.userMessageCount)
                config.currentMode = ctx.agentMode
                tokens.lastPromptTokens = ctx.lastPromptTokens
            } ?: runCatching { json.decodeFromString<List<LogEntry>>(text) }.getOrNull()
                ?.let { history.loadFrom(it, "", 0) }
        }
    }

    // --- SessionConfig delegates ---

    var currentModel: ModelConfig
        get() = config.currentModel
        set(v) = config.switchModel(v)

    val currentMode: AgentMode get() = config.currentMode

    val currentSystemPrompt: String
        get() = buildString {
            append(config.currentMode.basePrompt)
            val p = profile.load()
            if (p.isNotBlank()) append("\n\n## Долгосрочный профиль пользователя\n$p")
            append("\n$CONTEXT_FORMAT_INSTRUCTION")
        }

    var repoContext: RepoContext?
        get() = config.repoContext
        set(v) { config.repoContext = v }

    // --- TokenTracker delegates ---

    val lastPromptTokens: Int get() = tokens.lastPromptTokens

    fun addTokenEntry(usage: Usage) = tokens.addTokenEntry(usage)
    fun getTokenEntries() = tokens.getTokenEntries()
    fun updateLastPromptTokens(t: Int) { tokens.updateLastPromptTokens(t); saveContext() }
    fun shouldCompress(estimatedChars: Int = 0) =
        tokens.shouldCompress(estimatedChars, config.currentModel.contextWindow)

    // --- ConversationHistory delegates ---

    val summary: String get() = history.summary
    val userMessageCount: Int get() = history.userMessageCount

    fun addLogEntry(entry: LogEntry) = history.addLogEntry(entry)
    fun getHistory() = history.getHistory()
    fun getLastUserInputs(n: Int) = history.getLastUserInputs(n)
    fun shouldTriggerProfile() = history.shouldTriggerProfile()
    fun getMessagesToSummarize() = history.getMessagesToSummarize()
    fun applySummary(newSummary: String, count: Int) = history.applySummary(newSummary, count)
    fun buildContextContent() = history.buildContextContent()
    fun parseResponse(raw: String) = history.parseResponse(raw)
    fun addFileToContext(path: String, content: String) = history.addFileToContext(path, content)
    fun clearFileContext() = history.clearFileContext()
    fun getFileContextPaths() = history.getFileContextPaths()
    fun buildFileContextMessages() = history.buildFileContextMessages()

    // --- UserProfileStore delegates ---

    val profileFile: File get() = profile.file

    fun loadUserProfile() = profile.load()
    fun saveUserProfile(content: String) = profile.save(content)
    fun clearProfile() = profile.clear()

    // --- Coordinated operations ---

    fun switchModel(model: ModelConfig) = config.switchModel(model)

    fun switchMode(mode: AgentMode) {
        history.clear()
        config.currentMode = mode
        saveContext()
    }

    fun clear() {
        history.clear()
        tokens.clear()
        NetworkLogger.clear()
        saveContext()
    }

    private fun saveContext() {
        runCatching {
            contextFile.writeText(
                prettyJson.encodeToString(
                    ContextFile(
                        history = history.getHistory(),
                        summary = history.summary,
                        agentMode = config.currentMode,
                        lastPromptTokens = tokens.lastPromptTokens,
                        userMessageCount = history.userMessageCount
                    )
                )
            )
        }
    }
}
