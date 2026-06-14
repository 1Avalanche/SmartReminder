package smartagent

import java.io.File

fun main(args: Array<String>) {
    setupKeysIfNeeded()
    val parsedArgs = parseArgs(args)
    val session = ChatSession()
    session.switchModel(parsedArgs.model ?: Config.loadLastModel() ?: ModelConfig.DEEPSEEK)
    parsedArgs.contextStrategy?.let { session.switchContextStrategy(it) }

    parsedArgs.repoPath?.let { path ->
        val dir = File(path).canonicalFile
        if (dir.isDirectory) {
            session.repoContext = RepoContext(dir)
            println("${Colors.LIGHT_GREEN}Repo: ${dir.absolutePath}${Colors.RESET}")
        } else {
            println("${Colors.LIGHT_YELLOW}Warning: repo path not found: $path${Colors.RESET}")
        }
    }

    val client = ChatClient(session)

    println("${Colors.LIGHT_YELLOW}SmartAgent готов к работе!${Colors.RESET}")
    println("${Colors.DARK_GRAY}Model: ${session.currentModel.shortName} | Mode: ${session.currentMode.displayName} | Strategy: ${session.contextStrategy.name.lowercase()}")
    println("Type /help for commands, /exit to quit.${Colors.RESET}\n")

    runRepl(session, client)
}

private data class ParsedArgs(
    val model: ModelConfig? = null,
    val repoPath: String? = null,
    val contextStrategy: ContextStrategy? = null
)

private fun parseArgs(args: Array<String>): ParsedArgs {
    var model: ModelConfig? = null
    var repoPath: String? = null
    var contextStrategy: ContextStrategy? = null
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--model" -> { model = ModelConfig.fromName(args.getOrElse(++i) { "" }); i++ }
            "--repo" -> { repoPath = args.getOrElse(++i) { "" }; i++ }
            "--context-strategy" -> {
                contextStrategy = ContextStrategy.fromName(args.getOrElse(++i) { "" })
                i++
            }
            else -> i++
        }
    }
    return ParsedArgs(model, repoPath, contextStrategy)
}

private fun runRepl(session: ChatSession, client: ChatClient) {
    while (true) {
        print("${Colors.BRIGHT_WHITE}> ")
        System.out.flush()
        val input = readlnOrNull() ?: break
        if (input.isBlank()) continue

        when {
            input == "/exit" || input == "/quit" -> { println("${Colors.LIGHT_YELLOW}Goodbye!${Colors.RESET}"); break }
            input == "/help" -> { showHelp(); println() }
            input == "/history" || input == "/hist" -> showHistory(session)
            input == "/clear" -> { session.clear(); println("${Colors.LIGHT_YELLOW}Chat history cleared.${Colors.RESET}") }
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
            input.startsWith("/mode ") -> switchMode(session, input.removePrefix("/mode ").trim())
            input == "/context-strategy" -> showContextStrategy(session)
            input.startsWith("/context-strategy ") -> switchContextStrategy(session, input.removePrefix("/context-strategy ").trim())
            input == "/facts" -> showFacts(session)
            input == "/branch" -> showBranch(session)
            input.startsWith("/branch ") -> handleBranch(session, input.removePrefix("/branch ").trim())
            input == "/totalTokens" -> showTotalTokens(session)
            input.startsWith("/analyze ") -> {
                val (path, prompt) = parseAnalyzeArgs(input.removePrefix("/analyze ").trim())
                analyzeCode(session, client, path, prompt)
            }
            input.startsWith("/") -> println("${Colors.LIGHT_YELLOW}Unknown command: $input${Colors.RESET}")
            else -> client.sendMessage(input)
        }
    }
}

private fun showHelp() {
    println(Colors.LIGHT_YELLOW + """
Commands:
  /exit, /quit                    Exit the program
  /help                           Show this help
  /history, /hist                 Show full chat history (JSON)
  /clear                          Clear chat history and file context
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
  /mode <name>                    Switch mode (chat, code-analyzer)
  /context-strategy               Show current context strategy
  /context-strategy <name>        Switch strategy (sliding-window, sticky-facts, branching)
  /facts                          Show currently stored facts (sticky-facts mode)
  /branch                         Show branch status (active branch, available branches)
  /branch checkpoint <b1> <b2>    Create checkpoint and fork into two named branches
  /branch switch <name>           Switch to named branch
  /totalTokens                    Show token usage per request + total sum
  /analyze <path> [prompt]        Collect all text files from path and send for analysis
  <message>                       Send a message to the current model
    """.trimIndent() + Colors.RESET)
}

private fun showHistory(session: ChatSession) {
    val history = session.getHistory()
    if (session.contextStrategy == ContextStrategy.BRANCHING) {
        val branch = session.activeBranch
        if (branch != null) {
            println("${Colors.DARK_GRAY}[Ветка: $branch]${Colors.RESET}")
        } else {
            println("${Colors.DARK_GRAY}[Ствол — ветки не выбраны]${Colors.RESET}")
        }
    }
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
        println(Colors.LIGHT_YELLOW + "  ${index + 1}. $check${model.shortName}" + Colors.RESET)
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
    val target = resolveAnalyzePath(rawPath, session)
    if (target == null) {
        println("${Colors.LIGHT_YELLOW}Path not found: $rawPath${Colors.RESET}")
        return
    }

    val collected = mutableListOf<Pair<String, String>>()
    collectTextFiles(target, target, collected)

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

private fun resolveAnalyzePath(rawPath: String, session: ChatSession): File? {
    val abs = File(rawPath)
    if (abs.isAbsolute && abs.exists()) return abs

    val repoRoot = session.repoContext?.root
    if (repoRoot != null) {
        val relative = File(repoRoot, rawPath)
        if (relative.exists()) return relative
    }

    val cwd = File(rawPath).canonicalFile
    if (cwd.exists()) return cwd

    return null
}

private fun collectTextFiles(target: File, base: File, result: MutableList<Pair<String, String>>) {
    val ignoredDirs = setOf(
        ".git", "build", ".gradle", "node_modules", ".idea",
        "__pycache__", "out", ".dart_tool", ".pub-cache", ".build",
        "DerivedData", ".swiftpm", "Pods"
    )
    val binaryExtensions = setOf(
        "png", "jpg", "jpeg", "gif", "ico", "bmp", "webp",
        "pdf", "zip", "jar", "class", "apk", "ipa", "so", "dylib",
        "exe", "bin", "o", "a", "lib"
    )
    if (target.isFile) {
        if (target.extension !in binaryExtensions) {
            val content = runCatching { target.readText() }.getOrNull() ?: return
            result.add(Pair(target.relativeTo(base.parentFile ?: base).path, content))
        }
        return
    }

    target.walkTopDown()
        .onEnter { it.name !in ignoredDirs }
        .filter { it.isFile && it.extension !in binaryExtensions }
        .sortedBy { it.relativeTo(base).path }
        .forEach { file ->
            val content = runCatching { file.readText() }.getOrNull() ?: return@forEach
            result.add(Pair(file.relativeTo(base).path, content))
        }
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

private fun showContextStrategy(session: ChatSession) {
    println("${Colors.LIGHT_YELLOW}Current strategy: ${session.contextStrategy.name.lowercase()}${Colors.RESET}")
    println("${Colors.DARK_GRAY}Available: sliding-window, sticky-facts, branching${Colors.RESET}")
}

private fun switchContextStrategy(session: ChatSession, name: String) {
    val strategy = ContextStrategy.fromName(name)
    if (strategy != null) {
        session.switchContextStrategy(strategy)
        println("${Colors.LIGHT_YELLOW}Strategy: ${strategy.name.lowercase()} (история очищена)${Colors.RESET}")
    } else {
        println("${Colors.LIGHT_YELLOW}Unknown strategy: $name. Available: sliding-window, sticky-facts, branching${Colors.RESET}")
    }
}

private fun showFacts(session: ChatSession) {
    val facts = session.getFacts()
    if (facts.isEmpty()) {
        println("${Colors.LIGHT_YELLOW}No facts yet. Use sticky-facts strategy and chat to accumulate.${Colors.RESET}")
        return
    }
    println("${Colors.LIGHT_YELLOW}Stored facts:${Colors.RESET}")
    facts.forEach { println("${Colors.LIGHT_YELLOW}  ${it.name}: ${it.value}${Colors.RESET}") }
}

private fun showBranch(session: ChatSession) {
    val names = session.getBranchNames()
    if (names.isEmpty()) {
        println("${Colors.LIGHT_YELLOW}No branches. Use /branch checkpoint <b1> <b2> to create.${Colors.RESET}")
        return
    }
    println("${Colors.LIGHT_YELLOW}Active branch: ${session.activeBranch ?: "none"}${Colors.RESET}")
    println("${Colors.DARK_GRAY}Branches: ${names.joinToString(", ")}${Colors.RESET}")
}

private fun handleBranch(session: ChatSession, args: String) {
    val parts = args.trim().split("\\s+".toRegex())
    when {
        parts[0] == "checkpoint" && parts.size >= 3 -> {
            val b1 = parts[1]
            val b2 = parts[2]
            session.createCheckpoint(b1, b2)
            println("${Colors.LIGHT_YELLOW}Checkpoint created. Branches: $b1, $b2. Active: $b1${Colors.RESET}")
        }
        parts[0] == "switch" && parts.size >= 2 -> {
            val name = parts[1]
            if (session.switchBranch(name)) {
                println("${Colors.LIGHT_YELLOW}Switched to branch: $name${Colors.RESET}")
            } else {
                println("${Colors.LIGHT_YELLOW}Branch not found: $name. Available: ${session.getBranchNames().joinToString(", ")}${Colors.RESET}")
            }
        }
        else -> println("${Colors.LIGHT_YELLOW}Usage: /branch checkpoint <b1> <b2>  |  /branch switch <name>${Colors.RESET}")
    }
}

private fun showTotalTokens(session: ChatSession) {
    val entries = session.getTokenEntries()
    if (entries.isEmpty()) { println("${Colors.LIGHT_YELLOW}No token data yet.${Colors.RESET}"); return }
    entries.forEach {
        println("${Colors.LIGHT_YELLOW}#${it.request}  prompt: ${it.prompt} | completion: ${it.completion} | total: ${it.total}${Colors.RESET}")
    }
    val sumTotal = entries.sumOf { it.total }
    val strategy = session.contextStrategy.name.lowercase()
    println("${Colors.LIGHT_YELLOW}─────────────────────────────")
    println("Total tokens used: $sumTotal | strategy: $strategy${Colors.RESET}")
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
