package smartagent.investigator

import kotlinx.serialization.json.buildJsonObject
import smartagent.LLMGateway
import smartagent.Message
import smartagent.ModelConfig
import smartagent.OllamaOptions
import smartagent.investigator.agents.UiSearchAgent
import smartagent.investigator.model.UiAgentOutput
import smartagent.mcp_handler.McpTool
import smartagent.tools.github.FakeMcpSession
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UiSearchAgentTest {

    private val model = ModelConfig.MINIMAX

    private fun makeMcpSession(): smartagent.mcp_handler.McpSession {
        val tools = listOf(
            McpTool("search_code", "Search code", buildJsonObject {}),
            McpTool("get_file_contents", "Get file", buildJsonObject {})
        )
        return FakeMcpSession(tools = tools).asSession()
    }

    private fun makeGateway(responseContent: String): LLMGateway =
        object : LLMGateway {
            override fun chat(messages: List<Message>, model: ModelConfig, source: String, options: OllamaOptions?): LLMGateway.Response =
                LLMGateway.Response(responseContent)
        }

    private fun makeNullGateway(): LLMGateway =
        object : LLMGateway {
            override fun chat(messages: List<Message>, model: ModelConfig, source: String, options: OllamaOptions?): LLMGateway.Response? = null
        }

    @Test
    fun `parses found result with single item`() {
        val json = """
            {
              "status": "found",
              "items": [{
                "stringId": "guaranteed_stock_title",
                "displayText": "Гарантированный сток",
                "apiField": "stocks.other_stocks.guaranteed",
                "channelAlias": "peach",
                "apiMethod": "v4/product"
              }]
            }
        """.trimIndent()
        val gateway = makeGateway("FINAL_ANSWER\n$json")
        val agent = UiSearchAgent(makeMcpSession(), gateway, model, "my-org", "ui-repo")
        val (result) = agent.search("Гарантированный сток")
        assertIs<UiAgentOutput.Results>(result)
        assertEquals(1, result.items.size)
        assertEquals("guaranteed_stock_title", result.items[0].stringId)
        assertEquals("peach", result.items[0].channelAlias)
        assertEquals("v4/product", result.items[0].apiMethod)
    }

    @Test
    fun `parses not_found status`() {
        val gateway = makeGateway("FINAL_ANSWER\n{\"status\": \"not_found\"}")
        val agent = UiSearchAgent(makeMcpSession(), gateway, model, "my-org", "ui-repo")
        val (result) = agent.search("несуществующий элемент")
        assertIs<UiAgentOutput.NotFound>(result)
    }

    @Test
    fun `parses no_api_field status`() {
        val gateway = makeGateway("FINAL_ANSWER\n{\"status\": \"no_api_field\", \"stringId\": \"some_id\", \"displayText\": \"Какой-то текст\"}")
        val agent = UiSearchAgent(makeMcpSession(), gateway, model, "my-org", "ui-repo")
        val (result) = agent.search("Какой-то текст")
        assertIs<UiAgentOutput.NoApiField>(result)
        assertEquals("some_id", result.stringId)
        assertEquals("Какой-то текст", result.displayText)
    }

    @Test
    fun `parses multiple items for corner case two channels`() {
        val json = """
            {
              "status": "found",
              "items": [
                {"stringId": "price_label", "displayText": "Цена", "apiField": "price.value", "channelAlias": "peach", "apiMethod": "v4/product"},
                {"stringId": "price_label", "displayText": "Цена", "apiField": "price.discount", "channelAlias": "mango", "apiMethod": "v3/offers"}
              ]
            }
        """.trimIndent()
        val gateway = makeGateway("FINAL_ANSWER\n$json")
        val agent = UiSearchAgent(makeMcpSession(), gateway, model, "my-org", "ui-repo")
        val (result) = agent.search("цена")
        assertIs<UiAgentOutput.Results>(result)
        assertEquals(2, result.items.size)
    }

    @Test
    fun `returns SearchError when llm returns null`() {
        val agent = UiSearchAgent(makeMcpSession(), makeNullGateway(), model, "my-org", "ui-repo")
        val (result) = agent.search("запрос")
        assertIs<UiAgentOutput.SearchError>(result)
    }

    @Test
    fun `handles llm json with markdown fences`() {
        val json = "```json\n{\"status\": \"found\", \"items\": [{\"stringId\": \"x\", \"displayText\": \"X\", \"apiField\": \"f\", \"channelAlias\": \"ch\", \"apiMethod\": \"v1/m\"}]}\n```"
        val gateway = makeGateway("FINAL_ANSWER\n$json")
        val agent = UiSearchAgent(makeMcpSession(), gateway, model, "my-org", "ui-repo")
        val (result) = agent.search("X")
        assertIs<UiAgentOutput.Results>(result)
        assertTrue(result.items.isNotEmpty())
    }
}
