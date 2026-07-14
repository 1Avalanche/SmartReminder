package smartagent.doc

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocumentWalkerTest {

    private fun walker(responses: Map<String, String>) = DocumentWalker(
        owner = "org",
        repo = "repo",
        branch = "main",
        fetchContent = { path -> responses[path] }
    )

    @Test
    fun `single file path returns one document`() {
        val w = walker(mapOf("README.md" to "# Hello"))
        val docs = w.walk(listOf("README.md"))
        assertEquals(1, docs.size)
        assertEquals("README.md", docs[0].title)
        assertEquals("# Hello", docs[0].content)
    }

    @Test
    fun `directory listing expands into doc files`() {
        val dirJson = """[
            {"name":"guide.md","path":"docs/guide.md","type":"file"},
            {"name":"other.png","path":"docs/other.png","type":"file"},
            {"name":"sub","path":"docs/sub","type":"dir"}
        ]"""
        val w = walker(mapOf(
            "docs" to dirJson,
            "docs/guide.md" to "Guide content"
        ))
        val docs = w.walk(listOf("docs"))
        assertEquals(1, docs.size)
        assertEquals("docs/guide.md", docs[0].title)
    }

    @Test
    fun `directory filters non-doc extensions`() {
        val dirJson = """[
            {"name":"image.png","path":"img/image.png","type":"file"},
            {"name":"notes.txt","path":"img/notes.txt","type":"file"}
        ]"""
        val w = walker(mapOf("img" to dirJson, "img/notes.txt" to "text"))
        val docs = w.walk(listOf("img"))
        assertEquals(1, docs.size)
        assertEquals("img/notes.txt", docs[0].title)
    }

    @Test
    fun `null fetch result skips path silently`() {
        val w = walker(emptyMap())
        val docs = w.walk(listOf("missing.md"))
        assertTrue(docs.isEmpty())
    }

    @Test
    fun `directory entries with type dir are recursed into`() {
        val subJson = """[
            {"name":"nested.md","path":"docs/sub/nested.md","type":"file"}
        ]"""
        val dirJson = """[
            {"name":"guide.md","path":"docs/guide.md","type":"file"},
            {"name":"sub","path":"docs/sub","type":"dir"}
        ]"""
        val w = walker(mapOf(
            "docs" to dirJson,
            "docs/guide.md" to "Guide content",
            "docs/sub" to subJson,
            "docs/sub/nested.md" to "Nested content"
        ))
        val docs = w.walk(listOf("docs"))
        assertEquals(2, docs.size)
        assertTrue(docs.any { it.title == "docs/guide.md" })
        assertTrue(docs.any { it.title == "docs/sub/nested.md" })
    }

    @Test
    fun `multiple start paths accumulate all documents`() {
        val w = walker(mapOf(
            "README.md" to "readme",
            "CHANGELOG.md" to "changelog"
        ))
        val docs = w.walk(listOf("README.md", "CHANGELOG.md"))
        assertEquals(2, docs.size)
    }

    @Test
    fun `document id and source are set from owner-repo-branch`() {
        val w = DocumentWalker("myorg", "myrepo", "feat", { "content" })
        val docs = w.walk(listOf("file.md"))
        assertEquals("file:myorg/myrepo/file.md", docs[0].id)
        assertEquals("github:myorg/myrepo/file.md", docs[0].metadata.source)
    }

    @Test
    fun `malformed directory json skips gracefully`() {
        val w = walker(mapOf("baddir" to "[not valid json"))
        val docs = w.walk(listOf("baddir"))
        assertTrue(docs.isEmpty())
    }

    @Test
    fun `non-doc path without extension does not create document`() {
        val w = walker(mapOf("somedirectory" to "error: not a file"))
        val docs = w.walk(listOf("somedirectory"))
        assertTrue(docs.isEmpty())
    }

    @Test
    fun `self-referential dir entry does not cause infinite loop`() {
        val dirJson = """[
            {"name":"docs","path":"docs","type":"dir"},
            {"name":"guide.md","path":"docs/guide.md","type":"file"}
        ]"""
        val w = walker(mapOf(
            "docs" to dirJson,
            "docs/guide.md" to "Guide content"
        ))
        val docs = w.walk(listOf("docs"))
        assertEquals(1, docs.size)
        assertEquals("docs/guide.md", docs[0].title)
    }

    @Test
    fun `duplicate paths in multiple listings are visited once`() {
        val dir1Json = """[{"name":"shared.md","path":"shared.md","type":"file"}]"""
        val dir2Json = """[{"name":"shared.md","path":"shared.md","type":"file"}]"""
        var fetchCount = 0
        val w = DocumentWalker("o", "r", "b") { path ->
            if (path == "shared.md") { fetchCount++; "content" }
            else if (path == "dir1") dir1Json
            else if (path == "dir2") dir2Json
            else null
        }
        val docs = w.walk(listOf("dir1", "dir2"))
        assertEquals(1, docs.size)
        assertEquals(1, fetchCount)
    }
}
