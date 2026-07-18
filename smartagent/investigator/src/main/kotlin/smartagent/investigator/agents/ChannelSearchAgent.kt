package smartagent.investigator.agents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import smartagent.LLMGateway
import smartagent.Message
import smartagent.ModelConfig
import smartagent.investigator.model.ChannelAgentOutput
import smartagent.investigator.model.ChannelSearchResult
import smartagent.investigator.model.UiSearchResult
import smartagent.mcp_handler.McpSession

private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

class ChannelSearchAgent(
    private val session: McpSession,
    private val gateway: LLMGateway,
    private val model: ModelConfig,
    private val owner: String
) {
    private val loop = InvestigatorToolLoop(
        session = session,
        gateway = gateway,
        model = model,
        agentSystemPrompt = buildSystemPrompt(),
        maxIterations = 50
    )

    fun searchDirect(
        userQuery: String,
        channelAlias: String,
        channelRepo: String,
        history: List<Message> = emptyList(),
        definitionHint: String? = null
    ): ChannelAgentOutput {
        val hintBlock = if (definitionHint != null) """

Подсказка: в предыдущем запросе к этому каналу дефиниция была в `$definitionHint`.
Начни с get_file_contents этого файла вместо search_code.""" else ""

        val prompt = """
Прямой запрос пользователя к каналу: "$userQuery"

owner: $owner
repo канала: $channelRepo
алиас канала: $channelAlias
$hintBlock
Выполни поиск по запросу пользователя в этом канале. Если пользователь указал конкретный метод или поле — найди их.
Если запрос общий — найди подходящие дефиниции и опиши, что нашёл.
""".trimIndent()

        val result = runCatching {
            loop.run(prompt, history)
        }.getOrElse { e ->
            System.err.println("[channel-search-direct] Error: ${e.message}")
            return ChannelAgentOutput.SearchError(e.message ?: "unknown error")
        }

        return parseOutput(result.answer, channelAlias, channelRepo)
    }

    fun search(
        uiResult: UiSearchResult,
        channelRepo: String,
        history: List<Message> = emptyList(),
        definitionHint: String? = null
    ): ChannelAgentOutput {
        val hintBlock = if (definitionHint != null) """

Подсказка: в предыдущем запросе к этому каналу дефиниция была в `$definitionHint`.
Начни с get_file_contents этого файла вместо search_code.""" else ""

        val prompt = """
Ищу источник данных в канале.

owner: $owner
repo канала: $channelRepo
алиас канала: ${uiResult.channelAlias}
метод API (path для поиска дефиниции): ${uiResult.apiMethod}
искомое поле: ${uiResult.apiField}
$hintBlock
Выполни поиск по алгоритму из системного промпта и верни результат.
""".trimIndent()

        val result = runCatching {
            loop.run(prompt, history)
        }.getOrElse { e ->
            System.err.println("[channel-search] Error: ${e.message}")
            return ChannelAgentOutput.SearchError(e.message ?: "unknown error")
        }

        return parseOutput(result.answer, uiResult.channelAlias, channelRepo)
    }

    private fun parseOutput(raw: String, channelAlias: String, channelRepo: String): ChannelAgentOutput {
        val cleaned = extractJson(raw)

        if (!cleaned.startsWith("{")) {
            return ChannelAgentOutput.SearchError(cleaned)
        }

        return runCatching {
            val obj = lenientJson.parseToJsonElement(cleaned) as JsonObject
            val status = (obj["status"] as? JsonPrimitive)?.content ?: "error"

            when (status) {
                "found" -> {
                    val sourceFields = (obj["sourceFields"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.content }
                        ?: emptyList()
                    ChannelAgentOutput.Result(
                        ChannelSearchResult(
                            channelRepo = channelRepo,
                            definitionPath = (obj["definitionPath"] as? JsonPrimitive)?.content ?: "",
                            backendAlias = (obj["backendAlias"] as? JsonPrimitive)?.content ?: "",
                            backendHost = (obj["backendHost"] as? JsonPrimitive)?.content ?: "",
                            backendBasePath = (obj["backendBasePath"] as? JsonPrimitive)?.content ?: "",
                            sourceFields = sourceFields,
                            transformation = (obj["transformation"] as? JsonPrimitive)?.content
                        )
                    )
                }
                "no_method" -> {
                    val param = (obj["param"] as? JsonPrimitive)?.content ?: "unknown"
                    ChannelAgentOutput.NoMethod(param, channelAlias)
                }
                "no_backend" -> ChannelAgentOutput.NoBackend(channelAlias)
                else -> ChannelAgentOutput.SearchError("Unexpected status: $status. Raw: $cleaned")
            }
        }.getOrElse { e ->
            System.err.println("[channel-search] Parse error: ${e.message}. Raw: $cleaned")
            ChannelAgentOutput.SearchError("Не удалось разобрать ответ агента: ${e.message}")
        }
    }

    private fun buildSystemPrompt() = """
Ты — агент поиска данных в репозитории канала (middle-layer).
Твоя задача: найти конкретный бэкенд и поля, из которых канал получает запрошенные данные.

## Структура репозитория канала

- definitions/ — методы канала. Каждая папка = одна «дефиниция».
  Внутри папки: один .yaml файл + 0..N .js файлов.
  В yaml поле `path` — путь метода (например: path: v4/product).
- shared_flows/ — переиспользуемые блоки логики, структура как у дефиниции.
- config/production.json — все бэкенды: алиас → host + basepath.

## Алгоритм поиска (минимум инструментов)

### Шаг 1. Найти дефиницию через search_code — НЕ листинг папок
Используй search_code для поиска строки `path: <apiMethod>` в репозитории канала.
Параметры:
- query: `path: v4/product` (подставь реальный apiMethod)
- owner: из запроса
- repo: из запроса

Это сразу укажет на нужный .yaml файл — не надо листать definitions/ поштучно.

### Шаг 2. Прочитать найденный yaml
get_file_contents для файла из результата search_code.
В yaml найди:
1. Секцию контракта (response / output / result) — ищи искомое apiField
2. Алиас бэкенда для этого поля (источник данных)
3. Если поле вычисляется из нескольких полей бэкенда (сумма, разность, условие, конкатенация и т.п.) —
   запиши формулу в "transformation". Примеры: "body.a + body.b", "body.c ?? body.d", "body.x * 100".
   Если поле берётся напрямую — оставь transformation: null.

### Шаг 3. Если поле не в yaml напрямую — искать в shared_flow
Если yaml ссылается на shared_flow — прочти нужный файл из shared_flows/.
Используй search_code: `path: <shared_flow_name>` в репозитории.

### Шаг 4. Резолвить бэкенд
get_file_contents для config/production.json.
Найди алиас бэкенда из шага 2. Извлеки host и basepath.

## Важно

- Шаг 1 ВСЕГДА через search_code, НЕ через листинг definitions/.
- Не читай все yaml подряд — только тот, что нашёл в шаге 1.
- Если search_code нашёл несколько файлов — возьми тот, чей path точно совпадает.
- Если path в yaml содержит переменные (например: /v4/{version}/product) — это тоже совпадение.

## Формат ответа FINAL_ANSWER

Строго JSON без markdown:

Если нашёл (прямое поле):
{
  "status": "found",
  "definitionPath": "definitions/product",
  "backendAlias": "back_stock",
  "backendHost": "https://back-stock.example.com",
  "backendBasePath": "/v1/stock",
  "sourceFields": ["stocks.guaranteed"],
  "transformation": null
}

Если нашёл (вычисляемое поле):
{
  "status": "found",
  "definitionPath": "definitions/product",
  "backendAlias": "back_stock",
  "backendHost": "https://back-stock.example.com",
  "backendBasePath": "/v1/stock",
  "sourceFields": ["stocks.other", "stocks.plus", "stocks.bonus"],
  "transformation": "stocks.other + stocks.plus + stocks.bonus"
}

Если не нашёл метод/поле:
{"status": "no_method", "param": "v4/product"}

Если нашёл метод, но не определил бэкенд:
{"status": "no_backend"}
""".trimIndent()
}
