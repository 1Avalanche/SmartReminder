package smartagent.investigator

import kotlinx.serialization.json.Json
import smartagent.Config
import smartagent.investigator.model.ChannelMapping
import java.io.File

data class InvestigatorConfig(
    val owner: String,
    val uiRepo: String,
    val channels: List<ChannelMapping>
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun load(): InvestigatorConfig {
            val props = Config.localProperties

            val owner = props["INVASTIGATOR_OWNERR"]
                ?: error("INVASTIGATOR_OWNERR not set (проверьте .properties или ~/.config/smartagent/local.properties)")
            val uiRepo = props["UI_REPO"]
                ?: error("UI_REPO not set (проверьте .properties или ~/.config/smartagent/local.properties)")

            val channels = loadChannels()

            return InvestigatorConfig(owner = owner, uiRepo = uiRepo, channels = channels)
        }

        private fun loadChannels(): List<ChannelMapping> {
            val candidates = listOfNotNull(
                System.getProperty("investigator.channels"),
                "channels.json",
                "../channels.json"
            )
            val file = candidates.map(::File).firstOrNull { it.exists() }
                ?: return emptyList()

            return runCatching {
                json.decodeFromString<List<ChannelMapping>>(file.readText())
            }.getOrElse { e ->
                System.err.println("[investigator] Failed to parse channels.json: ${e.message}")
                emptyList()
            }
        }
    }
}
