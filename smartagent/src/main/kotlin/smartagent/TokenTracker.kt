package smartagent

import kotlinx.serialization.encodeToString
import java.io.File

private const val TOKEN_CONTEXT_PERCENT = 0.04

internal class TokenTracker(
    private val file: File = resolveTokenFile()
) {
    var lastPromptTokens: Int = 0
        internal set

    private val entries = mutableListOf<TokenEntry>()

    init {
        if (file.exists()) {
            runCatching { json.decodeFromString<List<TokenEntry>>(file.readText()) }
                .getOrNull()?.let { entries.addAll(it) }
        }
    }

    fun addTokenEntry(usage: Usage) {
        val entry = TokenEntry(
            request = entries.size + 1,
            prompt = usage.prompt_tokens,
            completion = usage.completion_tokens,
            total = usage.total_tokens
        )
        entries.add(entry)
        runCatching { file.writeText(json.encodeToString(entries.toList())) }
    }

    fun getTokenEntries(): List<TokenEntry> = entries.toList()

    fun shouldCompress(estimatedChars: Int = 0, contextWindow: Int): Boolean {
        val threshold = (contextWindow * TOKEN_CONTEXT_PERCENT).toInt()
        val effectiveTokens = maxOf(lastPromptTokens, estimatedChars / 4)
        return effectiveTokens >= threshold
    }

    fun updateLastPromptTokens(tokens: Int) {
        lastPromptTokens = tokens
    }

    fun clear() {
        entries.clear()
        lastPromptTokens = 0
        runCatching { file.writeText("[]") }
    }
}

private fun resolveTokenFile(): File {
    val path = listOf("cli/tokens.json", "tokens.json")
        .firstOrNull { File(it).parentFile?.exists() ?: true } ?: "tokens.json"
    return File(path)
}
