package smartagent.telegram.review

import org.junit.Test
import smartagent.LLMGateway
import smartagent.Message
import smartagent.ModelConfig
import smartagent.OllamaOptions
import smartagent.doc.KnowledgeService
import smartagent.doc.ProjectContext
import smartagent.review.ReviewCategory
import smartagent.review.ReviewContext
import smartagent.review.ReviewIssue
import smartagent.review.ReviewReport
import smartagent.review.ReviewSeverity
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

    // --- formatTelegramSummary ---

    private fun makeReviewResult(
        filesJson: String = """[{"filename":"Foo.kt","status":"modified"},{"filename":"Bar.kt","status":"added"}]""",
        issues: List<ReviewIssue> = listOf(
            ReviewIssue(ReviewSeverity.CRITICAL, ReviewCategory.BUG, "Foo.kt", 10, "desc", "fix"),
            ReviewIssue(ReviewSeverity.HIGH, ReviewCategory.SECURITY, "Bar.kt", null, "desc2", "fix2")
        )
    ): TelegramReviewHandler.ReviewResult {
        val ctx = ReviewContext(
            owner = "acme", repo = "myrepo", pullRequestNumber = 42,
            prTitle = "Add feature", prDescription = "",
            baseBranch = "main", headBranch = "feature/x",
            changedFiles = filesJson, diff = "", ragContext = "", projectRules = ""
        )
        val report = ReviewReport(
            owner = "acme", repo = "myrepo", pullRequestNumber = 42,
            prTitle = "Add feature", timestamp = "2024-01-01T00:00:00Z",
            summary = "Overall looks fine.", issues = issues
        )
        return TelegramReviewHandler.ReviewResult(ctx, report, "")
    }

    @Test
    fun `formatTelegramSummary contains repo name`() {
        val msg = handler.formatTelegramSummary(makeReviewResult())
        assertContains(msg, "acme/myrepo")
    }

    @Test
    fun `formatTelegramSummary contains PR title and number`() {
        val msg = handler.formatTelegramSummary(makeReviewResult())
        assertContains(msg, "PR #42")
        assertContains(msg, "Add feature")
    }

    @Test
    fun `formatTelegramSummary contains branches`() {
        val msg = handler.formatTelegramSummary(makeReviewResult())
        assertContains(msg, "feature/x")
        assertContains(msg, "main")
    }

    @Test
    fun `formatTelegramSummary lists changed files`() {
        val msg = handler.formatTelegramSummary(makeReviewResult())
        assertContains(msg, "Foo.kt")
        assertContains(msg, "Bar.kt")
        assertContains(msg, "modified")
        assertContains(msg, "added")
    }

    @Test
    fun `formatTelegramSummary contains summary text`() {
        val msg = handler.formatTelegramSummary(makeReviewResult())
        assertContains(msg, "Overall looks fine.")
    }

    @Test
    fun `formatTelegramSummary shows issue counts`() {
        val msg = handler.formatTelegramSummary(makeReviewResult())
        assertContains(msg, "2 найдено")
        assertContains(msg, "CRITICAL: 1")
        assertContains(msg, "HIGH: 1")
    }

    @Test
    fun `formatTelegramSummary handles no issues`() {
        val msg = handler.formatTelegramSummary(makeReviewResult(issues = emptyList()))
        assertContains(msg, "0 найдено")
    }

    @Test
    fun `formatTelegramSummary handles invalid files json gracefully`() {
        val msg = handler.formatTelegramSummary(makeReviewResult(filesJson = "not-json"))
        assertContains(msg, "acme/myrepo")
    }
}
