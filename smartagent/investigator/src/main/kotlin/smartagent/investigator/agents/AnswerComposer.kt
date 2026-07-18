package smartagent.investigator.agents

import smartagent.LLMGateway
import smartagent.Message
import smartagent.ModelConfig
import smartagent.investigator.model.ChannelAgentOutput
import smartagent.investigator.model.UiSearchResult

class AnswerComposer(private val gateway: LLMGateway, private val model: ModelConfig) {

    fun compose(
        query: String,
        uiResults: List<UiSearchResult>,
        channelOutputs: List<Pair<UiSearchResult, ChannelAgentOutput>>,
        history: List<Message> = emptyList(),
        uncertaintyNote: String? = null
    ): String {
        val context = buildContext(uiResults, channelOutputs)
        val uncertaintyBlock = if (uncertaintyNote != null)
            "\n⚠️ Предупреждение от системы проверки: $uncertaintyNote\n" else ""
        val messages = mutableListOf<Message>()
        messages += Message("system", SYSTEM_PROMPT)
        messages += history
        messages += Message("user", """
Вопрос пользователя: "$query"

Найденные данные:
$context
$uncertaintyBlock
Сформируй финальный ответ пользователю.
""".trimIndent())

        return runCatching {
            gateway.chat(messages, model, "investigator-composer")?.content?.let { stripThinkBlocks(it) }
        }.getOrElse { e ->
            System.err.println("[composer] LLM call failed: ${e.message}")
            null
        } ?: buildFallbackAnswer(uiResults, channelOutputs)
    }

    private fun buildContext(
        uiResults: List<UiSearchResult>,
        channelOutputs: List<Pair<UiSearchResult, ChannelAgentOutput>>
    ): String = buildString {
        channelOutputs.forEachIndexed { i, (ui, ch) ->
            if (channelOutputs.size > 1) appendLine("=== Ветка ${i + 1} ===")
            appendLine("UI-строка: \"${ui.displayText}\" (id: ${ui.stringId})")
            appendLine("API-поле: ${ui.apiField}")
            appendLine("Метод API: ${ui.apiMethod}")
            appendLine("Канал (алиас): ${ui.channelAlias}")
            when (ch) {
                is ChannelAgentOutput.Result -> {
                    val r = ch.data
                    appendLine("Репозиторий канала: ${r.channelRepo}")
                    appendLine("Дефиниция: ${r.definitionPath}")
                    appendLine("Бэкенд (алиас): ${r.backendAlias}")
                    appendLine("Бэкенд (host): ${r.backendHost}")
                    appendLine("Бэкенд (basepath): ${r.backendBasePath}")
                    appendLine("Поля источника: ${r.sourceFields.joinToString(", ")}")
                    if (!r.transformation.isNullOrBlank()) appendLine("Трансформация: ${r.transformation}")
                }
                is ChannelAgentOutput.NoMethod ->
                    appendLine("ОШИБКА: не найден метод/поле '${ch.param}' в канале '${ch.channel}'")
                is ChannelAgentOutput.NoBackend ->
                    appendLine("ОШИБКА: не найден бэкенд в канале '${ch.channel}'")
                is ChannelAgentOutput.SearchError ->
                    appendLine("ОШИБКА поиска в канале: ${ch.cause}")
            }
            appendLine()
        }

        if (channelOutputs.isEmpty()) {
            uiResults.forEach { ui ->
                appendLine("UI-строка: \"${ui.displayText}\" (id: ${ui.stringId})")
                appendLine("API-поле: ${ui.apiField}")
                appendLine("Метод: ${ui.apiMethod}, канал: ${ui.channelAlias}")
                appendLine("(поиск в канале не выполнен)")
            }
        }
    }

    private fun buildFallbackAnswer(
        uiResults: List<UiSearchResult>,
        channelOutputs: List<Pair<UiSearchResult, ChannelAgentOutput>>
    ): String = buildString {
        channelOutputs.forEach { (ui, ch) ->
            when (ch) {
                is ChannelAgentOutput.Result -> {
                    val r = ch.data
                    val source = if (!r.transformation.isNullOrBlank())
                        "из операции `${r.transformation}`"
                    else
                        "из полей ${r.sourceFields.joinToString(", ")}"
                    appendLine("Данные для \"${ui.displayText}\" берутся из ${ui.apiMethod} поля ${ui.apiField}. " +
                        "Получаем из канала ${ui.channelAlias}, бэкенд ${r.backendHost}${r.backendBasePath}, $source.")
                }
                is ChannelAgentOutput.NoMethod ->
                    appendLine("К сожалению, не нашлось поле/метод ${ch.param} в канале ${ch.channel}.")
                is ChannelAgentOutput.NoBackend ->
                    appendLine("К сожалению, не нашлось бэкенд источника в канале ${ch.channel}.")
                is ChannelAgentOutput.SearchError ->
                    appendLine("Ошибка при поиске в канале: ${ch.cause}")
            }
        }
    }.trim()

    companion object {
        private val SYSTEM_PROMPT = """
Ты — ассистент, который объясняет связи между UI и источниками данных в мобильном приложении.

Отвечай чётко и по делу. Используй следующий формат ответа:

Данные берутся из `<метод_api>` поля `<api_field>`.
Получаем из канала `<алиас_канала>`, бэкенд `<host><basepath>`.

Если transformation == null: из поля `<source_field>`
Если transformation != null: из операции `<transformation>`

Примеры:
- "из поля `body.price`"
- "из операции `body.a + body.b + body.c`"
- "из условия `body.x ?? body.y`"

Если данные пришли из нескольких каналов/бэкендов — опиши каждый отдельно, пронумеруй.

Если запрос содержал имя товара, объекта или пример — явно укажи в начале ответа как интерпретировал вопрос:
"По запросу о [контекст из запроса] найдено поле «[displayText]»."

Если в данных есть предупреждение системы проверки — включи его в ответ перед основными данными.

Если в данных есть ошибки поиска — сообщи о них в конце ответа.

Не добавляй лишнего текста. Только факты о data flow.
""".trimIndent()
    }
}
