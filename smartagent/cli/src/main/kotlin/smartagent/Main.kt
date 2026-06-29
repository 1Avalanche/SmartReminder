package smartagent

import smartagent.architect.ArchitectOnboarding
import smartagent.architect.ArchitectOrchestrator
import smartagent.architect.ExecutionAgent
import smartagent.architect.FeatureRepository
import smartagent.architect.FeatureStatus
import smartagent.architect.IntentClassifier
import smartagent.architect.InvariantAgent
import smartagent.architect.PlanningAgent
import smartagent.architect.Stage
import smartagent.architect.TaskRepository
import smartagent.architect.TaskStatus
import smartagent.architect.ValidationAgent
import smartagent.agent.toolcalling.ToolCallingAgent
import smartagent.mcp_handler.AssistRepl
import smartagent.mcp_handler.McpManager
import java.io.File

fun main(args: Array<String>) {
    setupKeysIfNeeded()
    val parsedArgs = parseArgs(args)
    val session = ChatSession()
    session.switchModel(parsedArgs.model ?: Config.loadLastModel() ?: ModelConfig.DEEPSEEK)

    parsedArgs.repoPath?.let { path ->
        val dir = File(path).canonicalFile
        if (dir.isDirectory) {
            session.repoContext = RepoContext(dir)
            println("${Colors.LIGHT_GREEN}Repo: ${dir.absolutePath}${Colors.RESET}")
        } else {
            println("${Colors.LIGHT_YELLOW}Warning: repo path not found: $path${Colors.RESET}")
        }
    }

    val targetMode = parsedArgs.initialMode ?: AgentMode.INDEX
    if (session.currentMode != targetMode) session.switchMode(targetMode)

    val client = ChatClient(session)
    val architectOnboarding = ArchitectOnboarding()
    val featureRepository = FeatureRepository()
    val taskRepository = TaskRepository()
    val gateway = OkHttpLLMGateway()
    val invariantAgent = InvariantAgent(session.config, session.tokens, gateway)
    val intentClassifier = IntentClassifier(session.config, featureRepository, taskRepository, gateway)
    val planningAgent = PlanningAgent(session.config, session.tokens, taskRepository, gateway)
    val executionAgent = ExecutionAgent(session.config, session.tokens, taskRepository, gateway)
    val validationAgent = ValidationAgent(session.config, session.tokens, taskRepository, gateway)
    val architectOrchestrator = ArchitectOrchestrator(session, featureRepository, taskRepository, invariantAgent, planningAgent, executionAgent, validationAgent, gateway, session.config, session.tokens)

    McpManager.initRemoteServers()

    when (session.currentMode) {
        AgentMode.ARCHITECT -> {
            println("${Colors.DARK_GRAY}Model: ${session.currentModel.shortName} | Mode: ${session.currentMode.displayName}")
            println("Type /help for commands, /exit to quit.${Colors.RESET}\n")
            architectOnboarding.startSession(featureRepository.getActiveFeature() != null)
        }
        AgentMode.ASSIST -> {
            println("${Colors.LIGHT_YELLOW}SmartAgent — Assist mode${Colors.RESET}")
            println("${Colors.DARK_GRAY}Type '/mcp list' to see servers, /help for all commands, /exit to quit.${Colors.RESET}\n")
        }
        else -> {
            println("${Colors.LIGHT_YELLOW}SmartAgent готов к работе!${Colors.RESET}")
            println("${Colors.DARK_GRAY}Model: ${session.currentModel.shortName} | Mode: ${session.currentMode.displayName}")
            println("Type /help for commands, /exit to quit.${Colors.RESET}\n")
        }
    }

    runRepl(session, client, architectOnboarding, architectOrchestrator, featureRepository, taskRepository, intentClassifier, invariantAgent, gateway)
}

private data class ParsedArgs(
    val model: ModelConfig? = null,
    val repoPath: String? = null,
    val initialMode: AgentMode? = null
)

private fun parseArgs(args: Array<String>): ParsedArgs {
    var model: ModelConfig? = null
    var repoPath: String? = null
    var initialMode: AgentMode? = null
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--model" -> { model = ModelConfig.fromName(args.getOrElse(++i) { "" }); i++ }
            "--repo" -> { repoPath = args.getOrElse(++i) { "" }; i++ }
            "assist"  -> { initialMode = AgentMode.ASSIST; i++ }
            else -> i++
        }
    }
    return ParsedArgs(model, repoPath, initialMode)
}

private fun runRepl(
    session: ChatSession,
    client: ChatClient,
    architectOnboarding: ArchitectOnboarding,
    architectOrchestrator: ArchitectOrchestrator,
    featureRepository: FeatureRepository,
    taskRepository: TaskRepository,
    intentClassifier: IntentClassifier,
    invariantAgent: InvariantAgent,
    gateway: LLMGateway
) {
    var indexPath: String? = null
    var indexStrategy = "fixed"

    while (true) {
        print("${Colors.BRIGHT_WHITE}> ")
        System.out.flush()
        val input = readlnOrNull() ?: break
        if (input.isBlank()) continue

        when {
            input == "/exit" || input == "/quit" -> { McpManager.shutdown(); println("${Colors.LIGHT_YELLOW}Goodbye!${Colors.RESET}"); break }
            input == "/help" -> { showHelp(); println() }
            input == "/history" || input == "/hist" -> showHistory(session)
            input == "/clear" || input == "/new" -> {
                if (session.currentMode == AgentMode.ARCHITECT) {
                    session.clear()
                    architectOnboarding.printHelloSecond()
                } else {
                    session.clear()
                    ToolCallingAgent.clearHistory()
                    println("${Colors.LIGHT_YELLOW}Chat history cleared.${Colors.RESET}")
                }
            }
            input == "/clearAll" -> {
                print("${Colors.LIGHT_YELLOW}Очистить все данные проекта (все решении и знания буду утеряны)? [y/N]: ${Colors.RESET}")
                System.out.flush()
                val confirm = readlnOrNull()?.trim()?.lowercase()
                if (confirm == "y" || confirm == "yes") {
                    architectOnboarding.clearAll(session)
                    featureRepository.clearAll()
                    taskRepository.clearAll()
                    invariantAgent.clearUserInvariants()
                    session.clear()
                    println("${Colors.LIGHT_YELLOW}Все данные проекта очищены.${Colors.RESET}")
                } else {
                    println("${Colors.DARK_GRAY}Отменено.${Colors.RESET}")
                }
            }
            input == "/models" -> listModels(session)
            input.startsWith("/model ") -> switchModel(session, input.removePrefix("/model ").trim())
            input == "/repo" -> showRepo(session)
            input.startsWith("/repo ") -> setRepo(session, input.removePrefix("/repo ").trim())
            input == "/files" -> listFiles(session, null)
            input.startsWith("/files ") -> listFiles(session, input.removePrefix("/files ").trim())
            input == "/tree" -> showTree(session, 3)
            input.startsWith("/tree ") -> showTree(session, input.removePrefix("/tree ").trim().toIntOrNull() ?: 3)
            input.startsWith("/read ") -> readFile(session, input.removePrefix("/read ").trim())
            input == "/context" -> showContext(session)
            input == "/context clear" -> { session.clearFileContext(); println("${Colors.LIGHT_YELLOW}File context cleared.${Colors.RESET}") }
            input == "/mode" -> showMode(session)
            input.startsWith("/mode ") -> {
                val modeName = input.removePrefix("/mode ").trim()
                switchMode(session, modeName)
                ToolCallingAgent.clearHistory()
                when (session.currentMode) {
                    AgentMode.ARCHITECT -> architectOnboarding.startSession(featureRepository.getActiveFeature() != null)
                    AgentMode.ASSIST -> println("${Colors.DARK_GRAY}Assist mode. Type '/mcp list' to start.${Colors.RESET}")
                    AgentMode.INDEX -> println("${Colors.DARK_GRAY}Index mode. Set /index-path, /index-strategy, /index-output, then /index-run.${Colors.RESET}")
                    else -> {}
                }
            }
            input == "/totalTokens" -> showTotalTokens(session)
            input == "/memory" -> showMemory(architectOnboarding)
            input == "/profile" -> showProfile(session)
            input.startsWith("/analyze ") -> {
                val (path, prompt) = parseAnalyzeArgs(input.removePrefix("/analyze ").trim())
                analyzeCode(session, client, path, prompt)
            }
            // Feature commands
            input == "/features" -> showFeatures(featureRepository, taskRepository)
            input.startsWith("/feature create ") -> createFeature(featureRepository, input.removePrefix("/feature create ").trim())
            input == "/feature current" -> showCurrentFeature(featureRepository)
            input.startsWith("/feature switch ") -> switchFeature(featureRepository, taskRepository, input.removePrefix("/feature switch ").trim())
            input == "/feature state" -> showFeatureState(featureRepository, taskRepository)
            input == "/feature pause" -> pauseActiveFeature(featureRepository)
            input == "/feature resume" -> resumeActiveFeature(featureRepository)
            input == "/feature info" -> showFeatureInfo(featureRepository, taskRepository)
            // Invariant commands
            input == "/invariants" -> showUserInvariants(invariantAgent)
            // Status and diagnostic commands
            input == "/status" -> showStatus(featureRepository, taskRepository)
            input.startsWith("/classify ") -> classifyDiagnostic(intentClassifier, input.removePrefix("/classify ").trim())
            // Debug commands (not shown in help)
            input == "/debug tasks" -> debugTasks(featureRepository, taskRepository)
            input == "/debug task current" -> debugCurrentTask(featureRepository, taskRepository)
            input == "/debug task history" -> debugTaskHistory(featureRepository, taskRepository)
            input == "/debug task artifact" -> debugTaskArtifact(featureRepository, taskRepository)
            input == "/debug task review" -> debugTaskReview(featureRepository, taskRepository)
            // Index mode commands
            input == "/index-status" -> showIndexStatus(indexPath, indexStrategy)
            input.startsWith("/index-path ") -> {
                indexPath = input.removePrefix("/index-path ").trim()
                println("${Colors.LIGHT_GREEN}Path set: $indexPath${Colors.RESET}")
            }
            input.startsWith("/index-strategy ") -> {
                val strategy = input.removePrefix("/index-strategy ").trim()
                val validStrategies = setOf("fixed", "structured")
                if (strategy !in validStrategies) {
                    println("${Colors.LIGHT_YELLOW}Unknown strategy: $strategy. Valid: ${validStrategies.joinToString("|")}${Colors.RESET}")
                } else {
                    indexStrategy = strategy
                    println("${Colors.LIGHT_GREEN}Strategy set: $indexStrategy${Colors.RESET}")
                }
            }
            input == "/index-run" -> runIndexing(indexPath, indexStrategy)
            // MCP commands — available in any mode
            input == "/mcp" || input.startsWith("/mcp ") -> AssistRepl.handle(input.removePrefix("/"))
            input.startsWith("/") -> println("${Colors.LIGHT_YELLOW}Unknown command: $input${Colors.RESET}")
            else -> {
                when (session.currentMode) {
                    AgentMode.ARCHITECT -> architectOrchestrator.process(input)
                    AgentMode.ASSIST    -> ToolCallingAgent.handle(input, gateway, session.currentModel)
                    AgentMode.INDEX     -> println("${Colors.DARK_GRAY}Use /index-run to start. See /index-status for current settings.${Colors.RESET}")
                    else                -> client.sendMessage(input)
                }
            }
        }
    }
}

private fun showHelp() {
    println(Colors.LIGHT_YELLOW + """
Commands:
  /exit, /quit                    Exit the program
  /help                           Show this help
  /history, /hist                 Show full chat history (JSON)
  /clear, /new                    Clear chat history and file context
  /clearAll                       Clear all project data
  /models                         List available models
  /model <name>                   Switch model (deepseek, qwen, qwen-low)
  /repo                           Show current repo path
  /repo <path>                    Set repo path
  /files [pattern]                List repo files (optional filter)
  /tree [depth]                   Show repo file tree (default depth: 3)
  /read <file>                    Load file into context (relative to repo root)
  /context                        Show files loaded in context
  /context clear                  Remove all files from context
  /mode                           Show current mode
  /mode <name>                    Switch mode (chat, code-analyzer, architect, assist, index)
  /memory                         Show arch_settings.md and arch_tasks.json
  /profile                        Show user_profile.md
  /totalTokens                    Show token usage per request + total sum
  /analyze <path> [prompt]        Collect all text files from path and send for analysis

Index mode commands (/mode index):
  /index-path <path>              Set directory to index
  /index-strategy fixed|structured  Set chunking strategy (default: fixed)
  /index-status                   Show current settings and output path
  /index-run                      Run indexing (saves to <path>/.indexed/<strategy>.json)

Project commands:
  /features                       List all projects
  /feature create <title>         Create project and make it active
  /feature current                Show active project
  /feature switch <id>            Switch active project
  /feature state                  Show project overview
  /feature pause                  Pause active project
  /feature resume                 Resume a paused project
  /feature info                   Show project details

MCP commands (any mode):
  /mcp list                       List registered MCP servers and status
  /mcp <name> init                Start and connect to a server
  /mcp <name> tools               List tools exposed by server
  /mcp <name> stop                Disconnect from server

Other:
  /status                         Show what's happening right now
  /classify <message>             Diagnose intent without changing state
  <message>                       Send a message (architect mode: guided dialog)
    """.trimIndent() + Colors.RESET)
}

private fun showHistory(session: ChatSession) {
    val history = session.getHistory()
    if (history.isEmpty()) { println("Chat history is empty."); return }
    history.forEachIndexed { i, entry ->
        println("--- Exchange ${i + 1} ---")
        println("${Colors.LIGHT_GREEN}Пользователь ввел:${Colors.RESET} ${entry.userInput}")
        println("${Colors.LIGHT_YELLOW}В запрос ушло:${Colors.RESET} ${entry.requestPayload}")
        println("${Colors.LIGHT_VIOLET}Ответ от API:${Colors.RESET} ${entry.apiResponse}")
        println()
    }
}

private fun listModels(session: ChatSession) {
    println(Colors.LIGHT_YELLOW + "Available models names:" + Colors.RESET)
    ModelConfig.entries.forEachIndexed { index, model ->
        val check = if (model == session.currentModel) "✅ " else ""
        val ctx = "%,d".format(model.contextWindow)
        println(Colors.LIGHT_YELLOW + "  ${index + 1}. $check${model.shortName}  (context: $ctx tokens)" + Colors.RESET)
        println(Colors.LIGHT_GRAY + "     ${model.description}" + Colors.RESET)
    }
    println()
}

private fun switchModel(session: ChatSession, name: String) {
    val found = ModelConfig.fromName(name)
    if (found != null) {
        session.switchModel(found)
        Config.saveLastModel(found)
        println("${Colors.LIGHT_YELLOW}Switched to model: ${session.currentModel.shortName}${Colors.RESET}")
    } else {
        println("${Colors.LIGHT_YELLOW}Unknown model: $name. Type /models to see available models.${Colors.RESET}")
    }
}

private fun showRepo(session: ChatSession) {
    val repo = session.repoContext
    if (repo == null) println("${Colors.LIGHT_YELLOW}No repo set. Use /repo <path>${Colors.RESET}")
    else println("${Colors.LIGHT_GREEN}Repo: ${repo.root.absolutePath}${Colors.RESET}")
}

private fun setRepo(session: ChatSession, path: String) {
    val dir = File(path).canonicalFile
    if (!dir.isDirectory) {
        println("${Colors.LIGHT_YELLOW}Not a directory: $path${Colors.RESET}")
        return
    }
    session.repoContext = RepoContext(dir)
    session.clearFileContext()
    println("${Colors.LIGHT_GREEN}Repo set: ${dir.absolutePath}${Colors.RESET}")
}

private fun listFiles(session: ChatSession, pattern: String?) {
    val repo = session.repoContext ?: run {
        println("${Colors.LIGHT_YELLOW}No repo set. Use /repo <path>${Colors.RESET}")
        return
    }
    val files = repo.listFiles(pattern)
    if (files.isEmpty()) {
        println("${Colors.LIGHT_YELLOW}No files found${if (pattern != null) " matching '$pattern'" else ""}.${Colors.RESET}")
        return
    }
    files.forEach { println("${Colors.LIGHT_GRAY}  $it${Colors.RESET}") }
    println("${Colors.DARK_GRAY}  (${files.size} files)${Colors.RESET}")
}

private fun showTree(session: ChatSession, depth: Int) {
    val repo = session.repoContext ?: run {
        println("${Colors.LIGHT_YELLOW}No repo set. Use /repo <path>${Colors.RESET}")
        return
    }
    println(Colors.LIGHT_GRAY + repo.fileTree(depth) + Colors.RESET)
}

private fun readFile(session: ChatSession, relativePath: String) {
    val repo = session.repoContext ?: run {
        println("${Colors.LIGHT_YELLOW}No repo set. Use /repo <path>${Colors.RESET}")
        return
    }
    val content = repo.readFile(relativePath) ?: run {
        println("${Colors.LIGHT_YELLOW}File not found: $relativePath${Colors.RESET}")
        return
    }
    session.addFileToContext(relativePath, content)
    println("${Colors.LIGHT_GREEN}Loaded: $relativePath (${content.length} chars)${Colors.RESET}")
}

private fun parseAnalyzeArgs(args: String): Pair<String, String?> {
    if (args.startsWith("\"")) {
        val end = args.indexOf('"', 1)
        if (end > 0) {
            val path = args.substring(1, end)
            val prompt = args.substring(end + 1).trim().takeIf { it.isNotEmpty() }
            return Pair(path, prompt)
        }
    }
    val spaceIdx = args.indexOf(' ')
    if (spaceIdx < 0) return Pair(args, null)
    return Pair(args.substring(0, spaceIdx), args.substring(spaceIdx + 1).trim().takeIf { it.isNotEmpty() })
}

private fun analyzeCode(session: ChatSession, client: ChatClient, rawPath: String, userPrompt: String?) {
    val target = FileScanner.resolvePath(rawPath, session.repoContext?.root)
    if (target == null) {
        println("${Colors.LIGHT_YELLOW}Path not found: $rawPath${Colors.RESET}")
        return
    }

    val collected = FileScanner(target).collectWithContent()

    if (collected.isEmpty()) {
        println("${Colors.LIGHT_YELLOW}No text files found in: ${target.absolutePath}${Colors.RESET}")
        return
    }

    val totalChars = collected.sumOf { it.second.length }
    println("${Colors.DARK_GRAY}Collecting ${collected.size} file(s), ~$totalChars chars...${Colors.RESET}")

    val prefix = userPrompt ?: "Проанализируй следующий код:"
    val sb = StringBuilder("$prefix\n\n")
    collected.forEach { (path, content) ->
        sb.appendLine("### $path")
        sb.appendLine("```")
        sb.appendLine(content)
        sb.appendLine("```")
        sb.appendLine()
    }

    client.sendMessage(sb.toString().trimEnd())
}

private fun showIndexStatus(path: String?, strategy: String) {
    val outputHint = if (path != null) "$path/.indexed/$strategy.json" else "(set path first)"
    println("${Colors.LIGHT_YELLOW}Index settings:${Colors.RESET}")
    println("${Colors.DARK_GRAY}  path     : ${path ?: "(not set)"}${Colors.RESET}")
    println("${Colors.DARK_GRAY}  strategy : $strategy${Colors.RESET}")
    println("${Colors.DARK_GRAY}  output   : $outputHint${Colors.RESET}")
}

private fun runIndexing(path: String?, strategy: String) {
    if (path == null) {
        println("${Colors.LIGHT_YELLOW}Set path first: /index-path <dir>${Colors.RESET}")
        return
    }
    IndexCommandHandler().handle(arrayOf("--path", path, "--strategy", strategy))
}

private fun showMode(session: ChatSession) {
    println("${Colors.LIGHT_YELLOW}Current mode: ${session.currentMode.displayName}${Colors.RESET}")
    println("${Colors.DARK_GRAY}Available: ${AgentMode.entries.joinToString(", ") { it.displayName }}${Colors.RESET}")
}

private fun switchMode(session: ChatSession, name: String) {
    val found = AgentMode.fromName(name)
    if (found != null) {
        session.switchMode(found)
        println("${Colors.LIGHT_YELLOW}Switched to mode: ${found.displayName} (история очищена)${Colors.RESET}")
    } else {
        println("${Colors.LIGHT_YELLOW}Unknown mode: $name. Available: ${AgentMode.entries.joinToString(", ") { it.displayName }}${Colors.RESET}")
    }
}

private fun showTotalTokens(session: ChatSession) {
    val entries = session.getTokenEntries()
    if (entries.isEmpty()) { println("${Colors.LIGHT_YELLOW}No token data yet.${Colors.RESET}"); return }
    entries.forEach {
        println("${Colors.LIGHT_YELLOW}#${it.request}  prompt: ${it.prompt} | completion: ${it.completion} | total: ${it.total}${Colors.RESET}")
    }
    val sumTotal = entries.sumOf { it.total }
    println("${Colors.LIGHT_YELLOW}─────────────────────────────")
    println("Total tokens used: $sumTotal${Colors.RESET}")
}

private fun showProfile(session: ChatSession) {
    val profile = session.loadUserProfile()
    println()
    println("${Colors.LIGHT_YELLOW}═══ user_profile.md ═══${Colors.RESET}")
    if (profile.isEmpty()) println("${Colors.DARK_GRAY}(пусто — профиль появится после 3-го сообщения)${Colors.RESET}")
    else println("${Colors.LIGHT_YELLOW}$profile${Colors.RESET}")
    println()
}

private fun showMemory(onboarding: ArchitectOnboarding) {
    val longMemory = runCatching { onboarding.longMemoryFile.readText().trim() }.getOrElse { "" }
    val workMemory = onboarding.buildWorkMemoryText()

    println()
    println("${Colors.LIGHT_YELLOW}═══ arch_settings.md ═══${Colors.RESET}")
    if (longMemory.isEmpty()) println("${Colors.DARK_GRAY}(пусто)${Colors.RESET}")
    else println("${Colors.LIGHT_YELLOW}$longMemory${Colors.RESET}")

    println()
    println("${Colors.LIGHT_YELLOW}═══ arch_tasks.json ═══${Colors.RESET}")
    if (workMemory.isEmpty()) println("${Colors.DARK_GRAY}(пусто)${Colors.RESET}")
    else println("${Colors.LIGHT_YELLOW}$workMemory${Colors.RESET}")
    println()
}

private fun showContext(session: ChatSession) {
    val paths = session.getFileContextPaths()
    if (paths.isEmpty()) {
        println("${Colors.LIGHT_YELLOW}No files in context. Use /read <file>${Colors.RESET}")
        return
    }
    println("${Colors.LIGHT_YELLOW}Files in context:${Colors.RESET}")
    paths.forEach { println("${Colors.LIGHT_GRAY}  $it${Colors.RESET}") }
}

// ─── Feature commands ─────────────────────────────────────────────────────────

private fun showFeatures(repo: FeatureRepository, taskRepo: TaskRepository) {
    val all = repo.getAllFeatures()
    if (all.isEmpty()) {
        println("${Colors.DARK_GRAY}Нет фич. Создай: /feature create <название>${Colors.RESET}")
        return
    }
    val grouped = all.groupBy { it.status }
    listOf(FeatureStatus.ACTIVE, FeatureStatus.PAUSED, FeatureStatus.COMPLETED).forEach { status ->
        val features = grouped[status] ?: return@forEach
        println("${Colors.LIGHT_YELLOW}[${status.name}]${Colors.RESET}")
        features.forEach { f ->
            val tasks = taskRepo.getTasksForFeature(f.id)
            val done = tasks.count { it.status == TaskStatus.COMPLETED }
            val total = tasks.size
            println("${Colors.LIGHT_GRAY}  ${f.id} | ${f.title}  ($done/$total tasks done)${Colors.RESET}")
        }
    }
    println()
}

private fun createFeature(repo: FeatureRepository, title: String) {
    if (title.isBlank()) {
        println("${Colors.LIGHT_YELLOW}Укажи название: /feature create <название>${Colors.RESET}")
        return
    }
    val feature = repo.createFeature(title)
    println("${Colors.LIGHT_GREEN}Создана фича: ${feature.id} | ${feature.title}${Colors.RESET}")
    println("${Colors.DARK_GRAY}Создай задачу: /task new <описание>${Colors.RESET}")
}

private fun showCurrentFeature(repo: FeatureRepository) {
    val feature = repo.getActiveFeature()
    if (feature == null) {
        println("${Colors.LIGHT_YELLOW}Нет активной фичи. Создай: /feature create <название>${Colors.RESET}")
        return
    }
    println("${Colors.LIGHT_YELLOW}Активная фича:${Colors.RESET}")
    println("${Colors.LIGHT_GREEN}${feature.id} | ${feature.title}${Colors.RESET}")
}

private fun switchFeature(repo: FeatureRepository, taskRepo: TaskRepository, id: String) {
    if (id.isBlank()) {
        println("${Colors.LIGHT_YELLOW}Укажи id: /feature switch <id>${Colors.RESET}")
        return
    }
    val target = repo.getFeature(id)
    if (target == null) {
        println("${Colors.LIGHT_YELLOW}Фича не найдена: $id${Colors.RESET}")
        return
    }
    if (target.status == FeatureStatus.COMPLETED) {
        println("${Colors.LIGHT_YELLOW}Фича завершена, нельзя переключить на COMPLETED-фичу.${Colors.RESET}")
        return
    }
    val prev = repo.getActiveFeature()
    repo.setActiveFeature(id)

    if (prev != null && prev.id != id) {
        println("${Colors.DARK_GRAY}${prev.id} | ${prev.title} → PAUSED${Colors.RESET}")
    }
    println("${Colors.LIGHT_GREEN}Активный проект: ${target.id} | ${target.title}${Colors.RESET}")
    val activeTask = taskRepo.getActiveTaskForFeature(id)
    if (activeTask != null) {
        println("${Colors.DARK_GRAY}Продолжаем: ${activeTask.title} | ${activeTask.stage.displayName()}${Colors.RESET}")
    }
}

private fun showFeatureState(repo: FeatureRepository, taskRepo: TaskRepository) {
    val feature = repo.getActiveFeature() ?: run {
        println("${Colors.LIGHT_YELLOW}Нет активной фичи.${Colors.RESET}")
        return
    }
    println()
    println("${Colors.LIGHT_YELLOW}Фича: ${feature.id} | ${feature.title}${Colors.RESET}")
    println("${Colors.LIGHT_YELLOW}Статус: ${feature.status}${Colors.RESET}")
    if (feature.summary.isNotBlank()) {
        println("${Colors.LIGHT_YELLOW}Summary: ${feature.summary}${Colors.RESET}")
    }
    println()
    val tasks = taskRepo.getTasksForFeature(feature.id)
    if (tasks.isEmpty()) {
        println("${Colors.DARK_GRAY}Нет задач. Опишите задачу в чате.${Colors.RESET}")
    } else {
        println("${Colors.LIGHT_YELLOW}Задачи:${Colors.RESET}")
        tasks.forEach { t ->
            val marker = if (t.status == TaskStatus.COMPLETED) "✓" else "→"
            println("${Colors.LIGHT_GRAY}  $marker ${t.title} | ${t.stage.displayName()}${Colors.RESET}")
        }
    }
    println()
}

private fun pauseActiveFeature(repo: FeatureRepository) {
    val feature = repo.getActiveFeature() ?: run {
        println("${Colors.LIGHT_YELLOW}Нет активной фичи.${Colors.RESET}")
        return
    }
    repo.updateFeature(feature.copy(status = FeatureStatus.PAUSED))
    println("${Colors.LIGHT_YELLOW}${feature.title} → PAUSED${Colors.RESET}")
}

private fun resumeActiveFeature(repo: FeatureRepository) {
    val all = repo.getAllFeatures()
    val paused = all.filter { it.status == FeatureStatus.PAUSED }
    if (paused.isEmpty()) {
        println("${Colors.LIGHT_YELLOW}Нет приостановленных фич.${Colors.RESET}")
        return
    }
    if (paused.size > 1) {
        println("${Colors.LIGHT_YELLOW}Несколько приостановленных фич. Используй /feature switch <id>:${Colors.RESET}")
        paused.forEach { f ->
            println("${Colors.LIGHT_GRAY}  ${f.id} | ${f.title}${Colors.RESET}")
        }
        return
    }
    val feature = paused.first()
    repo.setActiveFeature(feature.id)
    println("${Colors.LIGHT_GREEN}${feature.title} → ACTIVE${Colors.RESET}")
}

private fun showFeatureInfo(repo: FeatureRepository, taskRepo: TaskRepository) {
    val feature = repo.getActiveFeature() ?: run {
        println("${Colors.LIGHT_YELLOW}Нет активной фичи.${Colors.RESET}")
        return
    }
    println()
    println("${Colors.LIGHT_YELLOW}Фича:${Colors.RESET} ${feature.id} | ${feature.title}")
    println("${Colors.LIGHT_YELLOW}Статус:${Colors.RESET} ${feature.status}")
    if (feature.summary.isNotBlank()) {
        println("${Colors.LIGHT_YELLOW}Summary:${Colors.RESET} ${feature.summary}")
    }
    println("${Colors.LIGHT_YELLOW}Создана:${Colors.RESET} ${feature.createdAt}")
    println()
    val tasks = taskRepo.getTasksForFeature(feature.id)
    if (tasks.isNotEmpty()) {
        println("${Colors.LIGHT_YELLOW}Задачи (${tasks.size}):${Colors.RESET}")
        tasks.forEach { t ->
            val marker = if (t.status == TaskStatus.COMPLETED) "✓" else "→"
            println("${Colors.LIGHT_GRAY}  $marker ${t.title} | ${t.stage.displayName()}${Colors.RESET}")
        }
    }
    println()
}

// ─── Invariants ───────────────────────────────────────────────────────────────

private fun showUserInvariants(invariantAgent: InvariantAgent) {
    val text = invariantAgent.getUserInvariants()
    if (text.isEmpty()) {
        println("${Colors.DARK_GRAY}Пользовательские инварианты не заданы.${Colors.RESET}")
    } else {
        println("${Colors.LIGHT_YELLOW}Пользовательские инварианты:${Colors.RESET}")
        println(text)
    }
    println()
}

// ─── Status and diagnostic ────────────────────────────────────────────────────

private fun showStatus(featureRepo: FeatureRepository, taskRepo: TaskRepository) {
    val feature = featureRepo.getActiveFeature()
    if (feature == null) {
        println("${Colors.LIGHT_YELLOW}Нет активного проекта.${Colors.RESET}")
        return
    }
    val task = taskRepo.getActiveTaskForFeature(feature.id)
    println()
    println("${Colors.LIGHT_YELLOW}Проект:${Colors.RESET}")
    println("${Colors.LIGHT_GREEN}${feature.title}${Colors.RESET}")
    if (task != null) {
        println()
        println("${Colors.LIGHT_YELLOW}Сейчас работаем над:${Colors.RESET}")
        println("${Colors.LIGHT_GREEN}${task.title}${Colors.RESET}")
        println()
        println("${Colors.LIGHT_YELLOW}Этап:${Colors.RESET}")
        println("${Colors.LIGHT_GREEN}${task.stage.displayName()}${Colors.RESET}")
        if (task.expectedAction != null) {
            println()
            println("${Colors.LIGHT_YELLOW}Ожидается:${Colors.RESET}")
            println("${Colors.LIGHT_GREEN}${task.expectedAction}${Colors.RESET}")
        }
    } else {
        println()
        println("${Colors.DARK_GRAY}Нет активной работы. Опишите задачу в чате.${Colors.RESET}")
    }
    println()
}

// ─── Debug commands (not shown in help) ───────────────────────────────────────

private fun debugTasks(featureRepo: FeatureRepository, taskRepo: TaskRepository) {
    val feature = featureRepo.getActiveFeature() ?: run {
        println("${Colors.DARK_GRAY}[debug] no active feature${Colors.RESET}")
        return
    }
    val tasks = taskRepo.getTasksForFeature(feature.id)
    println()
    println("${Colors.DARK_GRAY}[debug] feature=${feature.id} | tasks=${tasks.size}${Colors.RESET}")
    tasks.forEach { t ->
        val marker = when (t.status) {
            TaskStatus.ACTIVE -> "▶"
            TaskStatus.COMPLETED -> "✓"
            TaskStatus.PAUSED -> "‖"
        }
        println("${Colors.DARK_GRAY}  $marker ${t.id} | ${t.title} | ${t.stage} | ${t.status}${Colors.RESET}")
    }
    println()
}

private fun debugCurrentTask(featureRepo: FeatureRepository, taskRepo: TaskRepository) {
    val feature = featureRepo.getActiveFeature() ?: run {
        println("${Colors.DARK_GRAY}[debug] no active feature${Colors.RESET}")
        return
    }
    val task = taskRepo.getActiveTaskForFeature(feature.id) ?: run {
        println("${Colors.DARK_GRAY}[debug] no active task for ${feature.id}${Colors.RESET}")
        return
    }
    println()
    println("${Colors.DARK_GRAY}[debug] task=${task.id} | featureId=${task.featureId}${Colors.RESET}")
    println("${Colors.DARK_GRAY}title=${task.title}${Colors.RESET}")
    println("${Colors.DARK_GRAY}status=${task.status} | stage=${task.stage}${Colors.RESET}")
    if (task.currentStep != null) println("${Colors.DARK_GRAY}currentStep=${task.currentStep}${Colors.RESET}")
    if (task.expectedAction != null) println("${Colors.DARK_GRAY}expectedAction=${task.expectedAction}${Colors.RESET}")
    if (task.summary.isNotBlank()) println("${Colors.DARK_GRAY}summary=${task.summary}${Colors.RESET}")
    println()
}

private fun debugTaskHistory(featureRepo: FeatureRepository, taskRepo: TaskRepository) {
    val feature = featureRepo.getActiveFeature() ?: run {
        println("${Colors.DARK_GRAY}[debug] no active feature${Colors.RESET}")
        return
    }
    val task = taskRepo.getActiveTaskForFeature(feature.id) ?: run {
        println("${Colors.DARK_GRAY}[debug] no active task${Colors.RESET}")
        return
    }
    val history = taskRepo.getHistory(task.id)
    println()
    println("${Colors.DARK_GRAY}[debug] history for ${task.id}${Colors.RESET}")
    println()
    if (history.isEmpty()) println("${Colors.DARK_GRAY}(empty)${Colors.RESET}")
    else println("${Colors.DARK_GRAY}$history${Colors.RESET}")
    println()
}

private fun debugTaskArtifact(featureRepo: FeatureRepository, taskRepo: TaskRepository) {
    val feature = featureRepo.getActiveFeature() ?: run {
        println("${Colors.DARK_GRAY}[debug] no active feature${Colors.RESET}")
        return
    }
    val task = taskRepo.getActiveTaskForFeature(feature.id) ?: run {
        println("${Colors.DARK_GRAY}[debug] no active task${Colors.RESET}")
        return
    }
    val artifact = taskRepo.getArchitecture(task.id)
    println()
    println("${Colors.DARK_GRAY}[debug] artifact for ${task.id}${Colors.RESET}")
    println()
    if (artifact.isBlank()) println("${Colors.DARK_GRAY}(empty)${Colors.RESET}")
    else println("${Colors.DARK_GRAY}$artifact${Colors.RESET}")
    println()
}

private fun debugTaskReview(featureRepo: FeatureRepository, taskRepo: TaskRepository) {
    val feature = featureRepo.getActiveFeature() ?: run {
        println("${Colors.DARK_GRAY}[debug] no active feature${Colors.RESET}")
        return
    }
    val task = taskRepo.getActiveTaskForFeature(feature.id) ?: run {
        println("${Colors.DARK_GRAY}[debug] no active task${Colors.RESET}")
        return
    }
    val review = taskRepo.getReview(task.id)
    println()
    println("${Colors.DARK_GRAY}[debug] review for ${task.id}${Colors.RESET}")
    println()
    if (review.isBlank()) println("${Colors.DARK_GRAY}(empty)${Colors.RESET}")
    else println("${Colors.DARK_GRAY}$review${Colors.RESET}")
    println()
}

private fun classifyDiagnostic(classifier: IntentClassifier, message: String) {
    if (message.isBlank()) {
        println("${Colors.LIGHT_YELLOW}Usage: /classify <message>${Colors.RESET}")
        return
    }
    println("${Colors.DARK_GRAY}Classifying...${Colors.RESET}")
    val result = classifier.classify(message)
    if (result == null) {
        println("${Colors.LIGHT_YELLOW}Classification failed.${Colors.RESET}")
        return
    }
    val featureIdLine = result.featureId?.let { "\"$it\"" } ?: "null"
    val reasonLine = result.reason?.let { "\"$it\"" } ?: "null"
    println(Colors.LIGHT_VIOLET + """
{
  "intent": "${result.intent}",
  "featureId": $featureIdLine,
  "confidence": ${result.confidence},
  "reason": $reasonLine
}""".trimIndent() + Colors.RESET)
}

private fun setupKeysIfNeeded() {
    if (Config.hasAnyKey()) return

    println("${Colors.LIGHT_YELLOW}Первый запуск: API-ключи не найдены.${Colors.RESET}")
    println("${Colors.DARK_GRAY}Введите ключи (Enter — пропустить). Сохранятся в ~/.config/smartagent/local.properties${Colors.RESET}\n")

    val keys = mapOf(
        "DEEPSEEK_STUDY_API_KEY" to "DeepSeek API key",
        "OPENROUTER_STUDY_API_KEY" to "OpenRouter API key"
    )
    var saved = 0
    keys.forEach { (prop, label) ->
        print("${Colors.BRIGHT_WHITE}$label: ${Colors.RESET}")
        System.out.flush()
        val value = readlnOrNull()?.trim() ?: ""
        if (value.isNotEmpty()) {
            Config.saveKey(prop, value)
            saved++
        }
    }

    if (saved == 0) {
        println("\n${Colors.LIGHT_YELLOW}Ключи не введены. Задай их позже в ~/.config/smartagent/local.properties${Colors.RESET}\n")
    } else {
        println("\n${Colors.LIGHT_GREEN}Сохранено $saved ключ(ей). Продолжаю...${Colors.RESET}\n")
    }
}
