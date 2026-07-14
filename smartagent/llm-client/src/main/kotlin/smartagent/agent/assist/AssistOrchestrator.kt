package smartagent.agent.assist

import smartagent.Colors
import smartagent.LLMGateway
import smartagent.ModelConfig
import smartagent.agent.toolcalling.ToolCallingAgent
import smartagent.doc.DocGitContext
import smartagent.doc.KnowledgeService

class AssistOrchestrator(
    private val knowledge: KnowledgeService,
    private val gateway: LLMGateway
) {
    companion object {
        val NOT_INITIALIZED_MESSAGE = """
Перед началом работы инициализируй проект.

Команды:
  /init <owner>/<repo>                         — индексировать весь репозиторий
  /init <owner>/<repo> --branch <branch>       — указать ветку
  /init <owner>/<repo> [path1] [path2...]      — индексировать отдельные папки

Пример: /init octocat/Hello-World --branch main docs src
""".trimIndent()
    }

    fun handle(query: String, model: ModelConfig, chatId: Long? = null): String {
        if (!knowledge.isInitialized()) {
            println(NOT_INITIALIZED_MESSAGE)
            return NOT_INITIALIZED_MESSAGE
        }

        val (ragCtx, gitCtx, errors) = gatherContext(query)
        return try {
            val answer = ToolCallingAgent.handle(
                query = query,
                gateway = gateway,
                model = model,
                chatId = chatId,
                ragContext = ragCtx,
                gitContext = gitCtx,
                systemErrors = errors,
                extraSystemPrompt = """
Ты — ассистент по проекту. Отвечай только на вопросы, связанные с текущим проектом.
Отвечай по делу, без лишних слов. Общайся на русском языке.
Стиль общения — спокойный, без эмоций.
""".trimIndent()
            )
            println()
            println("${Colors.LIGHT_VIOLET}$answer${Colors.RESET}")
            println()
            answer
        } catch (e: Exception) {
            val msg = "Ошибка агента: ${e.message ?: e::class.simpleName}"
            println(msg)
            msg
        }
    }

    private fun gatherContext(query: String): Triple<String?, DocGitContext?, List<String>> =
        runCatching {
            val ctx = knowledge.getContext(query)
            Triple(ctx.ragContext.takeIf { it.isNotBlank() }, ctx.gitContext, emptyList<String>())
        }.getOrElse { e ->
            println("[AssistOrchestrator] Context error: ${e.message}")
            Triple(null, null, listOf("Ошибка при получении контекста из индекса: ${e.message ?: e::class.simpleName}"))
        }
}
