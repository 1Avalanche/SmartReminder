package smartagent.investigator

import smartagent.Message
import smartagent.investigator.model.UiSearchResult

data class DataFlowCacheEntry(
    val stringId: String,
    val displayText: String,
    val apiMethod: String?,
    val apiField: String?,
    val channelAliases: List<String>,
    val uiPath: List<String>,
    val items: List<UiSearchResult>
)

class InvestigatorSession {
    private val history = mutableListOf<Pair<String, String>>()

    var uiFileHints: List<String> = emptyList()
    val channelFileHints: MutableMap<String, String> = mutableMapOf()
    val dataFlowCache: MutableMap<String, DataFlowCacheEntry> = mutableMapOf()

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
