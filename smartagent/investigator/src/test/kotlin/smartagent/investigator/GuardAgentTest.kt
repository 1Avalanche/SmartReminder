package smartagent.investigator

import smartagent.LLMGateway
import smartagent.Message
import smartagent.ModelConfig
import smartagent.investigator.agents.GuardAgent
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GuardAgentTest {

    private val model = ModelConfig.MINIMAX

    private fun makeGateway(responseContent: String): LLMGateway =
        object : LLMGateway {
            override fun chat(messages: List<Message>, model: ModelConfig, source: String, options: smartagent.OllamaOptions?): LLMGateway.Response =
                LLMGateway.Response(responseContent)
        }

    @Test
    fun `allows data flow question`() {
        val agent = GuardAgent(makeGateway("""{"allowed": true}"""), model)
        assertTrue(agent.isAllowed("откуда берутся данные для карточки товара?"))
    }

    @Test
    fun `blocks general question`() {
        val agent = GuardAgent(makeGateway("""{"allowed": false}"""), model)
        assertFalse(agent.isAllowed("напиши код на Kotlin"))
    }

    @Test
    fun `allows channel data question`() {
        val agent = GuardAgent(makeGateway("""{"allowed":true}"""), model)
        assertTrue(agent.isAllowed("из какого канала приходит цена?"))
    }

    @Test
    fun `blocks greeting`() {
        val agent = GuardAgent(makeGateway("""{"allowed":false}"""), model)
        assertFalse(agent.isAllowed("привет, как дела?"))
    }

    @Test
    fun `returns false when llm returns null`() {
        val nullGateway = object : LLMGateway {
            override fun chat(messages: List<Message>, model: ModelConfig, source: String, options: smartagent.OllamaOptions?): LLMGateway.Response? = null
        }
        val agent = GuardAgent(nullGateway, model)
        assertFalse(agent.isAllowed("любой запрос"))
    }

    @Test
    fun `handles json with spaces`() {
        val agent = GuardAgent(makeGateway("""{ "allowed" : true }"""), model)
        assertTrue(agent.isAllowed("откуда данные на главном экране?"))
    }

    @Test
    fun `handles llm adding markdown fences`() {
        val agent = GuardAgent(makeGateway("```json\n{\"allowed\": true}\n```"), model)
        assertTrue(agent.isAllowed("что за поле api используется здесь?"))
    }
}
