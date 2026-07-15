package smartagent.telegram.review

import org.junit.Test
import smartagent.LLMGateway
import smartagent.Message
import smartagent.ModelConfig
import smartagent.OllamaOptions
import smartagent.doc.KnowledgeService
import smartagent.doc.ProjectContext
import smartagent.tools.index.IndexMetadata
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private class FakeGateway : LLMGateway {
    override fun chat(messages: List<Message>, model: ModelConfig, source: String, options: OllamaOptions?): LLMGateway.Response? = null
}

private class FakeKnowledgeService : KnowledgeService {
    override fun getContext(query: String, topK: Int): ProjectContext = ProjectContext("", null)
    override fun init(owner: String, repo: String, branch: String, paths: List<String>) {}
    override fun reindex() {}
    override fun isStale(ttlHours: Int): Boolean = false
    override fun isInitialized(): Boolean = false
    override fun getStats(): IndexMetadata? = null
    override fun clear() {}
}

class TelegramReviewHandlerTest {

    private val handler = TelegramReviewHandler(FakeGateway(), FakeKnowledgeService())

    // --- parseCommand ---

    @Test
    fun `parseCommand returns null for bare review`() {
        assertNull(handler.parseCommand("/review"))
    }

    @Test
    fun `parseCommand returns null when pr number missing`() {
        assertNull(handler.parseCommand("/review owner/repo"))
    }

    @Test
    fun `parseCommand returns null for bad owner repo format`() {
        assertNull(handler.parseCommand("/review badformat 1"))
    }

    @Test
    fun `parseCommand returns null for non-numeric pr`() {
        assertNull(handler.parseCommand("/review owner/repo abc"))
    }

    @Test
    fun `parseCommand returns correct values for valid input`() {
        val parsed = handler.parseCommand("/review foo/bar 42")
        assertNotNull(parsed)
        assertEquals("foo", parsed.owner)
        assertEquals("bar", parsed.repo)
        assertEquals(42, parsed.prNumber)
    }

    // --- parseErrorMessage ---

    @Test
    fun `parseErrorMessage contains usage hint when no args`() {
        assertContains(handler.parseErrorMessage("/review"), "Использование:")
    }

    @Test
    fun `parseErrorMessage mentions PR number when missing`() {
        assertContains(handler.parseErrorMessage("/review owner/repo"), "Номер PR")
    }

    @Test
    fun `parseErrorMessage mentions format when owner repo bad`() {
        assertContains(handler.parseErrorMessage("/review badformat 1"), "Формат:")
    }

    // --- runAndPublish ---

    @Test
    fun `runAndPublish fails when github session absent`() {
        val result = handler.runAndPublish("owner", "repo", 1)
        assert(result.isFailure)
        assertContains(result.exceptionOrNull()?.message ?: "", "GitHub MCP не подключён")
    }
}
