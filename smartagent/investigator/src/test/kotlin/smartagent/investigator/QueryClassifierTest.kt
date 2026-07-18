package smartagent.investigator

import smartagent.LLMGateway
import smartagent.Message
import smartagent.ModelConfig
import smartagent.OllamaOptions
import smartagent.investigator.agents.QueryClassifier
import smartagent.investigator.model.QueryType
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class QueryClassifierTest {

    private val model = ModelConfig.MINIMAX

    private fun makeGateway(response: String): LLMGateway =
        object : LLMGateway {
            override fun chat(messages: List<Message>, model: ModelConfig, source: String, options: OllamaOptions?): LLMGateway.Response =
                LLMGateway.Response(response)
        }

    private fun makeNullGateway(): LLMGateway =
        object : LLMGateway {
            override fun chat(messages: List<Message>, model: ModelConfig, source: String, options: OllamaOptions?): LLMGateway.Response? = null
        }

    @Test
    fun `classifies data flow question`() {
        val classifier = QueryClassifier(makeGateway("""{"type": "data_flow"}"""), model)
        val result = classifier.classify("откуда данные на карточке товара?")
        assertIs<QueryType.DataFlow>(result)
    }

    @Test
    fun `classifies channel search with alias`() {
        val classifier = QueryClassifier(
            makeGateway("""{"type": "channel_search", "channelAlias": "peach", "searchQuery": "метод v4/product"}"""),
            model
        )
        val result = classifier.classify("найди метод v4/product в канале peach")
        assertIs<QueryType.ChannelSearch>(result)
        assertEquals("peach", result.channelAlias)
        assertEquals("метод v4/product", result.searchQuery)
    }

    @Test
    fun `classifies channel search without alias`() {
        val classifier = QueryClassifier(
            makeGateway("""{"type": "channel_search", "channelAlias": null, "searchQuery": "дефиниция orders"}"""),
            model
        )
        val result = classifier.classify("найди дефиницию orders")
        assertIs<QueryType.ChannelSearch>(result)
        assertNull(result.channelAlias)
        assertEquals("дефиниция orders", result.searchQuery)
    }

    @Test
    fun `classifies rejected query`() {
        val classifier = QueryClassifier(
            makeGateway("""{"type": "rejected", "reason": "Это не вопрос о data flow."}"""),
            model
        )
        val result = classifier.classify("напиши код на Python")
        assertIs<QueryType.Rejected>(result)
    }

    @Test
    fun `returns rejected when llm fails`() {
        val classifier = QueryClassifier(makeNullGateway(), model)
        val result = classifier.classify("любой запрос")
        assertIs<QueryType.Rejected>(result)
    }

    @Test
    fun `classifies explicit api path without verb as channel_search`() {
        val classifier = QueryClassifier(
            makeGateway("""{"type": "channel_search", "channelAlias": null, "searchQuery": "v4/product"}"""),
            model
        )
        val result = classifier.classify("v4/product")
        assertIs<QueryType.ChannelSearch>(result)
        assertNull(result.channelAlias)
    }

    @Test
    fun `classifies definition query as channel_search`() {
        val classifier = QueryClassifier(
            makeGateway("""{"type": "channel_search", "channelAlias": null, "searchQuery": "дефиниция v4/product"}"""),
            model
        )
        val result = classifier.classify("что такое дефиниция v4/product?")
        assertIs<QueryType.ChannelSearch>(result)
    }

    @Test
    fun `classifies field-in-method query as channel_search`() {
        val classifier = QueryClassifier(
            makeGateway("""{"type": "channel_search", "channelAlias": "peach", "searchQuery": "поле priceUnit в v4/product"}"""),
            model
        )
        val result = classifier.classify("из каких полей состоит priceUnit в v4/product канала peach?")
        assertIs<QueryType.ChannelSearch>(result)
        assertEquals("peach", result.channelAlias)
    }

    @Test
    fun `classifies ui field question as data_flow not channel_search`() {
        val classifier = QueryClassifier(
            makeGateway("""{"type": "data_flow"}"""),
            model
        )
        val result = classifier.classify("на карточке товара есть поле Недоступно для продажи — откуда данные?")
        assertIs<QueryType.DataFlow>(result)
    }

    @Test
    fun `handles markdown fences in response`() {
        val classifier = QueryClassifier(
            makeGateway("```json\n{\"type\": \"data_flow\"}\n```"),
            model
        )
        val result = classifier.classify("откуда берётся цена?")
        assertIs<QueryType.DataFlow>(result)
    }

    @Test
    fun `handles channel alias string null value`() {
        val classifier = QueryClassifier(
            makeGateway("""{"type": "channel_search", "channelAlias": "null", "searchQuery": "что ищем"}"""),
            model
        )
        val result = classifier.classify("поищи что-то")
        assertIs<QueryType.ChannelSearch>(result)
        assertNull(result.channelAlias)
    }
}
