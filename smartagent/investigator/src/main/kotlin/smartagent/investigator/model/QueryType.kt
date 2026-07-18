package smartagent.investigator.model

sealed class QueryType {
    object DataFlow : QueryType()
    data class ChannelSearch(
        val channelAlias: String?,
        val searchQuery: String
    ) : QueryType()
    data class Rejected(val reason: String) : QueryType()
}
