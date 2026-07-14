package smartagent.agent.assist

import org.junit.Test
import smartagent.FakeLLMGateway
import smartagent.ModelConfig
import smartagent.doc.DocGitContext
import smartagent.doc.KnowledgeService
import smartagent.doc.ProjectContext
import smartagent.tools.index.IndexMetadata
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AssistOrchestratorTest {

    @Test
    fun `handle does not throw when getContext throws`() {
        val orchestrator = AssistOrchestrator(ThrowingKnowledgeService(), FakeLLMGateway("FINAL_ANSWER\nError explanation."))
        val result = runCatching { orchestrator.handle("any query", ModelConfig.DEEPSEEK) }
        assertTrue(result.isSuccess)
    }

    @Test
    fun `handle returns non-null string when getContext throws`() {
        val orchestrator = AssistOrchestrator(ThrowingKnowledgeService(), FakeLLMGateway("FINAL_ANSWER\nSomething went wrong."))
        val result = runCatching { orchestrator.handle("query", ModelConfig.DEEPSEEK) }
        assertTrue(result.isSuccess)
        assertNotNull(result.getOrNull())
    }

    @Test
    fun `handle does not throw when getContext succeeds`() {
        val orchestrator = AssistOrchestrator(SucceedingKnowledgeService(), FakeLLMGateway("FINAL_ANSWER\nAll good."))
        val result = runCatching { orchestrator.handle("query", ModelConfig.DEEPSEEK) }
        assertTrue(result.isSuccess)
    }

    @Test
    fun `handle returns init instructions when not initialized`() {
        val orchestrator = AssistOrchestrator(UninitializedKnowledgeService(), FakeLLMGateway("FINAL_ANSWER\nUnreachable."))
        val result = orchestrator.handle("query", ModelConfig.DEEPSEEK)
        assertContains(result, "инициализируй проект")
        assertContains(result, "/init")
    }
}

private class ThrowingKnowledgeService : KnowledgeService {
    override fun getContext(query: String, topK: Int): ProjectContext =
        throw RuntimeException("Simulated embedding service unavailable")
    override fun init(owner: String, repo: String, branch: String, paths: List<String>) {}
    override fun reindex() {}
    override fun isStale(ttlHours: Int): Boolean = false
    override fun isInitialized(): Boolean = true
    override fun getStats(): IndexMetadata? = null
    override fun clear() {}
}

private class UninitializedKnowledgeService : KnowledgeService {
    override fun getContext(query: String, topK: Int): ProjectContext = error("should not be called")
    override fun init(owner: String, repo: String, branch: String, paths: List<String>) {}
    override fun reindex() {}
    override fun isStale(ttlHours: Int): Boolean = false
    override fun isInitialized(): Boolean = false
    override fun getStats(): IndexMetadata? = null
    override fun clear() {}
}

private class SucceedingKnowledgeService : KnowledgeService {
    override fun getContext(query: String, topK: Int): ProjectContext =
        ProjectContext(ragContext = "some rag context", gitContext = DocGitContext("main", emptyList()))
    override fun init(owner: String, repo: String, branch: String, paths: List<String>) {}
    override fun reindex() {}
    override fun isStale(ttlHours: Int): Boolean = false
    override fun isInitialized(): Boolean = true
    override fun getStats(): IndexMetadata? = null
    override fun clear() {}
}
