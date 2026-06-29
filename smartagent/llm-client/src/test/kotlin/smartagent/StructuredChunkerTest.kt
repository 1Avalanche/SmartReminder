package smartagent

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StructuredChunkerTest {

    private val chunker = StructuredChunker()

    private fun doc(content: String) = Document(
        id = "doc.md",
        title = "doc.md",
        content = content,
        metadata = DocumentMetadata(source = "docs/doc.md", extension = "md")
    )

    @Test
    fun `splits by h1 headers`() {
        val chunks = chunker.chunk(listOf(doc("# First\nText one.\n\n# Second\nText two.")))
        assertEquals(2, chunks.size)
        assertEquals(listOf("First"), chunks[0].metadata.sectionPath)
        assertEquals(listOf("Second"), chunks[1].metadata.sectionPath)
    }

    @Test
    fun `chunk content does not include header line`() {
        val chunks = chunker.chunk(listOf(doc("# Title\nBody text.")))
        assertEquals("Body text.", chunks[0].content)
    }

    @Test
    fun `nested headers produce hierarchical sectionPath`() {
        val content = "# API\nOverview.\n\n## Auth\nAuth details.\n\n### JWT\nJWT details."
        val chunks = chunker.chunk(listOf(doc(content)))
        assertEquals(3, chunks.size)
        assertEquals(listOf("API"), chunks[0].metadata.sectionPath)
        assertEquals(listOf("API", "Auth"), chunks[1].metadata.sectionPath)
        assertEquals(listOf("API", "Auth", "JWT"), chunks[2].metadata.sectionPath)
    }

    @Test
    fun `sibling h2 resets h3 in sectionPath`() {
        val content = "# API\n\n## Auth\nauth.\n\n### JWT\njwt.\n\n## OAuth\noauth."
        val chunks = chunker.chunk(listOf(doc(content)))
        assertEquals(listOf("API", "OAuth"), chunks.last().metadata.sectionPath)
    }

    @Test
    fun `parent chunk does not contain child section text`() {
        val content = "# Parent\nParent text.\n\n## Child\nChild text."
        val chunks = chunker.chunk(listOf(doc(content)))
        val parent = chunks.first { it.metadata.sectionPath == listOf("Parent") }
        assertTrue("Child text" !in parent.content)
        assertTrue("Parent text" in parent.content)
    }

    @Test
    fun `fallback splits by paragraphs when no headers`() {
        val chunks = chunker.chunk(listOf(doc("First paragraph.\n\nSecond paragraph.\n\nThird.")))
        assertEquals(3, chunks.size)
        assertEquals("First paragraph.", chunks[0].content)
        assertEquals("Second paragraph.", chunks[1].content)
        assertEquals("Third.", chunks[2].content)
    }

    @Test
    fun `fallback sectionPath is empty`() {
        val chunks = chunker.chunk(listOf(doc("No headers.\n\nJust paragraphs.")))
        assertTrue(chunks.all { it.metadata.sectionPath.isEmpty() })
    }

    @Test
    fun `text before first header becomes preamble chunk with empty sectionPath`() {
        val chunks = chunker.chunk(listOf(doc("Preamble.\n\n# Section\nSection text.")))
        assertEquals(2, chunks.size)
        assertTrue(chunks[0].metadata.sectionPath.isEmpty())
        assertTrue("Preamble" in chunks[0].content)
    }

    @Test
    fun `empty sections are skipped`() {
        val chunks = chunker.chunk(listOf(doc("# A\n\n# B\ntext b.")))
        assertEquals(1, chunks.size)
        assertEquals(listOf("B"), chunks[0].metadata.sectionPath)
    }

    @Test
    fun `chunk ids are unique and sequential`() {
        val chunks = chunker.chunk(listOf(doc("# A\ntext a.\n\n# B\ntext b.")))
        assertEquals("doc.md_0", chunks[0].id)
        assertEquals("doc.md_1", chunks[1].id)
    }

    @Test
    fun `supports h4 h5 h6 headers`() {
        val content = "# H1\ntext1.\n\n#### H4\ntext4.\n\n###### H6\ntext6."
        val chunks = chunker.chunk(listOf(doc(content)))
        assertEquals(3, chunks.size)
        assertEquals(listOf("H1"), chunks[0].metadata.sectionPath)
        assertEquals(listOf("H1", "H4"), chunks[1].metadata.sectionPath)
        assertEquals(listOf("H1", "H4", "H6"), chunks[2].metadata.sectionPath)
    }

    @Test
    fun `going shallower then deeper does not leak stale hierarchy`() {
        // h4 sets hierarchy[3]="Deep", then h2 must clear it
        // so h5 under h2 should NOT include "Deep" in path
        val content = "# Root\nroot.\n\n#### Deep\ndeep.\n\n## Sibling\nsibling.\n\n##### Other\nother."
        val chunks = chunker.chunk(listOf(doc(content)))
        val last = chunks.last()
        assertEquals(listOf("Root", "Sibling", "Other"), last.metadata.sectionPath)
    }

    @Test
    fun `metadata carries document info`() {
        val chunks = chunker.chunk(listOf(doc("# Section\ntext.")))
        assertEquals("doc.md", chunks[0].metadata.documentTitle)
        assertEquals("docs/doc.md", chunks[0].metadata.documentSource)
        assertEquals("md", chunks[0].metadata.extension)
    }
}
