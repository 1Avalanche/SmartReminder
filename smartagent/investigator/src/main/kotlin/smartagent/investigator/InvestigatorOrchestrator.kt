package smartagent.investigator

import smartagent.LLMGateway
import smartagent.Message
import smartagent.ModelConfig
import smartagent.investigator.agents.AnswerComposer
import smartagent.investigator.agents.ChannelSearchAgent
import smartagent.investigator.agents.QueryClassifier
import smartagent.investigator.agents.RelevanceGuard
import smartagent.investigator.agents.UiSearchAgent
import smartagent.investigator.model.ChannelAgentOutput
import smartagent.investigator.model.QueryType
import smartagent.investigator.model.UiAgentOutput
import smartagent.investigator.model.UiSearchResult
import smartagent.investigator.model.displayName
import smartagent.investigator.model.resolveRepo
import smartagent.mcp_handler.McpSession

sealed class OrchestratorResponse {
    data class FinalAnswer(val text: String, val isError: Boolean = false) : OrchestratorResponse()
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
    companion object {
        private const val GRAY = "[90m"
        private const val RESET = "[0m"
    }
    private val classifier = QueryClassifier(gateway, model)
    private val uiSearchAgent = UiSearchAgent(githubSession, gateway, model, config.owner, config.uiRepo)
    private val channelSearchAgent = ChannelSearchAgent(githubSession, gateway, model, config.owner)
    private val composer = AnswerComposer(gateway, model)
    private val relevanceGuard = RelevanceGuard(gateway, model)

    fun handle(query: String, session: InvestigatorSession): OrchestratorResponse {
        return when (val queryType = classifier.classify(query)) {
            is QueryType.Rejected ->
                OrchestratorResponse.Rejected(
                    "${queryType.reason}\n\nЯ отвечаю на вопросы о связях UI ↔ backend " +
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
            val allAliases = config.channels.map { it.primaryName }.distinct()
            return OrchestratorResponse.NeedsChannelSelection(
                availableChannels = allAliases,
                pendingQuery = queryType.searchQuery
            )
        }
        val channelRepo = config.channels.resolveRepo(alias)
            ?: return OrchestratorResponse.FinalAnswer(
                "⚠️ Не найден репозиторий для канала «$alias» в channels.json. " +
                    "Проверьте spelling или добавьте канал в файл.",
                isError = true
            )
        val output = channelSearchAgent.searchDirect(
            userQuery = queryType.searchQuery,
            channelAlias = alias,
            channelRepo = channelRepo,
            history = emptyList(),
            definitionHint = session.channelFileHints[alias]
        )
        if (output is ChannelAgentOutput.Result) {
            session.channelFileHints[alias] = output.data.definitionPath
        }
        return OrchestratorResponse.FinalAnswer(
            formatChannelOutput(output, alias),
            isError = output !is ChannelAgentOutput.Result
        )
    }

    fun handleChannelSearch(
        channelAlias: String,
        searchQuery: String,
        session: InvestigatorSession
    ): OrchestratorResponse {
        val channelRepo = config.channels.resolveRepo(channelAlias)
            ?: return OrchestratorResponse.FinalAnswer(
                "⚠️ Не найден репозиторий для канала «$channelAlias» в channels.json.",
                isError = true
            )
        val output = channelSearchAgent.searchDirect(
            userQuery = searchQuery,
            channelAlias = channelAlias,
            channelRepo = channelRepo,
            history = emptyList(),
            definitionHint = session.channelFileHints[channelAlias]
        )
        if (output is ChannelAgentOutput.Result) {
            session.channelFileHints[channelAlias] = output.data.definitionPath
        }
        return OrchestratorResponse.FinalAnswer(
            formatChannelOutput(output, channelAlias),
            isError = output !is ChannelAgentOutput.Result
        )
    }

    private fun formatChannelOutput(output: ChannelAgentOutput, channelAlias: String): String =
        when (output) {
            is ChannelAgentOutput.Result -> {
                val r = output.data
                buildString {
                    appendLine("Канал: ${config.channels.displayName(channelAlias)} (${r.channelRepo})")
                    appendLine("Дефиниция: ${r.definitionPath}")
                    appendLine("Бэкенд: ${r.backendHost}${r.backendBasePath} (алиас: ${r.backendAlias})")
                    appendLine("Поля: ${r.sourceFields.joinToString(", ")}")
                    if (!r.transformation.isNullOrBlank()) appendLine("Трансформация: ${r.transformation}")
                }.trimEnd()
            }
            is ChannelAgentOutput.NoMethod ->
                "❓ Не нашлось поле/метод «${output.param}» в канале ${output.channel}."
            is ChannelAgentOutput.NoBackend ->
                "⚠️ Не нашлось бэкенд источника в канале ${output.channel}."
            is ChannelAgentOutput.SearchError ->
                "⚠️ Ошибка при поиске в канале ${config.channels.displayName(channelAlias)}: ${output.cause}"
        }

    private fun handleDataFlowQuery(query: String, session: InvestigatorSession): OrchestratorResponse {
        val cached = lookupInCache(query, session.dataFlowCache)
        if (cached != null) {
            return handleDataFlowFromCache(query, cached, session)
        }

        val history = session.buildHistory()
        println("${GRAY}Ищу в UI-репозитории...$RESET")
        val (uiOutput, uiFiles) = uiSearchAgent.search(query, emptyList(), session.uiFileHints)

        return when (uiOutput) {
            is UiAgentOutput.NotFound ->
                OrchestratorResponse.FinalAnswer(
                    "❌ Не нашлось подходящих элементов UI для запроса «${uiOutput.query}». " +
                        "Попробуйте переформулировать запрос или уточнить название элемента на экране.",
                    isError = true
                )

            is UiAgentOutput.NoApiField ->
                OrchestratorResponse.FinalAnswer(
                    "❓ Нашёл UI-элемент «${uiOutput.displayText}», но не удалось определить API-поле или канал.",
                    isError = true
                )

            is UiAgentOutput.SearchError ->
                OrchestratorResponse.FinalAnswer(
                    "⚠️ Ошибка при поиске в UI-репозитории: ${uiOutput.cause}",
                    isError = true
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

                val guardNote = items.first().let { first ->
                    when (val g = relevanceGuard.check(query, first.displayText, first.stringId)) {
                        is RelevanceGuard.Result.Uncertain -> g.note
                        RelevanceGuard.Result.Confident -> null
                    }
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
                        println("${GRAY}Ищу в канале ${config.channels.displayName(ui.channelAlias)}...$RESET")
                        val chOutput = channelSearchAgent.search(
                            ui, channelRepo, emptyList(),
                            definitionHint = session.channelFileHints[ui.channelAlias]
                        )
                        if (chOutput is ChannelAgentOutput.Result) {
                            session.channelFileHints[ui.channelAlias] = chOutput.data.definitionPath
                        }
                        ui to chOutput
                    }
                }

                println("${GRAY}Формирую ответ...$RESET")
                val answer = composer.compose(query, items, channelOutputs, history, guardNote)

                val first = items.first()
                session.dataFlowCache[first.stringId] = DataFlowCacheEntry(
                    stringId = first.stringId,
                    displayText = first.displayText,
                    apiMethod = first.apiMethod,
                    apiField = first.apiField,
                    channelAliases = items.map { it.channelAlias }.distinct(),
                    uiPath = uiFiles,
                    items = items
                )

                OrchestratorResponse.FinalAnswer(answer)
            }
        }
    }

    private fun handleDataFlowFromCache(
        query: String,
        cached: DataFlowCacheEntry,
        session: InvestigatorSession
    ): OrchestratorResponse {
        println("${GRAY}Нашёл «${cached.displayText}» в кэше, уточняю данные...$RESET")
        if (cached.uiPath.isNotEmpty()) session.uiFileHints = cached.uiPath

        val guardNote = cached.items.first().let { first ->
            when (val g = relevanceGuard.check(query, first.displayText, first.stringId)) {
                is RelevanceGuard.Result.Uncertain -> g.note
                RelevanceGuard.Result.Confident -> null
            }
        }

        val history = session.buildHistory()
        val channelOutputs = cached.items.map { ui ->
            val channelRepo = config.channels.resolveRepo(ui.channelAlias)
            if (channelRepo == null) {
                ui to ChannelAgentOutput.SearchError(
                    "Не удалось найти репозиторий для канала «${ui.channelAlias}» в channels.json"
                )
            } else {
                println("Ищу в канале ${config.channels.displayName(ui.channelAlias)}...")
                val chOutput = channelSearchAgent.search(
                    ui, channelRepo, emptyList(),
                    definitionHint = session.channelFileHints[ui.channelAlias]
                )
                if (chOutput is ChannelAgentOutput.Result) {
                    session.channelFileHints[ui.channelAlias] = chOutput.data.definitionPath
                }
                ui to chOutput
            }
        }

        println("${GRAY}Формирую ответ...$RESET")
        val answer = composer.compose(query, cached.items, channelOutputs, history, guardNote)
        return OrchestratorResponse.FinalAnswer(answer)
    }

    private fun lookupInCache(
        query: String,
        cache: Map<String, DataFlowCacheEntry>
    ): DataFlowCacheEntry? {
        if (cache.isEmpty()) return null

        val recentHint = cache.values.lastOrNull()
            ?.let { "\nПредыдущий запрос был про: \"${it.displayText}\"." }
            ?: ""
        val entries = cache.values.joinToString("\n") {
            "- stringId=\"${it.stringId}\", displayText=\"${it.displayText}\""
        }

        val prompt = """
Кэш найденных UI-элементов:
$entries
$recentHint
Новый запрос: "$query"

Использовать кэш ТОЛЬКО если запрос — буквальное уточнение того же UI-элемента:
дополнительный вопрос о том же поле, том же экране, той же связи UI→backend.

НЕ использовать кэш если:
- запрос о другом UI-элементе, даже тематически близком
- запрос можно трактовать двояко
- первый раз спрашивают об этом UI-концепте в новом контексте

Если точно тот же UI-концепт → верни только stringId. Иначе → верни null.
Одна строка, без объяснений.
""".trimIndent()

        val response = runCatching {
            gateway.chat(listOf(Message("user", prompt)), model, "investigator-cache-lookup")
        }.getOrNull()?.content?.trim() ?: return null

        val id = response.removeSurrounding("\"").removeSurrounding("'").trim()
        if (id == "null" || id.isBlank()) return null
        return cache[id]
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
            selectedResult, channelRepo, emptyList(),
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

        session.dataFlowCache[selectedResult.stringId] = DataFlowCacheEntry(
            stringId = selectedResult.stringId,
            displayText = selectedResult.displayText,
            apiMethod = selectedResult.apiMethod,
            apiField = selectedResult.apiField,
            channelAliases = listOf(selectedResult.channelAlias),
            uiPath = session.uiFileHints,
            items = listOf(selectedResult)
        )

        return OrchestratorResponse.FinalAnswer(answer)
    }
}
