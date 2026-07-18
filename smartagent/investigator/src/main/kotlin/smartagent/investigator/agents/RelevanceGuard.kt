package smartagent.investigator.agents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import smartagent.LLMGateway
import smartagent.Message
import smartagent.ModelConfig

class RelevanceGuard(private val gateway: LLMGateway, private val model: ModelConfig) {

    sealed class Result {
        object Confident : Result()
        data class Uncertain(val note: String) : Result()
    }

    fun check(query: String, displayText: String, stringId: String): Result {
        val prompt = """
Пользователь спросил: "$query"
Найден UI-элемент: "$displayText" (id: $stringId)

Оцени соответствие. Правила:
- CONFIDENT: элемент явно отвечает на вопрос. Имена товаров/объектов в запросе — это контекст, не UI-элемент.
  Пример: "цена свинных крылышек" + найден "Цена" → confident
- UNCERTAIN: элемент найден по смежной теме, но может быть не то что нужно.
  Пример: "статус заказа на доставку" + найден "Статус резервирования" → uncertain
- UNCERTAIN: displayText совпадает с именем продукта/объекта из запроса пользователя.
  Это признак того, что агент придумал stringId из контекста, а не нашёл в strings.xml.
  Пример: "откуда данные о свинных крылышках в консультациях" + найден "свинные крылышки (название продукта из API)"
  → uncertain (displayText скопирован из запроса — вероятная галлюцинация агента)

Верни строго JSON без markdown: {"result": "confident"} или {"result": "uncertain", "note": "краткое объяснение"}
""".trimIndent()

        val content = runCatching {
            gateway.chat(listOf(Message("user", prompt)), model, "investigator-relevance-guard")
        }.getOrNull()?.content?.trim() ?: return Result.Confident

        return runCatching {
            val cleaned = extractJson(content)
            val obj = Json.parseToJsonElement(cleaned) as JsonObject
            when ((obj["result"] as? JsonPrimitive)?.content) {
                "uncertain" -> {
                    val note = (obj["note"] as? JsonPrimitive)?.content ?: "Возможное несоответствие найденного элемента запросу."
                    Result.Uncertain(note)
                }
                else -> Result.Confident
            }
        }.getOrElse { Result.Confident }
    }
}
