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
идентификатор дефиниции: ${uiResult.apiMethod}
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
                            backendMethod = (obj["backendMethod"] as? JsonPrimitive)?.content,
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

Определи формат идентификатора дефиниции из запроса:

**Формат A — endpoint-path** (содержит `/`, например: `v4/product`, `v1/offers`):
Ищи `path: v4/product` в репозитории.
- query: `path: v4/product`

**Формат B — name/title** (содержит `_` и не содержит `/`, например: `v4_product`, `v1_offers`):
Ищи `name: v4_product` или `title: v4_product` в репозитории.
- Сначала query: `name: v4_product`
- Если не нашёл — query: `title: v4_product`

Параметры search_code:
- owner: из запроса
- repo: из запроса

Это сразу укажет на нужный .yaml файл — не надо листать definitions/ поштучно.

### Шаг 2. Прочитать найденный yaml
get_file_contents для файла из результата search_code.
В yaml найди:
1. Значение поля `name:` или `title:` или `path:` — только само значение (например: `v4_product`), без пути к файлу. Это идёт в `definitionPath`.
2. Алиас бэкенда (`backendAlias`) и его HTTP-метод вызова из yaml (`method:` + `path:` или `url:`).
   HTTP-метод и путь бэкенда записать в `backendMethod` как `"GET /v1/data"` или `"POST /getData"`. Это эндпоинт бэкенда, который канал вызывает, — НЕ path дефиниции.
3. Секцию контракта (response / output / result) — ищи искомое apiField.
4. Если yaml ссылается на .js файл — прочти его для поиска sourceFields.

### Правило sourceFields и transformation при чтении JS

`sourceFields` — это **оригинальные поля из `response.body` бэкенда**, которые реально читаются.
НЕ промежуточные переменные, НЕ алиасы деструктуризации — только листовые поля.

Пример JS:
```js
module.exports = ({ response, STOCKS_RESULT: { body } }) => {
  const {
    item,
    stockByZone: {
      magOut,
      ...restStocks
    } = {}
  } = body;
}
```
`stockByZone` — промежуточный алиас. Оригинальные поля: `body.stockByZone.magOut` и остаток `body.stockByZone`.
→ `sourceFields` = `["body.stockByZone.magOut", "body.stockByZone"]`

Общее правило:
- `const { alias: { field1, ...rest } = {} } = body` → `["body.alias.field1", "body.alias"]`
- `const { field } = body` → `["body.field"]`
- `body.a + body.b` → `sourceFields = ["body.a", "body.b"]`, `transformation = "body.a + body.b"`

`transformation` — описание операции над оригинальными полями body, НЕ над внутренними переменными.

### Шаг 3. Если поле не в yaml напрямую — искать в shared_flow
Если yaml ссылается на shared_flow — прочти нужный файл из shared_flows/.
Используй search_code: `path: <shared_flow_name>` в репозитории.

### Шаг 4. Резолвить бэкенд
get_file_contents для config/production.json.
Найди алиас бэкенда из шага 2. Извлеки host и basepath.

## Важно

- Шаг 1 ВСЕГДА через search_code, НЕ через листинг definitions/.
- Не читай все yaml подряд — только тот, что нашёл в шаге 1.
- Если search_code нашёл несколько файлов — возьми тот, чей path/name/title точно совпадает.
- Если path в yaml содержит переменные (например: /v4/{version}/product) — это тоже совпадение.

## Формат ответа FINAL_ANSWER

Строго JSON без markdown:

Если нашёл (прямое поле):
{
  "status": "found",
  "definitionPath": "v4_product",
  "backendAlias": "back_stock",
  "backendHost": "https://back-stock.example.com",
  "backendBasePath": "/v1/stock",
  "backendMethod": "GET /getData",
  "sourceFields": ["body.stocks.guaranteed"],
  "transformation": null
}

Если нашёл (деструктуризация с вложенным алиасом):
{
  "status": "found",
  "definitionPath": "v4_product",
  "backendAlias": "back_stock",
  "backendHost": "https://back-stock.example.com",
  "backendBasePath": "/v1/stock",
  "backendMethod": "GET /getData",
  "sourceFields": ["body.stockByZone.magOut", "body.stockByZone"],
  "transformation": null
}

Если нашёл (вычисляемое поле):
{
  "status": "found",
  "definitionPath": "v4_product",
  "backendAlias": "back_stock",
  "backendHost": "https://back-stock.example.com",
  "backendBasePath": "/v1/stock",
  "backendMethod": "POST /getData",
  "sourceFields": ["body.stocks.other", "body.stocks.plus", "body.stocks.bonus"],
  "transformation": "body.stocks.other + body.stocks.plus + body.stocks.bonus"
}

Если не нашёл метод/поле:
{"status": "no_method", "param": "v4/product"}

Если нашёл метод, но не определил бэкенд:
{"status": "no_backend"}
""".trimIndent()
}
