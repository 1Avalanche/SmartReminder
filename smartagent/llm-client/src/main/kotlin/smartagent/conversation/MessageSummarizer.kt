package smartagent.conversation

import smartagent.LLMGateway
import smartagent.Message
import smartagent.ModelConfig

const val SUMMARIZATION_PROMPT = """Сделай краткое резюме предыдущего разговора.

Сохрани:
- важные факты
- принятые решения
- предпочтения пользователя
- договорённости
- открытые вопросы
- информацию, необходимую для продолжения диалога

Не добавляй информацию, которой нет в разговоре.
Пиши кратко и информативно.
Не пересказывай диалог полностью."""

object MessageSummarizer {

    fun summarize(
        currentSummary: String,
        messagesToSummarize: List<Message>,
        gateway: LLMGateway,
        model: ModelConfig
    ): String {
        val content = buildString {
            if (currentSummary.isNotBlank()) {
                appendLine("Существующее резюме:")
                appendLine(currentSummary)
                appendLine()
            }
            appendLine("Сообщения для суммаризации:")
            messagesToSummarize.forEach { msg -> appendLine("${msg.role}: ${msg.content}") }
        }
        val msgs = listOf(
            Message("system", SUMMARIZATION_PROMPT),
            Message("user", content)
        )
        return gateway.chat(msgs, model, "summarization")?.content ?: currentSummary
    }
}
