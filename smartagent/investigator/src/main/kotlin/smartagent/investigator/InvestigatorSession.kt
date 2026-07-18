package smartagent.investigator

import smartagent.Message

class InvestigatorSession {
    private val history = mutableListOf<Pair<String, String>>()

    var uiFileHints: List<String> = emptyList()
    val channelFileHints: MutableMap<String, String> = mutableMapOf()

    fun addExchange(query: String, answer: String) {
        history += query to answer
    }

    fun buildHistory(): List<Message> = history.flatMap { (q, a) ->
        listOf(Message("user", q), Message("assistant", a))
    }

    fun clear() {
        history.clear()
    }

    val isEmpty: Boolean get() = history.isEmpty()
}
