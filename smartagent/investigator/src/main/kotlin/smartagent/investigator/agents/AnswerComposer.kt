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
                    if (!r.backendMethod.isNullOrBlank()) appendLine("Метод бэкенда: ${r.backendMethod}")
                    appendLine("Поля источника (из body): ${r.sourceFields.joinToString(", ")}")
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
                        "${r.sourceFields.joinToString(", ")} (${r.transformation})"
                    else
                        "берем из поля ${r.sourceFields.joinToString(", ")}"

                    if (successOutputs.size > 1) {
                        appendLine("Поле ${ui.apiField} берём из:")
                    } else {
                        appendLine("Данные для поля «$displayText» получаем отсюда:")
                    }
                    appendLine("  Дефиниция: ${r.definitionPath}")
                    appendLine("  Бэкенд: ${r.backendHost}${r.backendBasePath}")
                    if (!r.backendMethod.isNullOrBlank()) appendLine("  Метод: ${r.backendMethod}")
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
  Метод: {backendMethod} (ОБЯЗАТЕЛЬНО: тип HTTP-метода + path бэкенда, например GET /v1/getData. Добавлять только если есть в данных. Это эндпоинт бэкенда, НЕ path дефиниции.)
  Маппинг в дефиниции: {см. правила маппинга ниже}

## Если данные из нескольких дефиниций:

Маппинг на ui: {apiField_1} + {apiField_2}

Поле {apiField_1} берём из:
  Дефиниция: {definitionPath_1}
  Бэкенд: {backendHost_1}{backendBasePath_1}
  Метод: {backendMethod_1}
  Маппинг в дефиниции: {см. правила маппинга ниже}

Поле {apiField_2} берём из:
  ...

## Правила

### Дефиниция
Только значение name/title/path из YAML (например: v4_product или v4/product). Без пути к файлу.

### Метод
Эндпоинт бэкенда с HTTP-методом: «GET /v1/getData», «POST /offers», «PATCH /update».
Добавлять ТОЛЬКО если «Метод бэкенда» присутствует в данных.

### Маппинг в дефиниции
- Если операции нет → «берем из поля {body.X}»
- Если есть трансформация → «{body.field1}, {body.field2} ({описание операции})»
  Примеры: «body.a, body.b (сложение)», «body.x, body.y (nullish coalescing body.x ?? body.y)»
- Использовать ОРИГИНАЛЬНЫЕ поля из body ответа бэкенда. НЕ внутренние переменные функции.

### Прочее
- Предупреждение системы проверки — вставить перед ответом.
- Ошибки поиска — добавить в конец.
- Никакого лишнего текста.
""".trimIndent()
    }
}
