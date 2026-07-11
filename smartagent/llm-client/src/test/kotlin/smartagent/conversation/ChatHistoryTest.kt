package smartagent.conversation

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatHistoryTest {

    @Test
    fun `empty history returns no messages`() {
        val h = ChatHistory()
        assertTrue(h.buildContextMessages().isEmpty())
    }

    @Test
    fun `addExchange appends user and assistant messages`() {
        val h = ChatHistory()
        h.addExchange("hello", "hi")
        val msgs = h.buildContextMessages()
        assertEquals(2, msgs.size)
        assertEquals("user", msgs[0].role)
        assertEquals("hello", msgs[0].content)
        assertEquals("assistant", msgs[1].role)
        assertEquals("hi", msgs[1].content)
    }

    @Test
    fun `messagesToSummarize returns empty when at or below KEEP_RECENT`() {
        val h = ChatHistory()
        repeat(KEEP_RECENT / 2) { h.addExchange("q$it", "a$it") }
        assertTrue(h.messagesToSummarize().isEmpty())
    }

    @Test
    fun `messagesToSummarize returns overflow when above KEEP_RECENT`() {
        val h = ChatHistory()
        repeat(8) { h.addExchange("q$it", "a$it") } // 16 messages
        val toSummarize = h.messagesToSummarize()
        assertEquals(16 - KEEP_RECENT, toSummarize.size)
    }

    @Test
    fun `applySummary injects summary and keeps last KEEP_RECENT messages`() {
        val h = ChatHistory()
        repeat(8) { h.addExchange("q$it", "a$it") } // 16 messages
        h.applySummary("previous stuff")

        val msgs = h.buildContextMessages()
        // 2 summary messages + KEEP_RECENT recent
        assertEquals(2 + KEEP_RECENT, msgs.size)
        assertEquals("user", msgs[0].role)
        assertTrue(msgs[0].content.contains("previous stuff"))
        assertEquals("assistant", msgs[1].role)
    }

    @Test
    fun `addExchange after applySummary works correctly`() {
        val h = ChatHistory()
        repeat(8) { h.addExchange("q$it", "a$it") }
        h.applySummary("summary")
        h.addExchange("new question", "new answer")

        val msgs = h.buildContextMessages()
        assertEquals(2 + KEEP_RECENT + 2, msgs.size)
        assertEquals("new question", msgs[msgs.size - 2].content)
        assertEquals("new answer", msgs[msgs.size - 1].content)
    }

    @Test
    fun `applySummary replaces previous summary`() {
        val h = ChatHistory()
        repeat(8) { h.addExchange("q$it", "a$it") }
        h.applySummary("first summary")
        repeat(8) { h.addExchange("r$it", "b$it") }
        h.applySummary("updated summary")

        val msgs = h.buildContextMessages()
        assertTrue(msgs[0].content.contains("updated summary"))
    }
}
