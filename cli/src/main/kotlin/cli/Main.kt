package cli

fun main(args: Array<String>) {
    val session = ChatSession()
    val selectedModel = parseArgs(args) ?: return
    session.switchModel(selectedModel)

    val client = ChatClient(session)

    println("${Colors.BRIGHT_GREEN}ChatAgent готов к работе!${Colors.RESET}")
    println("${Colors.DARK_GRAY}Model: ${session.currentModel.shortName}")
    println("Type /help for commands, /exit to quit.${Colors.RESET}\n")

    runRepl(session, client)
}

private fun parseArgs(args: Array<String>): ModelConfig? {
    var selected: ModelConfig = ModelConfig.DEEPSEEK
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--model", "-m" -> {
                if (i + 1 < args.size) {
                    val name = args[++i]
                    selected = ModelConfig.fromName(name) ?: run {
                        println("Unknown model: $name. Available: ${ModelConfig.entries.joinToString { it.shortName }}")
                        return null
                    }
                } else {
                    println("Error: --model requires a value")
                    return null
                }
            }
            "--help", "-h" -> { printUsage(); return null }
            else -> { println("Unknown option: ${args[i]}"); printUsage(); return null }
        }
        i++
    }
    return selected
}

private fun runRepl(session: ChatSession, client: ChatClient) {
    while (true) {
        print("> ")
        System.out.flush()
        val input = readlnOrNull() ?: break
        if (input.isBlank()) continue

        when {
            input == "/exit" || input == "/quit" -> { println("Goodbye!"); break }
            input == "/help" -> { showHelp(); println() }
            input == "/history" || input == "/hist" -> showHistory(session)
            input == "/clear" -> { session.clear(); println("Chat history cleared.") }
            input == "/models" -> listModels(session)
            input.startsWith("/model ") -> switchModel(session, input.removePrefix("/model ").trim())
            input.startsWith("/") -> println("Unknown command: $input")
            else -> client.sendMessage(input)
        }
    }
}

private fun showHelp() {
    println(Colors.LIGHT_YELLOW + """
Commands:
  /exit, /quit           Exit the program
  /help                  Show this help
  /history, /hist       Show full chat history (JSON)
  /clear                 Clear chat history
  /models                List available models
  /model <name>          Switch model (deepseek, qwen)
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
        println("Switched to model: ${session.currentModel.shortName}")
    } else {
        println("Unknown model: $name. Type /models to see available models.")
    }
}

private fun printUsage() {
    println("""
Usage: cli [options]

Options:
  -m, --model <model>   Model to use (default: deepseek)
                         Available: deepseek, qwen
  -h, --help            Show this help
    """.trimIndent())
}
