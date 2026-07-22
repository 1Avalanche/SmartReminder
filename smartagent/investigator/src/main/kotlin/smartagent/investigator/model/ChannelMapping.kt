package smartagent.investigator.model

import kotlinx.serialization.Serializable

@Serializable
data class ChannelMapping(
    val primaryName: String,
    val alias: List<String>,
    val repoName: String
)

fun List<ChannelMapping>.resolveRepo(alias: String): String? =
    find { mapping -> mapping.alias.any { it.equals(alias, ignoreCase = true) } }?.repoName

fun List<ChannelMapping>.displayName(alias: String): String =
    find { mapping -> mapping.alias.any { it.equals(alias, ignoreCase = true) } }?.primaryName ?: alias
