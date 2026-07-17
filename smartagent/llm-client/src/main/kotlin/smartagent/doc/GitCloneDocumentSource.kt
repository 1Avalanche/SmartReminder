package smartagent.doc

import smartagent.Config
import smartagent.Document
import smartagent.DocumentMetadata
import java.io.File
import java.nio.file.Files

class GitCloneDocumentSource(
    override val owner: String,
    override val repo: String,
    override val branch: String,
    override val docPaths: List<String>
) : DocumentSource {

    private val docExtensions = setOf("md", "mdx", "txt", "rst", "adoc")
    private val skipDirs = setOf("build", ".gradle", ".git", "node_modules", ".idea", ".cxx", "generated")

    override fun loadDocuments(): List<Document> {
        val tempDir = Files.createTempDirectory("docindex-$owner-$repo").toFile()
        println("[DocIndex] Cloning $owner/$repo@$branch into ${tempDir.absolutePath} ...")
        return try {
            clone(tempDir)
            val docs = walkDocs(tempDir).toMutableList()
            val commits = readCommits(tempDir)
            if (commits.isNotBlank()) {
                docs.add(
                    Document(
                        id = "commits:$owner/$repo@$branch",
                        title = "Recent commits on $branch",
                        content = commits,
                        metadata = DocumentMetadata(null, "github:$owner/$repo@$branch/commits")
                    )
                )
            }
            println("[DocIndex] Found ${docs.size} docs (${docs.size - 1} files + commits)")
            docs
        } finally {
            tempDir.deleteRecursively()
            println("[DocIndex] Cleaned up temp dir")
        }
    }

    private fun clone(dir: File) {
        val token = Config.localProperties["GITHUB_CORP_TOKEN"] ?: System.getenv("GITHUB_CORP_TOKEN")
        val host = Config.localProperties["GITHUB_CORP_HOST"] ?: System.getenv("GITHUB_CORP_HOST") ?: "github.com"
        val url = if (token != null) {
            "https://$token@$host/$owner/$repo.git"
        } else {
            "https://$host/$owner/$repo.git"
        }
        val process = ProcessBuilder("git", "clone", "--depth", "1", "--branch", branch, url, dir.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) throw RuntimeException("git clone failed (exit $exitCode): $output")
    }

    private fun walkDocs(root: File): List<Document> {
        return root.walkTopDown()
            .onEnter { dir -> dir.name !in skipDirs }
            .filter { it.isFile && it.extension.lowercase() in docExtensions }
            .map { file ->
                val relPath = file.relativeTo(root).path
                DocScanLogger.logDoc(relPath)
                Document(
                    id = "file:$owner/$repo/$relPath",
                    title = relPath,
                    content = file.readText(),
                    metadata = DocumentMetadata(
                        extension = file.extension,
                        source = "github:$owner/$repo/$relPath"
                    )
                )
            }.toList()
    }

    private fun readCommits(dir: File): String {
        return runCatching {
            val process = ProcessBuilder("git", "-C", dir.absolutePath, "log", "--oneline", "-20")
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().readText().also { process.waitFor() }
        }.getOrElse { "" }
    }
}
