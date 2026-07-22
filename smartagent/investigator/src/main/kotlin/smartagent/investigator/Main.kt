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

    val CORPORATE = ModelConfig.CORPORATE
    if (Config.apiKey(CORPORATE) == null) {
        println("${CYAN}Не найден GPU_STACK_API_KEY в .properties$RESET")
        return
    }
    if (Config.apiUrl(CORPORATE).isBlank()) {
        println("${CYAN}Не найден GPU_STACK_URL в .properties$RESET")
        return
    }

    when (DockerChecker.check()) {
        DockerChecker.Result.NotInstalled -> {
            println("${CYAN}Docker не установлен.$RESET")
            println("Установите Docker Desktop: https://docs.docker.com/desktop/mac/install/")
            println("После установки запустите Docker Desktop и повторите запуск.")
            return
        }
        DockerChecker.Result.NotRunning -> {
            println("${CYAN}Docker установлен, но демон не запущен.$RESET")
            println("Запустите Docker Desktop и повторите.")
            return
        }
        DockerChecker.Result.Ok -> Unit
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
    val orchestrator = InvestigatorOrchestrator(config, githubSession, gateway, CORPORATE)
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
                    val t0 = System.currentTimeMillis()
                    val response = safeHandle { orchestrator.handleClarification(s.options[idx - 1], s.pendingQuery, session) }
                    state = processResponse(response, session, s.pendingQuery, System.currentTimeMillis() - t0, CORPORATE.apiModelId)
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
                    val t0 = System.currentTimeMillis()
                    val response = safeHandle {
                        orchestrator.handleChannelSearch(selectedAlias, s.pendingQuery, session)
                    }
                    state = processResponse(response, session, s.pendingQuery, System.currentTimeMillis() - t0, CORPORATE.apiModelId)
                } else {
                    println("${YELLOW}Не распознан канал. Введите номер или алиас из списка выше.$RESET")
                }
            }

            ReplState.Idle -> {
                println("${GRAY}Обрабатываю...$RESET")
                val t0 = System.currentTimeMillis()
                val response = safeHandle { orchestrator.handle(input, session) }
                state = processResponse(response, session, input, System.currentTimeMillis() - t0, CORPORATE.apiModelId)
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

private fun formatElapsed(ms: Long): String {
    return if (ms < 1000) "${ms}ms" else "${"%.1f".format(ms / 1000.0)}s"
}

private fun processResponse(
    response: OrchestratorResponse,
    session: InvestigatorSession,
    query: String,
    elapsedMs: Long = 0,
    modelId: String = ""
): ReplState {
    return when (response) {
        is OrchestratorResponse.FinalAnswer -> {
            if (response.isError) println(response.text) else println("✅ ${response.text}")
            println("${GRAY}$modelId | ${formatElapsed(elapsedMs)}$RESET")
            println()
            session.clear()
            session.addExchange(query, response.text)
            ReplState.Idle
        }
        is OrchestratorResponse.Rejected -> {
            println("$YELLOW🚫 ${response.reason}$RESET")
            println("${GRAY}$modelId | ${formatElapsed(elapsedMs)}$RESET")
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
