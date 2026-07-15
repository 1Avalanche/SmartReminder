package smartagent.review

import org.junit.Test
import smartagent.FakeLLMGateway
import smartagent.ModelConfig
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReviewAgentTest {

    private val context = ReviewContext(
        owner = "owner",
        repo = "repo",
        pullRequestNumber = 42,
        prTitle = "Add feature X",
        prDescription = "Implements X",
        baseBranch = "main",
        headBranch = "feature/x",
        changedFiles = "src/X.kt",
        diff = "+fun x() {}",
        ragContext = "",
        projectRules = ""
    )

    @Test
    fun `review parses valid JSON response`() {
        val json = """
            {
              "summary": "Looks good overall",
              "issues": [
                {
                  "severity": "HIGH",
                  "category": "BUG",
                  "file": "src/X.kt",
                  "line": 10,
                  "description": "Null not handled",
                  "recommendation": "Add null check"
                }
              ]
            }
        """.trimIndent()

        val agent = ReviewAgent(FakeLLMGateway(json))
        val report = agent.review(context, ModelConfig.DEEPSEEK)

        assertEquals("Looks good overall", report.summary)
        assertEquals(1, report.issues.size)
        assertEquals(ReviewSeverity.HIGH, report.issues[0].severity)
        assertEquals(ReviewCategory.BUG, report.issues[0].category)
        assertEquals("src/X.kt", report.issues[0].file)
        assertEquals(10, report.issues[0].line)
    }

    @Test
    fun `review fills report metadata from context`() {
        val json = """{"summary": "ok", "issues": []}"""
        val agent = ReviewAgent(FakeLLMGateway(json))
        val report = agent.review(context, ModelConfig.DEEPSEEK)

        assertEquals("owner", report.owner)
        assertEquals("repo", report.repo)
        assertEquals(42, report.pullRequestNumber)
        assertEquals("Add feature X", report.prTitle)
        assertNotNull(report.timestamp)
    }

    @Test
    fun `review handles LLM response with json in markdown block`() {
        val response = """
            Here is my analysis:
            ```json
            {"summary": "Minor issues", "issues": []}
            ```
        """.trimIndent()

        val agent = ReviewAgent(FakeLLMGateway(response))
        val report = agent.review(context, ModelConfig.DEEPSEEK)

        assertEquals("Minor issues", report.summary)
        assertTrue(report.issues.isEmpty())
    }

    @Test
    fun `review falls back gracefully when LLM returns non-JSON`() {
        val plainText = "Everything looks fine, no issues found."
        val agent = ReviewAgent(FakeLLMGateway(plainText))
        val report = agent.review(context, ModelConfig.DEEPSEEK)

        assertTrue(report.summary.isNotBlank())
        assertTrue(report.issues.isEmpty())
    }

    @Test
    fun `review skips malformed issues without throwing`() {
        val json = """
            {
              "summary": "partial",
              "issues": [
                {"severity": "INVALID_ENUM", "category": "BUG", "file": "f.kt", "description": "d", "recommendation": "r"},
                {"severity": "LOW", "category": "CODE_QUALITY", "file": "g.kt", "description": "d2", "recommendation": "r2"}
              ]
            }
        """.trimIndent()

        val agent = ReviewAgent(FakeLLMGateway(json))
        val report = agent.review(context, ModelConfig.DEEPSEEK)

        assertEquals(1, report.issues.size)
        assertEquals(ReviewSeverity.LOW, report.issues[0].severity)
    }

    @Test
    fun `review throws when gateway returns null`() {
        val agent = ReviewAgent(FakeLLMGateway())
        val result = runCatching { agent.review(context, ModelConfig.DEEPSEEK) }
        assertTrue(result.isFailure)
    }
}
