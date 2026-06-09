package cli

fun main() {
    val session = ChatSession()
    session.switchModel(Config.loadLastModel() ?: ModelConfig.DEEPSEEK)

    val client = ChatClient(session)

    println("${Colors.LIGHT_YELLOW}ChatAgent готов к работе!${Colors.RESET}")
    println("${Colors.DARK_GRAY}Model: ${session.currentModel.shortName}")
    println("Type /help for commands, /exit to quit.${Colors.RESET}\n")

    runRepl(session, client)
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
        Config.saveLastModel(found)
        println("${Colors.LIGHT_YELLOW}Switched to model: ${session.currentModel.shortName}${Colors.RESET}")
    } else {
        println("${Colors.LIGHT_YELLOW}Unknown model: $name. Type /models to see available models.${Colors.RESET}")
    }
}

