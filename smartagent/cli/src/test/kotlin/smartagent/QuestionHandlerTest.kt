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

}
