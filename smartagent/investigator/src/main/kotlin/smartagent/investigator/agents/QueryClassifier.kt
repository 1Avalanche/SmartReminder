package smartagent.investigator.agents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import smartagent.LLMGateway
import smartagent.Message
import smartagent.ModelConfig
import smartagent.investigator.model.QueryType

class QueryClassifier(private val gateway: LLMGateway, private val model: ModelConfig) {

    fun classify(query: String): QueryType {
        val messages = listOf(
            Message("system", SYSTEM_PROMPT),
            Message("user", query)
        )
        val response = runCatching {
            gateway.chat(messages, model, "investigator-classifier")
        }.getOrElse { e ->
            System.err.println("[classifier] LLM call failed: ${e.message}")
            return QueryType.Rejected("Ошибка классификатора")
        } ?: return QueryType.Rejected("Ошибка классификатора")

        return parse(response.content)
    }

    private fun parse(content: String): QueryType {
        val cleaned = extractJson(content)

        return runCatching {
            val obj = Json.parseToJsonElement(cleaned) as JsonObject
            val type = (obj["type"] as? JsonPrimitive)?.content ?: "rejected"
            when (type) {
                "data_flow" -> QueryType.DataFlow
                "channel_search" -> {
                    val alias = (obj["channelAlias"] as? JsonPrimitive)
                        ?.content?.takeIf { it != "null" && it.isNotBlank() }
                    val searchQuery = (obj["searchQuery"] as? JsonPrimitive)?.content ?: cleaned
                    QueryType.ChannelSearch(channelAlias = alias, searchQuery = searchQuery)
                }
                else -> {
                    val reason = (obj["reason"] as? JsonPrimitive)?.content ?: "Запрос вне области действия."
                    QueryType.Rejected(reason)
                }
            }
        }.getOrElse { e ->
            System.err.println("[classifier] Parse error: ${e.message}. Raw: $cleaned")
            QueryType.Rejected("Не удалось классифицировать запрос.")
        }
    }

    companion object {
        internal val SYSTEM_PROMPT = """
Ты — классификатор запросов для системы анализа data flow в мобильном приложении.

Определи тип запроса и верни строго JSON без markdown.

## Типы запросов

### data_flow
Пользователь видит поле/элемент на UI и хочет знать, откуда приходят данные (UI → backend).
Запрос НЕ содержит явного пути API (v1/..., v4/...) и НЕ спрашивает о конкретной дефиниции/методе.
Примеры:
- "откуда берутся данные для карточки товара?"
- "из какого канала приходит цена на главном экране?"
- "как данные попадают в виджет гарантированного стока?"
- "на карточке товара есть поле Недоступно для продажи — откуда данные?"

### channel_search
Пользователь спрашивает о конкретном методе/дефиниции/поле в репозитории канала.
Признаки:
- явный путь API: v4/product, v1/offers, v3/catalog и т.п. (шаблон v\d+/...)
- слова «дефиниция», «definition», «endpoint», «метод канала»
- вопрос о том, что делает / что возвращает конкретный метод или дефиниция
- поиск конкретного поля внутри метода/дефиниции по техническому пути
Канал может быть указан явно или не указан.
Примеры:
- "найди метод v4/product в канале peach"
- "что такое дефиниция orders?"
- "что возвращает v4/product?"
- "поле priceUnit в v4/product"
- "из каких полей состоит priceUnit в дефиниции v4/product канала peach?"
- "покажи дефиницию v4/catalog"
- "поищи в канале mango поле price"
- "покажи дефиниции канала peach"
- "что в канале есть по теме stock?"

### rejected
Всё остальное: написание кода, общие вопросы, разговоры, не связанные с data flow или каналами.

## Формат ответа

Для data_flow:
{"type": "data_flow"}

Для channel_search:
{"type": "channel_search", "channelAlias": "peach", "searchQuery": "метод v4/product"}
или если канал не указан:
{"type": "channel_search", "channelAlias": null, "searchQuery": "что ищем"}

Для rejected:
{"type": "rejected", "reason": "Краткое объяснение почему отклонено."}
""".trimIndent()
    }
}
