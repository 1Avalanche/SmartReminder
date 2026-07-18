package smartagent.investigator

import smartagent.LLMGateway
import smartagent.ModelConfig
import smartagent.investigator.agents.AnswerComposer
import smartagent.investigator.agents.ChannelSearchAgent
import smartagent.investigator.agents.QueryClassifier
import smartagent.investigator.agents.UiSearchAgent
import smartagent.investigator.model.ChannelAgentOutput
import smartagent.investigator.model.QueryType
import smartagent.investigator.model.UiAgentOutput
import smartagent.investigator.model.UiSearchResult
import smartagent.investigator.model.resolveRepo
import smartagent.mcp_handler.McpSession

sealed class OrchestratorResponse {
    data class FinalAnswer(val text: String) : OrchestratorResponse()
    data class NeedsClarification(
        val options: List<UiSearchResult>,
        val pendingQuery: String
    ) : OrchestratorResponse()
    data class NeedsChannelSelection(
        val availableChannels: List<String>,
        val pendingQuery: String
    ) : OrchestratorResponse()
    data class Rejected(val reason: String) : OrchestratorResponse()
}

class InvestigatorOrchestrator(
    private val config: InvestigatorConfig,
    private val githubSession: McpSession,
    private val gateway: LLMGateway,
    private val model: ModelConfig
) {
    private val classifier = QueryClassifier(gateway, model)
    private val uiSearchAgent = UiSearchAgent(githubSession, gateway, model, config.owner, config.uiRepo)
    private val channelSearchAgent = ChannelSearchAgent(githubSession, gateway, model, config.owner)
    private val composer = AnswerComposer(gateway, model)

    fun handle(query: String, session: InvestigatorSession): OrchestratorResponse {
        return when (val queryType = classifier.classify(query)) {
            is QueryType.Rejected ->
                OrchestratorResponse.Rejected(
                    "${queryType.reason}\n\nЯ отвечаю на вопросы о связях UI↔backend " +
                        "или помогаю искать в репозиториях каналов."
                )
            is QueryType.ChannelSearch ->
                handleChannelSearchQuery(queryType, session)
            QueryType.DataFlow ->
                handleDataFlowQuery(query, session)
        }
    }

    private fun handleChannelSearchQuery(
        queryType: QueryType.ChannelSearch,
        session: InvestigatorSession
    ): OrchestratorResponse {
        val alias = queryType.channelAlias
        if (alias == null) {
            val allAliases = config.channels.mapNotNull { it.alias.firstOrNull() }.distinct()
            return OrchestratorResponse.NeedsChannelSelection(
                availableChannels = allAliases,
                pendingQuery = queryType.searchQuery
            )
        }
        val channelRepo = config.channels.resolveRepo(alias)
            ?: return OrchestratorResponse.FinalAnswer(
                "Не найден репозиторий для канала «$alias» в channels.json. " +
                    "Проверьте spelling или добавьте канал в файл."
            )
        val output = channelSearchAgent.searchDirect(
            userQuery = queryType.searchQuery,
            channelAlias = alias,
            channelRepo = channelRepo,
            history = session.buildHistory(),
            definitionHint = session.channelFileHints[alias]
        )
        if (output is ChannelAgentOutput.Result) {
            session.channelFileHints[alias] = output.data.definitionPath
        }
        return OrchestratorResponse.FinalAnswer(formatChannelOutput(output, alias))
    }

    fun handleChannelSearch(
        channelAlias: String,
        searchQuery: String,
        session: InvestigatorSession
    ): OrchestratorResponse {
        val channelRepo = config.channels.resolveRepo(channelAlias)
            ?: return OrchestratorResponse.FinalAnswer(
                "Не найден репозиторий для канала «$channelAlias» в channels.json."
            )
        val output = channelSearchAgent.searchDirect(
            userQuery = searchQuery,
            channelAlias = channelAlias,
            channelRepo = channelRepo,
            history = session.buildHistory(),
            definitionHint = session.channelFileHints[channelAlias]
        )
        if (output is ChannelAgentOutput.Result) {
            session.channelFileHints[channelAlias] = output.data.definitionPath
        }
        return OrchestratorResponse.FinalAnswer(formatChannelOutput(output, channelAlias))
    }

    private fun formatChannelOutput(output: ChannelAgentOutput, channelAlias: String): String =
        when (output) {
            is ChannelAgentOutput.Result -> {
                val r = output.data
                buildString {
                    appendLine("Канал: $channelAlias (${r.channelRepo})")
                    appendLine("Дефиниция: ${r.definitionPath}")
                    appendLine("Бэкенд: ${r.backendHost}${r.backendBasePath} (алиас: ${r.backendAlias})")
                    appendLine("Поля: ${r.sourceFields.joinToString(", ")}")
                    if (!r.transformation.isNullOrBlank()) appendLine("Трансформация: ${r.transformation}")
                }.trimEnd()
            }
            is ChannelAgentOutput.NoMethod ->
                "К сожалению, не нашлось поле/метод ${output.param} в канале ${output.channel}."
            is ChannelAgentOutput.NoBackend ->
                "К сожалению, не нашлось бэкенд источника в канале ${output.channel}."
            is ChannelAgentOutput.SearchError ->
                "Ошибка при поиске в канале $channelAlias: ${output.cause}"
        }

    private fun handleDataFlowQuery(query: String, session: InvestigatorSession): OrchestratorResponse {
        val history = session.buildHistory()
        val (uiOutput, uiFiles) = uiSearchAgent.search(query, history, session.uiFileHints)

        return when (uiOutput) {
            is UiAgentOutput.NotFound ->
                OrchestratorResponse.FinalAnswer(
                    "Не нашлось подходящих элементов UI для запроса «${uiOutput.query}». " +
                        "Попробуйте переформулировать запрос или уточнить название элемента на экране."
                )

            is UiAgentOutput.NoApiField ->
                OrchestratorResponse.FinalAnswer(
                    "К сожалению, не нашлось поле апи для «${uiOutput.displayText}»."
                )

            is UiAgentOutput.SearchError ->
                OrchestratorResponse.FinalAnswer(
                    "Ошибка при поиске в UI-репозитории: ${uiOutput.cause}"
                )

            is UiAgentOutput.Results -> {
                if (uiFiles.isNotEmpty()) session.uiFileHints = uiFiles

                val items = uiOutput.items
                val distinctStringIds = items.map { it.stringId }.distinct()

                // Multiple different UI strings → ask user to clarify
                if (distinctStringIds.size > 1) {
                    return OrchestratorResponse.NeedsClarification(
                        options = items.distinctBy { it.stringId },
                        pendingQuery = query
                    )
                }

                // Single string (possibly multiple channels — corner case)
                val channelOutputs = items.map { ui ->
                    val channelRepo = config.channels.resolveRepo(ui.channelAlias)
                    if (channelRepo == null) {
                        System.err.println("[orchestrator] Unknown channel alias: ${ui.channelAlias}")
                        ui to ChannelAgentOutput.SearchError(
                            "Не удалось найти репозиторий для канала «${ui.channelAlias}» в channels.json"
                        )
                    } else {
                        val chOutput = channelSearchAgent.search(
                            ui, channelRepo, history,
                            definitionHint = session.channelFileHints[ui.channelAlias]
                        )
                        if (chOutput is ChannelAgentOutput.Result) {
                            session.channelFileHints[ui.channelAlias] = chOutput.data.definitionPath
                        }
                        ui to chOutput
                    }
                }

                val answer = composer.compose(query, items, channelOutputs, history)
                OrchestratorResponse.FinalAnswer(answer)
            }
        }
    }

    fun handleClarification(
        selectedResult: UiSearchResult,
        originalQuery: String,
        session: InvestigatorSession
    ): OrchestratorResponse {
        val history = session.buildHistory()
        val channelRepo = config.channels.resolveRepo(selectedResult.channelAlias)
        if (channelRepo == null) {
            return OrchestratorResponse.FinalAnswer(
                "Не удалось найти репозиторий для канала «${selectedResult.channelAlias}» в channels.json."
            )
        }

        val channelOutput = channelSearchAgent.search(
            selectedResult, channelRepo, history,
            definitionHint = session.channelFileHints[selectedResult.channelAlias]
        )
        if (channelOutput is ChannelAgentOutput.Result) {
            session.channelFileHints[selectedResult.channelAlias] = channelOutput.data.definitionPath
        }
        val answer = composer.compose(
            originalQuery,
            listOf(selectedResult),
            listOf(selectedResult to channelOutput),
            history
        )
        return OrchestratorResponse.FinalAnswer(answer)
    }
}
