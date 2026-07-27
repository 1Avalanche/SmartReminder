package smartagent.investigator

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class McpBinaryDownloaderTest {

    @get:Rule val tmpFolder = TemporaryFolder()

    private val mockServer = MockWebServer()

    @Before fun setUp() { mockServer.start() }
    @After fun tearDown() { mockServer.shutdown() }

    private fun makeDownloader() = McpBinaryDownloader(
        httpClient = OkHttpClient(),
        apiBaseUrl = mockServer.url("").toString().trimEnd('/'),
        storageDir = tmpFolder.newFolder("smartagent")
    )

    @Test
    fun `ensureBinary returns existing file without network call`() {
        val downloader = makeDownloader()
        downloader.binaryFile.also { it.parentFile.mkdirs(); it.writeText("existing") }
        // no mock enqueued — any request would throw
        val result = downloader.ensureBinary()
        assertEquals(downloader.binaryFile, result)
        assertEquals("existing", result.readText())
    }

    @Test
    fun `resolveWindowsAssetUrl picks Windows x86_64 zip`() {
        mockServer.enqueue(MockResponse().setBody("""
            {
              "tag_name": "v1.6.0",
              "assets": [
                {"name": "github-mcp-server_Linux_x86_64.tar.gz", "browser_download_url": "https://example.com/linux.tar.gz"},
                {"name": "github-mcp-server_Windows_x86_64.zip", "browser_download_url": "https://example.com/windows.zip"},
                {"name": "github-mcp-server_Darwin_arm64.tar.gz", "browser_download_url": "https://example.com/darwin.tar.gz"}
              ]
            }
        """.trimIndent()))
        val url = makeDownloader().resolveWindowsAssetUrl()
        assertEquals("https://example.com/windows.zip", url)
    }

    @Test
    fun `resolveWindowsAssetUrl picks amd64 variant when x86_64 absent`() {
        mockServer.enqueue(MockResponse().setBody("""
            {
              "tag_name": "v1.7.0",
              "assets": [
                {"name": "github-mcp-server_Windows_amd64.zip", "browser_download_url": "https://example.com/win-amd64.zip"}
              ]
            }
        """.trimIndent()))
        val url = makeDownloader().resolveWindowsAssetUrl()
        assertEquals("https://example.com/win-amd64.zip", url)
    }

    @Test
    fun `resolveWindowsAssetUrl fails when no Windows asset present`() {
        mockServer.enqueue(MockResponse().setBody("""
            {"tag_name": "v1.6.0", "assets": [
              {"name": "github-mcp-server_Linux_x86_64.tar.gz", "browser_download_url": "https://example.com/linux.tar.gz"}
            ]}
        """.trimIndent()))
        assertFailsWith<IllegalStateException> { makeDownloader().resolveWindowsAssetUrl() }
    }

    @Test
    fun `ensureBinary downloads and extracts exe from zip`() {
        val zipBytes = buildZip("github-mcp-server.exe" to "fake-binary-content".toByteArray())
        val downloadPath = "/download/windows.zip"

        mockServer.enqueue(MockResponse().setBody("""
            {"tag_name": "v1.6.0", "assets": [
              {"name": "github-mcp-server_Windows_x86_64.zip",
               "browser_download_url": "${mockServer.url(downloadPath)}"}
            ]}
        """.trimIndent()))
        mockServer.enqueue(
            MockResponse()
                .addHeader("Content-Type", "application/zip")
                .setBody(okio.Buffer().write(zipBytes))
        )

        val downloader = makeDownloader()
        val result = downloader.ensureBinary()

        assertTrue(result.exists())
        assertEquals("fake-binary-content", result.readText())
        assertEquals("github-mcp-server.exe", result.name)
    }

    @Test
    fun `ensureBinary fails when exe not found in zip`() {
        val zipBytes = buildZip("readme.txt" to "hello".toByteArray())

        mockServer.enqueue(MockResponse().setBody("""
            {"tag_name": "v1.6.0", "assets": [
              {"name": "github-mcp-server_Windows_x86_64.zip",
               "browser_download_url": "${mockServer.url("/download.zip")}"}
            ]}
        """.trimIndent()))
        mockServer.enqueue(
            MockResponse()
                .addHeader("Content-Type", "application/zip")
                .setBody(okio.Buffer().write(zipBytes))
        )

        assertFailsWith<IllegalStateException> { makeDownloader().ensureBinary() }
    }

    private fun buildZip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            entries.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(content)
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }
}
