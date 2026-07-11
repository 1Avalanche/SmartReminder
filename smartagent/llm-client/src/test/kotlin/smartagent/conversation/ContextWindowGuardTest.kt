package smartagent.conversation

import org.junit.Test
import smartagent.Message
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContextWindowGuardTest {

    private fun msgs(totalChars: Int): List<Message> =
        listOf(Message("user", "x".repeat(totalChars)))

    @Test
    fun `returns false when under threshold`() {
        val guard = ContextWindowGuard(threshold = 0.8)
        // contextWindow=1000, threshold=0.8 → limit=800 tokens → 3200 chars
        // 3199 chars / 4 = 799 tokens < 800
        assertFalse(guard.needsCompression(msgs(3199), contextWindow = 1000))
    }

    @Test
    fun `returns true when over threshold`() {
        val guard = ContextWindowGuard(threshold = 0.8)
        // 3201 chars / 4 = 800 tokens > 800
        assertTrue(guard.needsCompression(msgs(3201), contextWindow = 1000))
    }

    @Test
    fun `custom threshold is respected`() {
        val guard = ContextWindowGuard(threshold = 0.5)
        // contextWindow=1000, threshold=0.5 → limit=500 tokens → 2000 chars
        assertTrue(guard.needsCompression(msgs(2001), contextWindow = 1000))
        assertFalse(guard.needsCompression(msgs(1999), contextWindow = 1000))
    }

    @Test
    fun `empty messages never trigger compression`() {
        val guard = ContextWindowGuard()
        assertFalse(guard.needsCompression(emptyList(), contextWindow = 100))
    }
}
