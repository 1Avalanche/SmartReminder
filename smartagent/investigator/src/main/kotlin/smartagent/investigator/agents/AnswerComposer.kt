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
        val successOutputs = channelOutputs.filter { (_, ch) -> ch is ChannelAgentOutput.Result }
        val displayText = uiResults.firstOrNull()?.displayText ?: "?"

        if (successOutputs.size > 1) {
            val uiMapping = successOutputs.joinToString(" + ") { (ui, _) -> ui.apiField }
            appendLine("Маппинг на ui: $uiMapping")
            appendLine()
        }

        channelOutputs.forEach { (ui, ch) ->
            when (ch) {
                is ChannelAgentOutput.Result -> {
                    val r = ch.data
                    val mapping = if (!r.transformation.isNullOrBlank())
                        r.transformation
                    else
                        r.sourceFields.joinToString(", ")

                    if (successOutputs.size > 1) {
                        appendLine("Поле ${ui.apiField} берём из:")
                    } else {
                        appendLine("Данные для поля «$displayText» получаем отсюда:")
                    }
                    appendLine("  Дефиниция: ${r.definitionPath}")
                    appendLine("  Бэкенд: ${r.backendHost}${r.backendBasePath}")
                    if (ui.apiMethod.isNotBlank()) appendLine("  Метод: ${ui.apiMethod}")
                    appendLine("  Маппинг в дефиниции: $mapping")
                    appendLine()
                }
                is ChannelAgentOutput.NoMethod ->
                    appendLine("Не нашлось поле/метод ${ch.param} в канале ${ch.channel}.")
                is ChannelAgentOutput.NoBackend ->
                    appendLine("Не нашлось бэкенд источника в канале ${ch.channel}.")
                is ChannelAgentOutput.SearchError ->
                    appendLine("Ошибка при поиске в канале: ${ch.cause}")
            }
        }
    }.trim()

    companion object {
        private val SYSTEM_PROMPT = """
Ты — ассистент, который объясняет связи между UI и источниками данных в мобильном приложении.

Строго используй следующий формат ответа:

## Если данные из одной дефиниции:

Данные для поля «{displayText}» получаем отсюда:
  Дефиниция: {definitionPath}
  Бэкенд: {backendHost}{backendBasePath}
  Метод: {apiMethod} (добавить только если есть в данных)
  Маппинг в дефиниции: {transformation если есть, иначе: перечисление sourceFields}

## Если данные из нескольких дефиниций:

Маппинг на ui: {apiField_1} + {apiField_2} (перечисли все UI-поля)

Поле {apiField_1} берём из:
  Дефиниция: {definitionPath_1}
  Бэкенд: {backendHost_1}{backendBasePath_1}
  Метод: {apiMethod_1} (добавить только если есть в данных)
  Маппинг в дефиниции: {transformation_1 или sourceFields_1}

Поле {apiField_2} берём из:
  Дефиниция: {definitionPath_2}
  Бэкенд: {backendHost_2}{backendBasePath_2}
  Метод: {apiMethod_2} (добавить только если есть в данных)
  Маппинг в дефиниции: {transformation_2 или sourceFields_2}

## Правила

- Строку «Метод:» добавлять ТОЛЬКО если apiMethod присутствует в данных.
- Если в данных есть предупреждение системы проверки — вставь его перед ответом.
- Если в данных есть ошибки поиска — добавь в конец.
- Никакого лишнего текста. Только данные о data flow.
""".trimIndent()
    }
}
