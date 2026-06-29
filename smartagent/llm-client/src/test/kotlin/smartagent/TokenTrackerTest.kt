package smartagent

import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TokenTrackerTest {

    private lateinit var tmpDir: File
    private lateinit var tokensFile: File

    @Before
    fun setup() {
        tmpDir = createTempDirectory("smartagent-test").toFile()
        tokensFile = File(tmpDir, "tokens.json")
    }

    @After
    fun teardown() {
        tmpDir.deleteRecursively()
    }

    @Test
    fun `starts with empty state`() {
        val tracker = TokenTracker(tokensFile)
        assertEquals(0, tracker.lastPromptTokens)
        assertEquals(0, tracker.getTokenEntries().size)
    }

    @Test
    fun `addTokenEntry stores entry and persists to file`() {
        val tracker = TokenTracker(tokensFile)
        tracker.addTokenEntry(Usage(prompt_tokens = 100, completion_tokens = 50, total_tokens = 150))

        assertEquals(1, tracker.getTokenEntries().size)
        val entry = tracker.getTokenEntries().first()
        assertEquals(100, entry.prompt)
        assertEquals(50, entry.completion)
        assertEquals(150, entry.total)
        assertTrue(tokensFile.exists())
    }

    @Test
    fun `loads existing entries from file on init`() {
        val tracker1 = TokenTracker(tokensFile)
        tracker1.addTokenEntry(Usage(100, 50, 150))
        tracker1.addTokenEntry(Usage(200, 80, 280))

        val tracker2 = TokenTracker(tokensFile)
        assertEquals(2, tracker2.getTokenEntries().size)
    }

    @Test
    fun `updateLastPromptTokens updates field`() {
        val tracker = TokenTracker(tokensFile)
        tracker.updateLastPromptTokens(500)
        assertEquals(500, tracker.lastPromptTokens)
    }

    @Test
    fun `shouldCompress false when below threshold`() {
        val tracker = TokenTracker(tokensFile)
        tracker.updateLastPromptTokens(100)
        // contextWindow=128000, threshold=5120 (4%), 100 < 5120
        assertFalse(tracker.shouldCompress(contextWindow = 128000))
    }

    @Test
    fun `shouldCompress true when above threshold`() {
        val tracker = TokenTracker(tokensFile)
        tracker.updateLastPromptTokens(6000)
        // contextWindow=128000, threshold=5120, 6000 >= 5120
        assertTrue(tracker.shouldCompress(contextWindow = 128000))
    }

    @Test
    fun `shouldCompress uses estimatedChars when higher than lastPromptTokens`() {
        val tracker = TokenTracker(tokensFile)
        tracker.updateLastPromptTokens(10)
        // estimatedChars/4 = 24000 chars / 4 = 6000 tokens, > threshold of 5120
        assertTrue(tracker.shouldCompress(estimatedChars = 24000, contextWindow = 128000))
    }

    @Test
    fun `clear resets state and writes empty file`() {
        val tracker = TokenTracker(tokensFile)
        tracker.addTokenEntry(Usage(100, 50, 150))
        tracker.updateLastPromptTokens(100)

        tracker.clear()

        assertEquals(0, tracker.getTokenEntries().size)
        assertEquals(0, tracker.lastPromptTokens)
        assertEquals("[]", tokensFile.readText())
    }

    @Test
    fun `request numbers increment sequentially`() {
        val tracker = TokenTracker(tokensFile)
        tracker.addTokenEntry(Usage(10, 5, 15))
        tracker.addTokenEntry(Usage(20, 10, 30))
        tracker.addTokenEntry(Usage(30, 15, 45))

        val entries = tracker.getTokenEntries()
        assertEquals(1, entries[0].request)
        assertEquals(2, entries[1].request)
        assertEquals(3, entries[2].request)
    }
}
