package smartagent.review

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import smartagent.mcp_handler.McpServerConfig
import smartagent.mcp_handler.McpSession
import java.time.Instant
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class GitHubReviewPublisherTest {

    private class FakeMcpSession(
        private val response: (toolName: String, args: Map<String, JsonElement>) -> JsonElement?
    ) : McpSession("fake", McpServerConfig(name = "fake")) {
        val calls = mutableListOf<Pair<String, Map<String, JsonElement>>>()

        override fun callTool(toolName: String, arguments: Map<String, JsonElement>): JsonElement? {
            calls += toolName to arguments
            return response(toolName, arguments)
        }
    }

    private fun successResult(text: String): JsonElement = buildJsonObject {
        put("isError", false)
        put("content", buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", text)
            })
        })
    }

    private fun errorResult(text: String): JsonElement = buildJsonObject {
        put("isError", true)
        put("content", buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", text)
            })
        })
    }

    private fun makeReport(issues: List<ReviewIssue> = emptyList()) = ReviewReport(
        owner = "owner",
        repo = "repo",
        pullRequestNumber = 7,
        prTitle = "Fix login bug",
        timestamp = Instant.parse("2024-01-15T10:00:00Z").toString(),
        summary = "Overall the PR looks good.",
        issues = issues
    )

    @Test
    fun `toMarkdown includes PR number and title`() {
        val publisher = GitHubReviewPublisher(session = null)
        val md = publisher.toMarkdown(makeReport())

        assertContains(md, "PR #7")
        assertContains(md, "Fix login bug")
    }

    @Test
    fun `toMarkdown shows no issues message when empty`() {
        val publisher = GitHubReviewPublisher(session = null)
        val md = publisher.toMarkdown(makeReport())

        assertContains(md, "No issues found")
    }

    @Test
    fun `toMarkdown renders issues grouped by severity`() {
        val issues = listOf(
            ReviewIssue(ReviewSeverity.HIGH, ReviewCategory.BUG, "Foo.kt", 42, "NPE risk", "Add null check"),
            ReviewIssue(ReviewSeverity.LOW, ReviewCategory.CODE_QUALITY, "Bar.kt", null, "Long method", "Extract")
        )
        val publisher = GitHubReviewPublisher(session = null)
        val md = publisher.toMarkdown(makeReport(issues))

        assertContains(md, "HIGH")
        assertContains(md, "LOW")
        assertContains(md, "Foo.kt:42")
        assertContains(md, "Bar.kt")
        assertContains(md, "NPE risk")
        assertContains(md, "Add null check")
        assertFalse(md.contains("No issues found"))
    }

    @Test
    fun `toMarkdown includes summary`() {
        val publisher = GitHubReviewPublisher(session = null)
        val md = publisher.toMarkdown(makeReport())

        assertContains(md, "Overall the PR looks good.")
    }

    @Test
    fun `toMarkdown renders all severity icons`() {
        val allSeverities = ReviewSeverity.entries.map { sev ->
            ReviewIssue(sev, ReviewCategory.CODE_QUALITY, "F.kt", null, "desc", "rec")
        }
        val publisher = GitHubReviewPublisher(session = null)
        val md = publisher.toMarkdown(makeReport(allSeverities))

        assertContains(md, "🔴")
        assertContains(md, "🟠")
        assertContains(md, "🟡")
        assertContains(md, "🔵")
        assertContains(md, "⚪")
    }

    @Test
    fun `publish throws when session is null`() {
        val publisher = GitHubReviewPublisher(session = null)
        assertFailsWith<IllegalStateException> {
            publisher.publish(makeReport())
        }
    }

    @Test
    fun `publish succeeds with issue_number`() {
        val session = FakeMcpSession { _, args ->
            if (args.containsKey("issue_number")) successResult("comment created") else null
        }
        val publisher = GitHubReviewPublisher(session)
        val result = publisher.publish(makeReport())

        assertContains(result, "comment created")
        assert(session.calls.size == 1) { "Expected 1 MCP call, got ${session.calls.size}" }
        assert(session.calls[0].second.containsKey("issue_number"))
    }

    @Test
    fun `publish passes correct owner repo and body`() {
        var capturedArgs: Map<String, JsonElement>? = null
        val session = FakeMcpSession { _, args ->
            capturedArgs = args
            successResult("ok")
        }
        val publisher = GitHubReviewPublisher(session)
        publisher.publish(makeReport())

        val args = capturedArgs!!
        assertContains(args["owner"].toString(), "owner")
        assertContains(args["repo"].toString(), "repo")
        assertContains(args["body"].toString(), "PR #7")
    }

    @Test
    fun `publish throws with error message when MCP returns null`() {
        val session = FakeMcpSession { _, _ -> null }
        val publisher = GitHubReviewPublisher(session)

        val ex = assertFailsWith<IllegalStateException> {
            publisher.publish(makeReport())
        }
        assertContains(ex.message ?: "", "Failed to post review comment")
    }

    @Test
    fun `publish throws when MCP returns error result`() {
        val session = FakeMcpSession { _, _ -> errorResult("422 Unprocessable Entity") }
        val publisher = GitHubReviewPublisher(session)

        val ex = assertFailsWith<IllegalStateException> {
            publisher.publish(makeReport())
        }
        assertContains(ex.message ?: "", "422 Unprocessable Entity")
    }
}
