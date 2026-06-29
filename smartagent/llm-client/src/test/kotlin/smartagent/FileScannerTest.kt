package smartagent

import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileScannerTest {

    private lateinit var root: File

    @Before
    fun setup() {
        root = Files.createTempDirectory("scanner-test").toFile()
    }

    @After
    fun teardown() {
        root.deleteRecursively()
    }

    @Test
    fun `listFiles returns all non-binary files sorted`() {
        File(root, "b.md").writeText("# Hello")
        File(root, "a.kt").writeText("fun main() {}")
        val files = FileScanner(root).listFiles()
        assertEquals(listOf("a.kt", "b.md"), files)
    }

    @Test
    fun `listFiles filters by pattern`() {
        File(root, "Main.kt").writeText("...")
        File(root, "README.md").writeText("...")
        assertEquals(listOf("Main.kt"), FileScanner(root).listFiles(".kt"))
    }

    @Test
    fun `listFiles excludes binary extensions`() {
        File(root, "image.png").writeBytes(byteArrayOf(0, 1, 2))
        File(root, "code.kt").writeText("val x = 1")
        assertEquals(listOf("code.kt"), FileScanner(root).listFiles())
    }

    @Test
    fun `listFiles excludes ignored dirs`() {
        File(root, "build").mkdirs()
        File(root, "build/output.kt").writeText("generated")
        File(root, "main.kt").writeText("real")
        assertEquals(listOf("main.kt"), FileScanner(root).listFiles())
    }

    @Test
    fun `readFile returns content for existing file`() {
        File(root, "hello.txt").writeText("hello world")
        assertEquals("hello world", FileScanner(root).readFile("hello.txt"))
    }

    @Test
    fun `readFile returns null for missing file`() {
        assertNull(FileScanner(root).readFile("nonexistent.txt"))
    }

    @Test
    fun `collectWithContent returns path-content pairs`() {
        File(root, "a.kt").writeText("val x = 1")
        val result = FileScanner(root).collectWithContent()
        assertEquals(1, result.size)
        assertEquals("a.kt", result[0].first)
        assertEquals("val x = 1", result[0].second)
    }

    @Test
    fun `collectWithContent skips binary files`() {
        File(root, "img.png").writeBytes(byteArrayOf(0, 1))
        File(root, "code.kt").writeText("code")
        val result = FileScanner(root).collectWithContent()
        assertEquals(1, result.size)
        assertEquals("code.kt", result[0].first)
    }

    @Test
    fun `collectWithContent recurses into subdirectories`() {
        File(root, "src").mkdirs()
        File(root, "src/Main.kt").writeText("content")
        val result = FileScanner(root).collectWithContent()
        assertEquals("src/Main.kt", result[0].first)
    }
}
