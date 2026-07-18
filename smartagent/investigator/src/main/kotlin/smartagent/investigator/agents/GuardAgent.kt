package smartagent.investigator.agents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import smartagent.LLMGateway
import smartagent.Message
import smartagent.ModelConfig

class GuardAgent(private val gateway: LLMGateway, private val model: ModelConfig) {

    fun isAllowed(query: String): Boolean {
        val messages = listOf(
            Message("system", SYSTEM_PROMPT),
            Message("user", query)
        )
        val response = runCatching {
            gateway.chat(messages, model, "investigator-guard")
        }.getOrElse { e ->
            System.err.println("[guard] LLM call failed: ${e.message}")
            return false
        } ?: return false

        return parseAllowed(response.content)
    }

    private fun parseAllowed(content: String): Boolean {
        val cleaned = extractJson(content)
        return runCatching {
            val obj = Json.parseToJsonElement(cleaned) as JsonObject
            (obj["allowed"] as? JsonPrimitive)?.booleanOrNull ?: false
        }.getOrElse {
            cleaned.contains("\"allowed\":true") || cleaned.contains("\"allowed\": true")
        }
    }

    companion object {
        internal val SYSTEM_PROMPT = """
Ты — классификатор запросов для системы анализа data flow.

Твоя задача: определить, является ли запрос вопросом о связях между UI-отображением и источниками данных.

Разрешённые запросы — вопросы вида:
- "откуда берутся данные для [элемента UI]?"
- "из какого канала/бэкенда приходит [поле/виджет]?"
- "как данные попадают в [экран/карточку/компонент]?"
- "какой API вызывается для [UI-элемента]?"

Запрещено всё остальное: написание кода, общие технические вопросы, разговоры, просьбы помочь с задачей.

Ответь строго JSON без markdown-обёртки:
{"allowed": true}
или
{"allowed": false}
""".trimIndent()
    }
}
