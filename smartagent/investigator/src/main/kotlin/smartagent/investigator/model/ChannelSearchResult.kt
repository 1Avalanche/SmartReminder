package smartagent.investigator.model

import kotlinx.serialization.Serializable

@Serializable
data class ChannelSearchResult(
    val channelRepo: String,
    val definitionPath: String,
    val backendAlias: String,
    val backendHost: String,
    val backendBasePath: String,
    val sourceFields: List<String>,
    val transformation: String? = null
)

sealed class ChannelAgentOutput {
    data class Result(val data: ChannelSearchResult) : ChannelAgentOutput()
    data class NoMethod(val param: String, val channel: String) : ChannelAgentOutput()
    data class NoBackend(val channel: String) : ChannelAgentOutput()
    data class SearchError(val cause: String) : ChannelAgentOutput()
}
