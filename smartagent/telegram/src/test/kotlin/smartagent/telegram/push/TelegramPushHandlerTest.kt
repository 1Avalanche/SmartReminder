package smartagent.telegram.push

import org.junit.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TelegramPushHandlerTest {

    private val handler = TelegramPushHandler()

    // --- parseCommand ---

    @Test
    fun `parseCommand returns null for bare push`() {
        assertNull(handler.parseCommand("/push"))
    }

    @Test
    fun `parseCommand returns null when missing args`() {
        assertNull(handler.parseCommand("/push owner/repo main"))
    }

    @Test
    fun `parseCommand returns null for bad owner repo format`() {
        assertNull(handler.parseCommand("/push badrepo main abc123 def456"))
    }

    @Test
    fun `parseCommand returns correct values for valid input`() {
        val parsed = handler.parseCommand("/push acme/myrepo main abc123 def456")
        assertNotNull(parsed)
        assertEquals("acme", parsed.owner)
        assertEquals("myrepo", parsed.repo)
        assertEquals("main", parsed.branch)
        assertEquals("abc123", parsed.beforeSha)
        assertEquals("def456", parsed.afterSha)
    }

    @Test
    fun `parseCommand handles branch with slash`() {
        val parsed = handler.parseCommand("/push acme/repo feature/auth abc def")
        assertNotNull(parsed)
        assertEquals("feature/auth", parsed.branch)
    }

    @Test
    fun `parseCommand handles zero sha for initial push`() {
        val zeroes = "0000000000000000000000000000000000000000"
        val parsed = handler.parseCommand("/push acme/repo main $zeroes deadbeef")
        assertNotNull(parsed)
        assertEquals(zeroes, parsed.beforeSha)
        assertEquals("deadbeef", parsed.afterSha)
    }

    // --- parseErrorMessage ---

    @Test
    fun `parseErrorMessage contains usage hint when no args`() {
        assertContains(handler.parseErrorMessage("/push"), "Использование:")
    }

    @Test
    fun `parseErrorMessage mentions format when owner repo bad`() {
        assertContains(handler.parseErrorMessage("/push badrepo main abc def"), "Формат")
    }

    @Test
    fun `parseErrorMessage mentions branch when missing`() {
        assertContains(handler.parseErrorMessage("/push owner/repo"), "branch")
    }

    @Test
    fun `parseErrorMessage mentions before_sha when missing`() {
        assertContains(handler.parseErrorMessage("/push owner/repo main"), "before_sha")
    }

    @Test
    fun `parseErrorMessage mentions after_sha when missing`() {
        assertContains(handler.parseErrorMessage("/push owner/repo main abc"), "after_sha")
    }

    // --- handle ---

    @Test
    fun `handle fails when github session absent`() {
        val parsed = TelegramPushHandler.ParsedPush("owner", "repo", "main", "abc", "def")
        val result = handler.handle(parsed)
        assert(result.isFailure)
        assertContains(result.exceptionOrNull()?.message ?: "", "GitHub MCP не подключён")
    }
}
