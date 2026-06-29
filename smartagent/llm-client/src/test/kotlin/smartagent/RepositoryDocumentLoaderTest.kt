package smartagent

import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RepositoryDocumentLoaderTest {

    private lateinit var root: File

    @Before
    fun setup() {
        root = Files.createTempDirectory("loader-test").toFile()
    }

    @After
    fun teardown() {
        root.deleteRecursively()
    }

    private fun loader() = RepositoryDocumentLoader(FileScanner(root))

    @Test
    fun `load returns one document per file`() {
        File(root, "a.kt").writeText("val a = 1")
        File(root, "b.kt").writeText("val b = 2")
        assertEquals(2, loader().load().size)
    }

    @Test
    fun `document content matches file content`() {
        File(root, "hello.txt").writeText("hello world")
        assertEquals("hello world", loader().load().single().content)
    }

    @Test
    fun `document title is filename`() {
        File(root, "MyFile.kt").writeText("content")
        assertEquals("MyFile.kt", loader().load().single().title)
    }

    @Test
    fun `document id is relative path`() {
        File(root, "file.kt").writeText("content")
        assertEquals("file.kt", loader().load().single().id)
    }

    @Test
    fun `document source is relative path`() {
        File(root, "src").mkdirs()
        File(root, "src/Main.kt").writeText("content")
        assertEquals("src/Main.kt", loader().load().single().metadata.source)
    }

    @Test
    fun `document extension extracted correctly`() {
        File(root, "script.py").writeText("print('hi')")
        assertEquals("py", loader().load().single().metadata.extension)
    }

    @Test
    fun `file without extension has null extension`() {
        File(root, "Makefile").writeText("all:")
        assertNull(loader().load().single().metadata.extension)
    }

    @Test
    fun `binary files are excluded`() {
        File(root, "image.png").writeBytes(byteArrayOf(0, 1))
        File(root, "code.kt").writeText("val x = 1")
        assertEquals(1, loader().load().size)
    }

    @Test
    fun `files in ignored dirs are excluded`() {
        File(root, "build").mkdirs()
        File(root, "build/generated.kt").writeText("generated")
        File(root, "main.kt").writeText("real")
        assertEquals(1, loader().load().size)
    }
}
