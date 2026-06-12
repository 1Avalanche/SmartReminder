package smartagent

import java.io.File

fun main(args: Array<String>) {
    setupKeysIfNeeded()
    val parsedArgs = parseArgs(args)
    val session = ChatSession()
    session.switchModel(parsedArgs.model ?: Config.loadLastModel() ?: ModelConfig.DEEPSEEK)
    parsedArgs.compressionMode?.let { session.switchCompression(it) }

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
    println("${Colors.DARK_GRAY}Model: ${session.currentModel.shortName} | Mode: ${session.currentMode.displayName} | Compression: ${session.compressionMode.name.lowercase()}")
    println("Type /help for commands, /exit to quit.${Colors.RESET}\n")

    runRepl(session, client)
}

private data class ParsedArgs(
    val model: ModelConfig? = null,
    val repoPath: String? = null,
    val compressionMode: CompressionMode? = null
)

private fun parseArgs(args: Array<String>): ParsedArgs {
    var model: ModelConfig? = null
    var repoPath: String? = null
    var compressionMode: CompressionMode? = null
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--model" -> { model = ModelConfig.fromName(args.getOrElse(++i) { "" }); i++ }
            "--repo" -> { repoPath = args.getOrElse(++i) { "" }; i++ }
            "--compression" -> {
                compressionMode = when (args.getOrElse(++i) { "" }.lowercase()) {
                    "compress" -> CompressionMode.COMPRESS
                    else -> CompressionMode.NONE
                }
                i++
            }
            else -> i++
        }
    }
    return ParsedArgs(model, repoPath, compressionMode)
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
            input == "/compression" -> showCompression(session)
            input.startsWith("/compression ") -> switchCompression(session, input.removePrefix("/compression ").trim())
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
  /exit, /quit           Exit the program
  /help                  Show this help
  /history, /hist        Show full chat history (JSON)
  /clear                 Clear chat history and file context
  /models                List available models
  /model <name>          Switch model (deepseek, qwen, qwen-low)
  /repo                  Show current repo path
  /repo <path>           Set repo path
  /files [pattern]       List repo files (optional filter)
  /tree [depth]          Show repo file tree (default depth: 3)
  /read <file>           Load file into context (relative to repo root)
  /context               Show files loaded in context
  /context clear         Remove all files from context
  /mode                  Show current mode
  /mode <name>           Switch mode (chat, code-analyzer)
  /compression           Show current compression mode
  /compression <mode>    Switch compression mode (none, compress)
  /totalTokens           Show token usage per request + total sum
  /analyze <path> [prompt]  Collect all text files from path and send for analysis with optional prompt
  <message>              Send a message to the current model
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
        println("${Colors.LIGHT_YELLOW}Switched to mode: ${found.displayName}${Colors.RESET}")
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
    val compression = session.compressionMode.name.lowercase()
    println("${Colors.LIGHT_YELLOW}─────────────────────────────")
    println("Total tokens used: $sumTotal | compression: $compression${Colors.RESET}")
}

private fun showCompression(session: ChatSession) {
    println("${Colors.LIGHT_YELLOW}Current compression: ${session.compressionMode.name.lowercase()}${Colors.RESET}")
    println("${Colors.DARK_GRAY}Available: none, compress${Colors.RESET}")
}

private fun switchCompression(session: ChatSession, name: String) {
    val mode = when (name.lowercase()) {
        "compress" -> CompressionMode.COMPRESS
        "none" -> CompressionMode.NONE
        else -> null
    }
    if (mode != null) {
        session.switchCompression(mode)
        println("${Colors.LIGHT_YELLOW}Compression: ${mode.name.lowercase()}${Colors.RESET}")
    } else {
        println("${Colors.LIGHT_YELLOW}Unknown: $name. Use: none, compress${Colors.RESET}")
    }
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
