package smartagent

import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertFalse
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

    private fun makeRanked(
        source: String = "docs/doc.md",
        title: String = "doc.md",
        extension: String? = "md",
        sectionPath: List<String> = emptyList(),
        chunkIndex: Int = 0,
        content: String = "chunk content",
        score: Double? = 0.75
    ) = RankedChunk(
        chunk = Chunk(
            id = "doc_$chunkIndex",
            content = content,
            documentId = "doc.md",
            chunkIndex = chunkIndex,
            metadata = ChunkMetadata(
                documentTitle = title,
                documentSource = source,
                extension = extension,
                sectionPath = sectionPath,
                chunkIndex = chunkIndex
            )
        ),
        score = score
    )

    @Test
    fun `formatChunkBlock includes all metadata fields`() {
        val ranked = makeRanked(
            source = "docs/README.md",
            title = "README.md",
            extension = "md",
            sectionPath = listOf("API", "Auth"),
            chunkIndex = 3,
            content = "Some content",
            score = 0.9876
        )
        val block = handler.formatChunkBlock(ranked)
        assertTrue("file=\"docs/README.md\"" in block)
        assertTrue("title=\"README.md\"" in block)
        assertTrue("ext=\"md\"" in block)
        assertTrue("section=\"API > Auth\"" in block)
        assertTrue("index=\"3\"" in block)
        assertTrue("<score>0.9876</score>" in block)
        assertTrue("Some content" in block)
    }

    @Test
    fun `formatChunkBlock with null score shows n_a`() {
        val ranked = makeRanked(score = null)
        val block = handler.formatChunkBlock(ranked)
        assertTrue("<score>n/a</score>" in block)
    }

    @Test
    fun `formatChunkBlock with sectionPath shows section attribute`() {
        val ranked = makeRanked(sectionPath = listOf("Root", "Child"))
        val block = handler.formatChunkBlock(ranked)
        assertTrue("section=\"Root > Child\"" in block)
    }

    @Test
    fun `formatChunkBlock with empty sectionPath omits section attribute`() {
        val ranked = makeRanked(sectionPath = emptyList())
        val block = handler.formatChunkBlock(ranked)
        assertFalse("section=" in block)
    }
}
