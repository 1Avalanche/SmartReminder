package smartagent.investigator

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

class McpBinaryDownloader(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(120, TimeUnit.SECONDS)
        .build(),
    private val apiBaseUrl: String = "https://api.github.com",
    private val storageDir: File = File(System.getProperty("user.home"), ".config/smartagent")
) {
    private val json = Json { ignoreUnknownKeys = true }
    val binaryFile: File get() = File(storageDir, "github-mcp-server.exe")

    fun ensureBinary(onProgress: (String) -> Unit = {}): File {
        if (binaryFile.exists()) return binaryFile
        storageDir.mkdirs()
        val downloadUrl = resolveWindowsAssetUrl()
        onProgress("Скачиваю GitHub MCP Server binary (~30 МБ)...")
        downloadAndExtract(downloadUrl)
        onProgress("Binary готов: ${binaryFile.absolutePath}")
        return binaryFile
    }

    internal fun resolveWindowsAssetUrl(): String {
        val request = Request.Builder()
            .url("$apiBaseUrl/repos/github/github-mcp-server/releases/latest")
            .header("User-Agent", "smartagent-investigator")
            .header("Accept", "application/vnd.github+json")
            .build()
        val body = httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("GitHub API error: ${resp.code}")
            resp.body?.string() ?: error("empty body from GitHub API")
        }
        val assets = json.parseToJsonElement(body).jsonObject["assets"]?.jsonArray
            ?: error("no assets field in GitHub release response")
        val asset = assets.map { it.jsonObject }.firstOrNull { obj ->
            val name = obj["name"]?.jsonPrimitive?.content ?: ""
            name.contains("Windows", ignoreCase = true) &&
                (name.contains("x86_64") || name.contains("amd64")) &&
                name.endsWith(".zip")
        } ?: error(
            "No Windows x86_64/amd64 zip asset found. Available: ${
                assets.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }
            }"
        )
        return asset["browser_download_url"]?.jsonPrimitive?.content
            ?: error("no browser_download_url in asset")
    }

    private fun downloadAndExtract(url: String) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "smartagent-investigator")
            .build()
        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("Download failed: ${resp.code} from $url")
            val body = resp.body ?: error("empty download response")
            ZipInputStream(body.byteStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith("github-mcp-server.exe")) {
                        binaryFile.outputStream().use { out -> zip.copyTo(out) }
                        binaryFile.setExecutable(true)
                        return
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            error("github-mcp-server.exe not found inside downloaded zip from $url")
        }
    }
}
