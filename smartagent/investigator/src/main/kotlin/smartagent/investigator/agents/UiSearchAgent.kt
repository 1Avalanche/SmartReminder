package smartagent.investigator.agents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import smartagent.LLMGateway
import smartagent.Message
import smartagent.ModelConfig
import smartagent.investigator.model.UiAgentOutput
import smartagent.investigator.model.UiSearchResult
import smartagent.mcp_handler.McpSession

private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

class UiSearchAgent(
    private val session: McpSession,
    private val gateway: LLMGateway,
    private val model: ModelConfig,
    private val owner: String,
    private val uiRepo: String
) {
    private val loop = InvestigatorToolLoop(
        session = session,
        gateway = gateway,
        model = model,
        agentSystemPrompt = buildSystemPrompt(),
        maxIterations = 50
    )

    fun search(
        query: String,
        history: List<Message> = emptyList(),
        fileHints: List<String> = emptyList()
    ): Pair<UiAgentOutput, List<String>> {
        val hintBlock = if (fileHints.isNotEmpty()) """

Подсказка из предыдущего запроса: цепочка файлов проходила через:
${fileHints.joinToString("\n") { "- $it" }}
Начни с проверки этих файлов.""" else ""

        val prompt = """
Запрос пользователя: "$query"

Репозиторий UI: owner=$owner, repo=$uiRepo
$hintBlock
Выполни поиск по алгоритму из системного промпта и верни результат.
""".trimIndent()

        val result = runCatching {
            loop.run(prompt, history)
        }.getOrElse { e ->
            System.err.println("[ui-search] Error: ${e.message}")
            return UiAgentOutput.SearchError(e.message ?: "unknown error") to emptyList()
        }

        return parseOutput(result.answer, query) to result.accessedFiles
    }

    private fun parseOutput(raw: String, originalQuery: String): UiAgentOutput {
        val cleaned = extractJson(raw)

        if (!cleaned.startsWith("{")) {
            return UiAgentOutput.SearchError(cleaned)
        }

        return runCatching {
            val obj = lenientJson.parseToJsonElement(cleaned) as JsonObject
            val status = (obj["status"] as? JsonPrimitive)?.content ?: "error"

            when (status) {
                "found" -> {
                    val items = (obj["items"] as? JsonArray)?.map { el ->
                        val item = el.jsonObject
                        UiSearchResult(
                            stringId = item["stringId"]!!.jsonPrimitive.content,
                            displayText = item["displayText"]!!.jsonPrimitive.content,
                            apiField = item["apiField"]!!.jsonPrimitive.content,
                            channelAlias = item["channelAlias"]!!.jsonPrimitive.content,
                            apiMethod = item["apiMethod"]!!.jsonPrimitive.content
                        )
                    } ?: emptyList()
                    if (items.isEmpty()) UiAgentOutput.NotFound(originalQuery)
                    else UiAgentOutput.Results(items)
                }
                "not_found" -> UiAgentOutput.NotFound(originalQuery)
                "no_api_field" -> {
                    val stringId = (obj["stringId"] as? JsonPrimitive)?.content ?: "unknown"
                    val displayText = (obj["displayText"] as? JsonPrimitive)?.content ?: stringId
                    UiAgentOutput.NoApiField(stringId, displayText)
                }
                else -> UiAgentOutput.SearchError("Unexpected status: $status. Raw: $cleaned")
            }
        }.getOrElse { e ->
            System.err.println("[ui-search] Parse error: ${e.message}. Raw: $cleaned")
            UiAgentOutput.SearchError("Не удалось разобрать ответ агента: ${e.message}")
        }
    }

    private fun buildSystemPrompt() = """
Ты — агент поиска данных в Android UI-репозитории.
Твоя задача: найти связь между текстом на UI и источником данных (API-поле и канал).

## Алгоритм поиска

### Шаг 0. Определение точки входа

Проверь, содержит ли запрос явное название фичи, модуля или экрана.

**Признаки экрана**: запрос содержит "экран [Name]", "screen [Name]", "[Name]Screen", "на [Name]Screen".
  Примеры: "откуда на экране ProductCard данные", "как работает OrderListScreen"
  Действие: search_code с query="<Name>Screen" в repo UI, получи файл экрана,
             читай его через get_file_contents и переходи сразу к Шагу 4 (трассировка).
             Шаги 1–3 пропустить.

**Признаки фичи/модуля**: пользователь называет название папки, пакета или feature-модуля.
  Примеры: "в feature product-card", "в модуле order", "фича catalog"
  Действие: search_code с query="<feature_name>" в repo UI, найди модуль (ViewModel, UseCase или Screen),
             и переходи к Шагу 4 для найденного файла.
             Шаги 1–3 пропустить.

**Если запрос содержит и экран/фичу, и конкретное поле** — ищи это поле начиная с найденного файла.

**Если запрос НЕ содержит явного экрана или фичи** — переходи к Шагу 1 (поиск в strings.xml).

**ВАЖНО — что НЕ является именем экрана/фичи:**
- Доменные слова предметной области: "консультации", "заказы", "товары", "оплата", "корзина", "акции"
- Бизнес-термины в любом падеже: "в консультациях", "для заказов", "по товарам"
- Любое слово из бизнес-домена без технического суффикса
Экран/фича — ТОЛЬКО технический идентификатор: *Screen, *Fragment, *Activity,
feature/<name>, module/<name> или явное название пакета.
При малейшем сомнении — всегда переходи к Шагу 1.

### Шаг 1. Поиск строк в strings.xml
Используй search_code для поиска в файлах strings.xml репозитория UI.
Ищи семантически близкие строки к запросу пользователя.
Формат строк: <string name="string_id">Текст для UI</string>

Параметры search_code:
- query: текст запроса + filename:strings.xml
- owner: из запроса пользователя
- repo: из запроса пользователя

### Шаг 2. Чтение strings.xml
Получи содержимое найденного файла через get_file_contents.
Извлеки id строки (атрибут name) и текст.

### Шаг 3. Поиск использования строки на UI
Используй search_code для поиска stringId в Kotlin/XML файлах.
Найди Composable или XML-layout где используется эта строка.
Ищи паттерны: R.string.{stringId}, "@string/{stringId}", stringResource(R.string.{stringId})

### Шаг 4. Глубокая трассировка (cross-module)
Из найденного UI-компонента трассируй цепочку до RemoteDataSource:
- Composable/Fragment → ViewModel
- ViewModel → UseCase (может быть 0 или несколько)
- UseCase → Repository (интерфейс)
- Repository → *RemoteDataSource (реализация)

При каждом шаге используй search_code и get_file_contents для чтения файлов.
ВАЖНО: данные могут приходить из feature-модуля ДРУГОГО модуля, ищи по всему репозиторию.

Если UseCase вызывает 2 разных Repository — трассируй обе ветки.

### Шаг 5. Поиск API-поля и канала в RemoteDataSource
В файле *RemoteDataSource найди:
1. Вызов API-метода и поле ответа, соответствующее искомым данным
2. Канал (алиас) — ищи в:
   - Endpoints object/class → HostTags enum/object (содержит алиас канала)
   - или интерфейсе из пакета data.features.api
   - или зависимости, инжектируемой в RemoteDataSource

## Правило о stringId

stringId ОБЯЗАН быть реально найден в файле strings.xml в Шаге 2.
Если Шаги 1–3 не дали реального stringId из strings.xml → верни {"status": "not_found"}.
ЗАПРЕЩЕНО придумывать stringId из:
- текста запроса пользователя
- имён переменных, полей API или классов
- текста в коде или комментариях
- любых других источников кроме атрибута name тега <string> в strings.xml

## Формат ответа FINAL_ANSWER

Возвращай строго JSON без markdown-обёртки:

Если нашёл (одна или несколько веток):
{
  "status": "found",
  "items": [
    {
      "stringId": "bottom_navigation_main_tab",
      "displayText": "Главная",
      "apiField": "stocks.other_stocks.guaranteed",
      "channelAlias": "peach",
      "apiMethod": "v4/product"
    }
  ]
}

Если строка не найдена в UI:
{"status": "not_found"}

Если нашёл строку, но не смог определить API-поле или канал:
{"status": "no_api_field", "stringId": "...", "displayText": "..."}
""".trimIndent()
}
