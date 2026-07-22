package smartagent.investigator

import smartagent.Config
import smartagent.ModelConfig
import smartagent.OkHttpLLMGateway
import smartagent.investigator.model.UiSearchResult
import smartagent.mcp_handler.McpManager

private const val PROMPT = "investigator> "
private const val RESET = "\u001B[0m"
private const val YELLOW = "\u001B[33m"
private const val CYAN = "\u001B[36m"
private const val GRAY = "\u001B[90m"

private val MODEL_PRIORITY = listOf(ModelConfig.DeepSeekFlash, ModelConfig.Qwen)

private sealed class ReplState {
    object Idle : ReplState()
    data class AwaitingUiClarification(val options: List<UiSearchResult>, val pendingQuery: String) : ReplState()
    data class AwaitingDefinitionSelection(val candidates: List<DefinitionCandidate>, val pendingQuery: String) : ReplState()
}

fun main() {
    val config = runCatching { InvestigatorConfig.load() }.getOrElse { e ->
        println("${CYAN}Ошибка конфигурации: ${e.message}$RESET")
        println("Проверьте local.properties: UI_REPO, INVASTIGATOR_OWNERR, GITHUB_CORP_TOKEN")
        return
    }

    val availableModels = MODEL_PRIORITY.filter {
        Config.apiKey(it) != null && Config.apiUrl(it).isNotBlank()
    }

    if (availableModels.isEmpty()) {
        println("${CYAN}Не найден ни один доступный API ключ (GPU_STACK_API_KEY + GPU_STACK_URL).$RESET")
        return
    }

    val primaryModel = availableModels.first()
    val fallbackModel = availableModels.getOrNull(1)

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
    val primaryOrchestrator = InvestigatorOrchestrator(config, githubSession, gateway, primaryModel)
    val fallbackOrchestrator = fallbackModel?.let { InvestigatorOrchestrator(config, githubSession, gateway, it) }
    val session = InvestigatorSession()

    println("${CYAN}Investigator готов. UI репозиторий: ${config.owner}/${config.uiRepo}$RESET")
    print("${GRAY}Модели: ${primaryModel.apiModelId}")
    if (fallbackModel != null) print(" → ${fallbackModel.apiModelId} (запасная)")
    println(RESET)
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
                    val (response, usedModel) = handleWithFallback(
                        primaryModel, primaryOrchestrator, fallbackModel, fallbackOrchestrator
                    ) { it.handleClarification(s.options[idx - 1], s.pendingQuery, session) }
                    state = processResponse(response, session, s.pendingQuery, System.currentTimeMillis() - t0, usedModel)
                } else {
                    println("${YELLOW}Введите номер от 1 до ${s.options.size}.$RESET")
                }
            }

            is ReplState.AwaitingDefinitionSelection -> {
                val idx = input.toIntOrNull()
                if (idx != null && idx in 1..s.candidates.size) {
                    state = ReplState.Idle
                    val t0 = System.currentTimeMillis()
                    val response = safeHandle {
                        primaryOrchestrator.handleDefinitionSelection(s.candidates[idx - 1], session)
                    }
                    state = processResponse(response, session, s.pendingQuery, System.currentTimeMillis() - t0, primaryModel)
                } else {
                    println("${YELLOW}Введите номер от 1 до ${s.candidates.size}.$RESET")
                }
            }

            ReplState.Idle -> {
                println("${GRAY}Обрабатываю...$RESET")
                val t0 = System.currentTimeMillis()
                val (response, usedModel) = handleWithFallback(
                    primaryModel, primaryOrchestrator, fallbackModel, fallbackOrchestrator
                ) { it.handle(input, session) }
                state = processResponse(response, session, input, System.currentTimeMillis() - t0, usedModel)
            }
        }
    }

    McpManager.getSession("github")?.close()
}

private fun handleWithFallback(
    primaryModel: ModelConfig,
    primaryOrchestrator: InvestigatorOrchestrator,
    fallbackModel: ModelConfig?,
    fallbackOrchestrator: InvestigatorOrchestrator?,
    block: (InvestigatorOrchestrator) -> OrchestratorResponse
): Pair<OrchestratorResponse, ModelConfig> {
    val primary = safeHandle { block(primaryOrchestrator) }
    if (primary is OrchestratorResponse.FinalAnswer && primary.isError
        && fallbackOrchestrator != null && fallbackModel != null
    ) {
        println("${CYAN}${primaryModel.apiModelId} не нашел ответ, отдал на обработку ${fallbackModel.apiModelId}$RESET")
        return safeHandle { block(fallbackOrchestrator) } to fallbackModel
    }
    return primary to primaryModel
}

private fun safeHandle(block: () -> OrchestratorResponse): OrchestratorResponse =
    runCatching(block).getOrElse { e ->
        System.err.println("[main] Orchestrator error: ${e.message}")
        OrchestratorResponse.FinalAnswer("Произошла ошибка: ${e.message}", isError = true)
    }

private fun formatElapsed(ms: Long): String =
    if (ms < 1000) "${ms}ms" else "${"%.1f".format(ms / 1000.0)}s"

private fun processResponse(
    response: OrchestratorResponse,
    session: InvestigatorSession,
    query: String,
    elapsedMs: Long = 0,
    usedModel: ModelConfig
): ReplState {
    return when (response) {
        is OrchestratorResponse.FinalAnswer -> {
            if (response.isError) println(response.text) else println("✅ ${response.text}")
            println("${GRAY}ответил ${usedModel.apiModelId}, время ответа: ${formatElapsed(elapsedMs)}$RESET")
            println()
            session.clear()
            session.addExchange(query, response.text)
            ReplState.Idle
        }
        is OrchestratorResponse.Rejected -> {
            println("$YELLOW🚫 ${response.reason}$RESET")
            println("${GRAY}ответил ${usedModel.apiModelId}, время ответа: ${formatElapsed(elapsedMs)}$RESET")
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
        is OrchestratorResponse.NeedsDefinitionSelection -> {
            println("${YELLOW}Найдено в нескольких каналах. Уточните, какой именно:$RESET")
            response.candidates.forEachIndexed { i, c ->
                println("  ${i + 1}. [${c.channelPrimaryName}] ${c.output.data.definitionPath}")
            }
            println("Введите номер:")
            ReplState.AwaitingDefinitionSelection(response.candidates, response.pendingQuery)
        }
    }
}
