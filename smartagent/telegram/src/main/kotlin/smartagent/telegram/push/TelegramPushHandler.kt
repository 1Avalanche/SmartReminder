package smartagent.telegram.push

import smartagent.LLMGateway
import smartagent.ModelConfig
import smartagent.mcp_handler.McpManager
import smartagent.push.PushHandler

class TelegramPushHandler(
    private val gateway: LLMGateway? = null,
    private val model: ModelConfig = ModelConfig.QWEN
) {

    data class ParsedPush(
        val owner: String,
        val repo: String,
        val branch: String,
        val beforeSha: String,
        val afterSha: String,
        val prNumber: Int? = null
    )

    fun parseCommand(text: String): ParsedPush? {
        val args = text.trim().removePrefix("/push").trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (args.size < 4) return null
        val ownerRepo = args[0].split("/")
        if (ownerRepo.size != 2) return null
        return ParsedPush(
            owner = ownerRepo[0],
            repo = ownerRepo[1],
            branch = args[1],
            beforeSha = args[2],
            afterSha = args[3],
            prNumber = args.getOrNull(4)?.toIntOrNull()
        )
    }

    fun parseErrorMessage(text: String): String {
        val args = text.trim().removePrefix("/push").trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (args.isEmpty()) return "Использование: /push <owner>/<repo> <branch> <before_sha> <after_sha> [pr_number]"
        if (args[0].split("/").size != 2) return "Формат репозитория: <owner>/<repo>"
        if (args.size < 2) return "Не указан branch"
        if (args.size < 3) return "Не указан before_sha"
        if (args.size < 4) return "Не указан after_sha"
        return "Неверная команда"
    }

    fun handle(push: ParsedPush): Result<String> {
        val session = McpManager.getSession("github")
            ?: return Result.failure(Exception("GitHub MCP не подключён. Добавь GITHUB_PERSONAL_ACCESS_TOKEN."))
        return PushHandler(session, gateway, model).handle(push.owner, push.repo, push.branch, push.beforeSha, push.afterSha, push.prNumber)
    }
}
