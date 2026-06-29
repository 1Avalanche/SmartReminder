package smartagent

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionConfigTest {

    @Test
    fun `defaults to DEEPSEEK and CHAT mode`() {
        val config = SessionConfig()
        assertEquals(ModelConfig.DEEPSEEK, config.currentModel)
        assertEquals(AgentMode.ASSIST, config.currentMode)
        assertNull(config.repoContext)
    }

    @Test
    fun `switchModel updates currentModel`() {
        val config = SessionConfig()
        config.switchModel(ModelConfig.QWEN)
        assertEquals(ModelConfig.QWEN, config.currentModel)
    }

    @Test
    fun `currentMode can be set via internal setter`() {
        val config = SessionConfig()
        config.currentMode = AgentMode.ARCHITECT
        assertEquals(AgentMode.ARCHITECT, config.currentMode)
    }

    @Test
    fun `repoContext is mutable`() {
        val config = SessionConfig()
        val repo = RepoContext(java.io.File("."))
        config.repoContext = repo
        assertEquals(repo, config.repoContext)
    }
}
