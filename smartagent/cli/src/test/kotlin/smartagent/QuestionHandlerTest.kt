package smartagent

import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QuestionHandlerTest {

    private lateinit var session: ChatSession
    private lateinit var handler: QuestionHandler

    @Before
    fun setup() {
        session = ChatSession()
        handler = QuestionHandler(session, ChatClient(session))
        session.clearProfile()
    }

    @After
    fun teardown() {
        session.clearProfile()
    }

    @Test
    fun `loadSystemPrompt returns non-empty prompt`() {
        val prompt = handler.loadSystemPrompt()
        assertTrue(prompt.isNotBlank())
        assertTrue("Источник:" in prompt || "Ты" in prompt)
    }

    @Test
    fun `resultsCount returns 1 for single line`() {
        assertEquals(1, handler.resultsCount("just one line"))
    }

    @Test
    fun `resultsCount returns correct count for multiple lines`() {
        val block = "line1\nline2\nline3"
        assertEquals(3, handler.resultsCount(block))
    }

    @Test
    fun `resultsCount returns 0 for empty string`() {
        assertEquals(1, handler.resultsCount(""))
    }

    @Test
    fun `formatChunkLine formats with id title and content`() {
        val chunk = Chunk(
            id = "chunk-1",
            content = "This is the chunk content",
            documentId = "README.md",
            index = 5,
            metadata = ChunkMetadata(
                documentTitle = "README",
                documentSource = "README.md",
                extension = "md"
            )
        )
        val line = handler.formatChunkLine(chunk)
        assertEquals(
            "[id: README.md] [title: README] [README.md_5]: \"This is the chunk content\"",
            line
        )
    }

    @Test
    fun `formatContextBlock joins multiple chunks`() {
        val chunks = listOf(
            Chunk(
                id = "c1", content = "first chunk", documentId = "a.md", index = 0,
                metadata = ChunkMetadata("A", "a.md", "md")
            ),
            Chunk(
                id = "c2", content = "second chunk", documentId = "b.md", index = 1,
                metadata = ChunkMetadata("B", "b.md", "md")
            )
        )
        val results = chunks.map { SearchResult(it, 0.95f) }
        val block = handler.formatContextBlock(results)
        val expected = "[id: a.md] [title: A] [a.md_0]: \"first chunk\"\n" +
                "[id: b.md] [title: B] [b.md_1]: \"second chunk\""
        assertEquals(expected, block)
    }

    @Test
    fun `formatContextBlock returns empty string for empty results`() {
        assertEquals("", handler.formatContextBlock(emptyList()))
    }
}
