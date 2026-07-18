package smartagent.push

import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PushHandlerTest {

    // --- parseDiffResult ---

    @Test
    fun `parseDiffResult extracts files and author from compare response`() {
        val json = """
        {
          "files": [
            {"filename": "src/Foo.kt", "status": "modified", "additions": 5, "deletions": 2},
            {"filename": "src/Bar.kt", "status": "added", "additions": 10, "deletions": 0}
          ],
          "commits": [
            {
              "commit": {
                "author": {"name": "Jane Doe"},
                "message": "fix: auth bug\n\nDetailed description"
              }
            }
          ]
        }
        """.trimIndent()

        val fakeMcp = FakePushMcpSession()
        val handler = PushHandler(fakeMcp.session)
        val result = handler.parseDiffResult(json)

        assertEquals(2, result.files.size)
        assertEquals("src/Foo.kt", result.files[0].filename)
        assertEquals("modified", result.files[0].status)
        assertEquals(5, result.files[0].additions)
        assertEquals(2, result.files[0].deletions)
        assertEquals("src/Bar.kt", result.files[1].filename)
        assertEquals("added", result.files[1].status)
        assertEquals("Jane Doe", result.authors.first())
        assertEquals("fix: auth bug", result.commitMessages.first())
    }

    @Test
    fun `parseDiffResult returns empty result for invalid json`() {
        val fakeMcp = FakePushMcpSession()
        val handler = PushHandler(fakeMcp.session)
        val result = handler.parseDiffResult("not-json")
        assertEquals(0, result.files.size)
        assertEquals("unknown", result.authors.first())
    }

    @Test
    fun `parseDiffResult handles missing commits array`() {
        val json = """{"files": [{"filename": "Foo.kt", "status": "modified", "additions": 1, "deletions": 0}]}"""
        val fakeMcp = FakePushMcpSession()
        val handler = PushHandler(fakeMcp.session)
        val result = handler.parseDiffResult(json)
        assertEquals(1, result.files.size)
        assertEquals("unknown", result.authors.first())
    }

    // --- parseCommitsResult ---

    @Test
    fun `parseCommitsResult extracts author and message from commits array`() {
        val json = """
        [
          {
            "commit": {
              "author": {"name": "John Smith"},
              "message": "chore: cleanup"
            }
          }
        ]
        """.trimIndent()
        val fakeMcp = FakePushMcpSession()
        val handler = PushHandler(fakeMcp.session)
        val result = handler.parseCommitsResult(json)
        assertEquals("John Smith", result.authors.first())
        assertEquals("chore: cleanup", result.commitMessages.first())
        assertEquals(0, result.files.size)
    }

    // --- buildEntry ---

    @Test
    fun `buildEntry contains date author and branch`() {
        val fakeMcp = FakePushMcpSession()
        val handler = PushHandler(fakeMcp.session)
        val diff = PushHandler.DiffResult(
            files = listOf(PushHandler.FileChange("Foo.kt", "modified", 3, 1)),
            authors = listOf("Alice"),
            commitMessages = listOf("refactor: cleanup")
        )
        val entry = handler.buildEntry(diff, "main")
        val today = LocalDate.now().toString()
        assertContains(entry, today)
        assertContains(entry, "Alice")
        assertContains(entry, "main")
    }

    @Test
    fun `buildEntry lists changed files with stats`() {
        val fakeMcp = FakePushMcpSession()
        val handler = PushHandler(fakeMcp.session)
        val diff = PushHandler.DiffResult(
            files = listOf(
                PushHandler.FileChange("Foo.kt", "modified", 5, 2),
                PushHandler.FileChange("Bar.kt", "added", 10, 0)
            ),
            authors = listOf("Bob"),
            commitMessages = listOf("feat: add bar")
        )
        val entry = handler.buildEntry(diff, "feature/x")
        assertContains(entry, "Foo.kt")
        assertContains(entry, "modified")
        assertContains(entry, "+5/-2")
        assertContains(entry, "Bar.kt")
        assertContains(entry, "added")
        assertContains(entry, "feat: add bar")
    }

    @Test
    fun `buildEntry handles no files gracefully`() {
        val fakeMcp = FakePushMcpSession()
        val handler = PushHandler(fakeMcp.session)
        val diff = PushHandler.DiffResult(emptyList(), listOf("Dev"), listOf("init"))
        val entry = handler.buildEntry(diff, "main")
        assertContains(entry, "Dev")
        assertContains(entry, "init")
    }

    // --- CHANGELOG_CANDIDATES ---

    @Test
    fun `CHANGELOG_CANDIDATES includes common names`() {
        assertContains(PushHandler.CHANGELOG_CANDIDATES, "CHANGELOG.md")
        assertContains(PushHandler.CHANGELOG_CANDIDATES, "CHANGES.md")
        assertContains(PushHandler.CHANGELOG_CANDIDATES, "HISTORY.md")
        assertContains(PushHandler.CHANGELOG_CANDIDATES, "changelog.md")
    }
}

private class FakePushMcpSession {
    val session: smartagent.mcp_handler.McpSession by lazy {
        val config = smartagent.mcp_handler.McpServerConfig(name = "github", command = emptyList())
        smartagent.mcp_handler.McpSession("github", config)
    }
}
