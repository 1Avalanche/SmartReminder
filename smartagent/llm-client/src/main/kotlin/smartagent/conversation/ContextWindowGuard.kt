package smartagent.conversation

import smartagent.Message

class ContextWindowGuard(val threshold: Double = COMPRESSION_THRESHOLD) {

    companion object {
        const val COMPRESSION_THRESHOLD = 0.8
        private const val CHARS_PER_TOKEN = 4
    }

    fun needsCompression(messages: List<Message>, contextWindow: Int): Boolean {
        val estimatedTokens = messages.sumOf { it.content.length } / CHARS_PER_TOKEN
        return estimatedTokens >= contextWindow * threshold
    }
}
