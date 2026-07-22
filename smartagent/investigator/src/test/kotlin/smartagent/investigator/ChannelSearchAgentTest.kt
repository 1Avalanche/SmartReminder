package smartagent.investigator

import kotlinx.serialization.json.buildJsonObject
import smartagent.LLMGateway
import smartagent.Message
import smartagent.ModelConfig
import smartagent.OllamaOptions
import smartagent.investigator.agents.ChannelSearchAgent
import smartagent.investigator.model.ChannelAgentOutput
import smartagent.investigator.model.UiSearchResult
import smartagent.mcp_handler.McpTool
import smartagent.tools.github.FakeMcpSession
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ChannelSearchAgentTest {

    private val model = ModelConfig.DeepSeekFlash

    private val sampleUiResult = UiSearchResult(
        stringId = "guaranteed_stock_title",
        displayText = "Гарантированный сток",
        apiField = "stocks.other_stocks.guaranteed",
        channelAlias = "peach",
        apiMethod = "v4/product"
    )

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
    fun `parses found result`() {
        val json = """
            {
              "status": "found",
              "definitionPath": "definitions/product",
              "backendAlias": "back_stock",
              "backendHost": "https://back-stock.example.com",
              "backendBasePath": "/v1/stock",
              "sourceFields": ["stocks.other", "stocks.plus"],
              "transformation": null
            }
        """.trimIndent()
        val gateway = makeGateway("FINAL_ANSWER\n$json")
        val agent = ChannelSearchAgent(makeMcpSession(), gateway, model, "my-org")
        val result = agent.search(sampleUiResult, "peach-repo")
        assertIs<ChannelAgentOutput.Result>(result)
        assertEquals("back_stock", result.data.backendAlias)
        assertEquals("https://back-stock.example.com", result.data.backendHost)
        assertEquals(2, result.data.sourceFields.size)
    }

    @Test
    fun `parses no_method status`() {
        val gateway = makeGateway("FINAL_ANSWER\n{\"status\": \"no_method\", \"param\": \"v4/product\"}")
        val agent = ChannelSearchAgent(makeMcpSession(), gateway, model, "my-org")
        val result = agent.search(sampleUiResult, "peach-repo")
        assertIs<ChannelAgentOutput.NoMethod>(result)
        assertEquals("v4/product", result.param)
        assertEquals("peach", result.channel)
    }

    @Test
    fun `parses no_backend status`() {
        val gateway = makeGateway("FINAL_ANSWER\n{\"status\": \"no_backend\"}")
        val agent = ChannelSearchAgent(makeMcpSession(), gateway, model, "my-org")
        val result = agent.search(sampleUiResult, "peach-repo")
        assertIs<ChannelAgentOutput.NoBackend>(result)
        assertEquals("peach", result.channel)
    }

    @Test
    fun `returns SearchError when llm returns null`() {
        val agent = ChannelSearchAgent(makeMcpSession(), makeNullGateway(), model, "my-org")
        val result = agent.search(sampleUiResult, "peach-repo")
        assertIs<ChannelAgentOutput.SearchError>(result)
    }

    @Test
    fun `parses sourceFields list correctly`() {
        val json = """{"status":"found","definitionPath":"definitions/p","backendAlias":"b","backendHost":"h","backendBasePath":"/p","sourceFields":["f1","f2","f3"]}"""
        val gateway = makeGateway("FINAL_ANSWER\n$json")
        val agent = ChannelSearchAgent(makeMcpSession(), gateway, model, "my-org")
        val result = agent.search(sampleUiResult, "peach-repo")
        assertIs<ChannelAgentOutput.Result>(result)
        assertTrue(result.data.sourceFields.containsAll(listOf("f1", "f2", "f3")))
    }

    @Test
    fun `stores channelRepo in result`() {
        val json = """{"status":"found","definitionPath":"def/p","backendAlias":"b","backendHost":"h","backendBasePath":"/p","sourceFields":["f"]}"""
        val gateway = makeGateway("FINAL_ANSWER\n$json")
        val agent = ChannelSearchAgent(makeMcpSession(), gateway, model, "my-org")
        val result = agent.search(sampleUiResult, "peach-repo")
        assertIs<ChannelAgentOutput.Result>(result)
        assertEquals("peach-repo", result.data.channelRepo)
    }
}
