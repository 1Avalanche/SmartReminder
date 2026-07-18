package smartagent.investigator.model

import kotlinx.serialization.Serializable

@Serializable
data class UiSearchResult(
    val stringId: String,
    val displayText: String,
    val apiField: String,
    val channelAlias: String,
    val apiMethod: String
)

sealed class UiAgentOutput {
    data class Results(val items: List<UiSearchResult>) : UiAgentOutput()
    data class NotFound(val query: String) : UiAgentOutput()
    data class NoApiField(val stringId: String, val displayText: String) : UiAgentOutput()
    data class SearchError(val cause: String) : UiAgentOutput()
}
