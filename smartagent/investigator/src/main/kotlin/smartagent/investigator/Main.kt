package smartagent.investigator

import smartagent.Config
import smartagent.ModelConfig
import smartagent.OkHttpLLMGateway
import smartagent.investigator.model.UiSearchResult
import smartagent.mcp_handler.McpManager

private const val PROMPT = "investigator> "
private const val RESET = "[0m"
private const val YELLOW = "[33m"
private const val CYAN = "[36m"
private const val GRAY = "[90m"

private sealed class ReplState {
    object Idle : ReplState()
    data class AwaitingUiClarification(val options: List<UiSearchResult>, val pendingQuery: String) : ReplState()
    data class AwaitingChannelSelection(val availableChannels: List<String>, val pendingQuery: String) : ReplState()
}

fun main() {
    val config = runCatching { InvestigatorConfig.load() }.getOrElse { e ->
        println("${CYAN}Ошибка конфигурации: ${e.message}$RESET")
        println("Проверьте local.properties: UI_REPO, INVASTIGATOR_OWNERR, GITHUB_CORP_TOKEN")
        return
    }

    val minimax = ModelConfig.MINIMAX
    if (Config.apiKey(minimax) == null) {
        println("${CYAN}Не найден GPU_STACK_API_KEY в local.properties$RESET")
        return
    }

    println("${CYAN}Подключение к GitHub MCP...$RESET")
    val githubSession = runCatching {
        McpManager.initServer("github")
    }.getOrElse { e ->
        println("${CYAN}Не удалось подключиться к GitHub MCP: ${e.message}$RESET")
        println("Проверьте GITHUB_CORP_TOKEN и Docker.")
        return
    }

    val gateway = OkHttpLLMGateway()
    val orchestrator = InvestigatorOrchestrator(config, githubSession, gateway, minimax)
    val session = InvestigatorSession()

    println("${CYAN}Investigator готов. UI репозиторий: ${config.owner}/${config.uiRepo}$RESET")
    println("${GRAY}Команды: clear, exit/quit$RESET")
    println()

    var state: ReplState = ReplState.Idle

    while (true) {
        print(PROMPT)
        val input = readLine()?.trim() ?: break

        if (input.isBlank()) continue

        when (input.lowercase()) {
            "exit", "quit" -> {
                println("${GRAY}До свидания.$RESET")
                break
            }
            "clear" -> {
                print("Очистить контекст диалога? [y/N]: ")
                val confirm = readLine()?.trim()?.lowercase()
                if (confirm == "y" || confirm == "yes") {
                    session.clear()
                    state = ReplState.Idle
                    println("${GRAY}Контекст очищен.$RESET")
                } else {
                    println("${GRAY}Отмена.$RESET")
                }
                continue
            }
            else -> {}
        }

        when (val s = state) {
            is ReplState.AwaitingUiClarification -> {
                val idx = input.toIntOrNull()
                if (idx != null && idx in 1..s.options.size) {
                    state = ReplState.Idle
                    println("${GRAY}Ищу в канале...$RESET")
                    val response = safeHandle { orchestrator.handleClarification(s.options[idx - 1], s.pendingQuery, session) }
                    state = processResponse(response, session, s.pendingQuery)
                } else {
                    println("${YELLOW}Введите номер от 1 до ${s.options.size}.$RESET")
                }
            }

            is ReplState.AwaitingChannelSelection -> {
                // Accept either number (1..N) or channel alias directly
                val selectedAlias = resolveChannelInput(input, s.availableChannels)
                if (selectedAlias != null) {
                    state = ReplState.Idle
                    println("${GRAY}Ищу в канале $selectedAlias...$RESET")
                    val response = safeHandle {
                        orchestrator.handleChannelSearch(selectedAlias, s.pendingQuery, session)
                    }
                    state = processResponse(response, session, s.pendingQuery)
                } else {
                    println("${YELLOW}Не распознан канал. Введите номер или алиас из списка выше.$RESET")
                }
            }

            ReplState.Idle -> {
                println("${GRAY}Обрабатываю...$RESET")
                val response = safeHandle { orchestrator.handle(input, session) }
                state = processResponse(response, session, input)
            }
        }
    }

    McpManager.getSession("github")?.close()
}

private fun resolveChannelInput(input: String, channels: List<String>): String? {
    val idx = input.toIntOrNull()
    if (idx != null && idx in 1..channels.size) return channels[idx - 1]
    return channels.firstOrNull { it.equals(input.trim(), ignoreCase = true) }
}

private fun safeHandle(block: () -> OrchestratorResponse): OrchestratorResponse =
    runCatching(block).getOrElse { e ->
        System.err.println("[main] Orchestrator error: ${e.message}")
        OrchestratorResponse.FinalAnswer("Произошла ошибка: ${e.message}")
    }

private fun processResponse(
    response: OrchestratorResponse,
    session: InvestigatorSession,
    query: String
): ReplState {
    return when (response) {
        is OrchestratorResponse.FinalAnswer -> {
            println(response.text)
            println()
            session.clear()
            session.addExchange(query, response.text)
            ReplState.Idle
        }
        is OrchestratorResponse.Rejected -> {
            println("$YELLOW${response.reason}$RESET")
            println()
            ReplState.Idle
        }
        is OrchestratorResponse.NeedsClarification -> {
            println("${YELLOW}Найдено несколько подходящих элементов UI. Уточните, какой именно:$RESET")
            response.options.forEachIndexed { i, opt ->
                println("  ${i + 1}. \"${opt.displayText}\" (id: ${opt.stringId})")
            }
            println("Введите номер:")
            ReplState.AwaitingUiClarification(response.options, response.pendingQuery)
        }
        is OrchestratorResponse.NeedsChannelSelection -> {
            println("${YELLOW}Уточните, в каком канале искать:$RESET")
            response.availableChannels.forEachIndexed { i, alias ->
                println("  ${i + 1}. $alias")
            }
            println("Введите номер или название канала:")
            ReplState.AwaitingChannelSelection(response.availableChannels, response.pendingQuery)
        }
    }
}
