package smartagent

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversationHistoryTest {

    private fun entry(input: String, response: String = "reply") =
        LogEntry(userInput = input, requestPayload = "{}", apiResponse = response)

    @Test
    fun `starts empty`() {
        val h = ConversationHistory()
        assertEquals(0, h.getHistory().size)
        assertEquals(0, h.userMessageCount)
        assertEquals("", h.summary)
    }

    @Test
    fun `addLogEntry increments userMessageCount`() {
        val h = ConversationHistory()
        h.addLogEntry(entry("hello"))
        h.addLogEntry(entry("world"))
        assertEquals(2, h.userMessageCount)
        assertEquals(2, h.getHistory().size)
    }

    @Test
    fun `addLogEntry triggers onChange`() {
        var fired = 0
        val h = ConversationHistory(onChange = { fired++ })
        h.addLogEntry(entry("test"))
        assertEquals(1, fired)
    }

    @Test
    fun `shouldTriggerProfile fires every 3rd message`() {
        val h = ConversationHistory()
        assertFalse(h.shouldTriggerProfile())
        h.addLogEntry(entry("1"))
        assertFalse(h.shouldTriggerProfile())
        h.addLogEntry(entry("2"))
        assertFalse(h.shouldTriggerProfile())
        h.addLogEntry(entry("3"))
        assertTrue(h.shouldTriggerProfile())
        h.addLogEntry(entry("4"))
        assertFalse(h.shouldTriggerProfile())
        h.addLogEntry(entry("5"))
        assertFalse(h.shouldTriggerProfile())
        h.addLogEntry(entry("6"))
        assertTrue(h.shouldTriggerProfile())
    }

    @Test
    fun `getLastUserInputs returns last n inputs`() {
        val h = ConversationHistory()
        h.addLogEntry(entry("a"))
        h.addLogEntry(entry("b"))
        h.addLogEntry(entry("c"))
        h.addLogEntry(entry("d"))

        assertEquals(listOf("c", "d"), h.getLastUserInputs(2))
        assertEquals(listOf("b", "c", "d"), h.getLastUserInputs(3))
    }

    @Test
    fun `getMessagesToSummarize returns all except last 3`() {
        val h = ConversationHistory()
        repeat(5) { h.addLogEntry(entry("msg$it")) }

        val toSummarize = h.getMessagesToSummarize()
        assertEquals(2, toSummarize.size)
        assertEquals("msg0", toSummarize[0].userInput)
        assertEquals("msg1", toSummarize[1].userInput)
    }

    @Test
    fun `getMessagesToSummarize returns empty when 3 or fewer entries`() {
        val h = ConversationHistory()
        repeat(3) { h.addLogEntry(entry("msg$it")) }
        assertEquals(0, h.getMessagesToSummarize().size)
    }

    @Test
    fun `applySummary sets summary and removes old entries`() {
        val h = ConversationHistory()
        repeat(5) { h.addLogEntry(entry("msg$it")) }

        h.applySummary("Summary text", summarizedCount = 2)

        assertEquals("Summary text", h.summary)
        assertEquals(3, h.getHistory().size)
        assertEquals("msg2", h.getHistory()[0].userInput)
    }

    @Test
    fun `applySummary triggers onChange`() {
        var fired = 0
        val h = ConversationHistory(onChange = { fired++ })
        repeat(3) { h.addLogEntry(entry("x")) }
        fired = 0  // reset after addLogEntry calls
        h.applySummary("summary", 1)
        assertEquals(1, fired)
    }

    @Test
    fun `clear resets all state`() {
        val h = ConversationHistory()
        h.addLogEntry(entry("test"))
        h.applySummary("summary", 0)
        h.addFileToContext("file.kt", "content")

        h.clear()

        assertEquals(0, h.getHistory().size)
        assertEquals("", h.summary)
        assertEquals(0, h.userMessageCount)
        assertEquals(emptyList(), h.getFileContextPaths())
    }

    @Test
    fun `loadFrom restores state without triggering onChange`() {
        var fired = 0
        val h = ConversationHistory(onChange = { fired++ })
        val entries = listOf(entry("a"), entry("b"))
        h.loadFrom(entries, "old summary", userMessageCount = 5)

        assertEquals(2, h.getHistory().size)
        assertEquals("old summary", h.summary)
        assertEquals(5, h.userMessageCount)
        assertEquals(0, fired)
    }

    @Test
    fun `addFileToContext replaces same path`() {
        val h = ConversationHistory()
        h.addFileToContext("main.kt", "v1")
        h.addFileToContext("main.kt", "v2")

        assertEquals(1, h.getFileContextPaths().size)
        assertTrue(h.buildFileContextMessages().first().content.contains("v2"))
    }

    @Test
    fun `buildContextContent returns empty when no history or summary`() {
        val h = ConversationHistory()
        assertEquals("", h.buildContextContent())
    }

    @Test
    fun `buildContextContent includes summary and history`() {
        val h = ConversationHistory()
        h.loadFrom(listOf(entry("question", """{"content":"answer"}""")), "prev summary", 1)

        val content = h.buildContextContent()
        assertTrue(content.contains("prev summary"))
        assertTrue(content.contains("question"))
        assertTrue(content.contains("answer"))
    }

    @Test
    fun `parseResponse extracts content from JSON`() {
        val h = ConversationHistory()
        val (text, structured) = h.parseResponse("""{"content":"hello"}""")
        assertEquals("hello", text)
        assertEquals("hello", structured?.content)
    }

    @Test
    fun `parseResponse returns raw when not JSON`() {
        val h = ConversationHistory()
        val (text, structured) = h.parseResponse("plain text")
        assertEquals("plain text", text)
        assertEquals(null, structured)
    }
}
