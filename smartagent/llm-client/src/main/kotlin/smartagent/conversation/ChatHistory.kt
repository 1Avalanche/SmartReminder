package smartagent.conversation

import smartagent.Message

const val KEEP_RECENT = 10

class ChatHistory {
    var summary: String = ""
        private set
    private val messages = mutableListOf<Message>()

    fun addExchange(userQuery: String, assistantAnswer: String) {
        messages += Message("user", userQuery)
        messages += Message("assistant", assistantAnswer)
    }

    fun messagesToSummarize(): List<Message> =
        if (messages.size > KEEP_RECENT) messages.dropLast(KEEP_RECENT) else emptyList()

    fun applySummary(newSummary: String) {
        summary = newSummary
        val kept = messages.takeLast(KEEP_RECENT)
        messages.clear()
        messages += kept
    }

    fun buildContextMessages(): List<Message> {
        val result = mutableListOf<Message>()
        if (summary.isNotBlank()) {
            result += Message("user", "Краткое содержание предыдущего разговора:\n$summary")
            result += Message("assistant", "Понял, учту.")
        }
        result += messages
        return result
    }
}
