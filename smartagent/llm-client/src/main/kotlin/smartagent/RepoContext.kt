package smartagent

import java.io.File

class RepoContext(val root: File) {
    private val scanner = FileScanner(root)

    fun listFiles(pattern: String? = null) = scanner.listFiles(pattern)
    fun readFile(relativePath: String) = scanner.readFile(relativePath)
    fun fileTree(maxDepth: Int = 3) = scanner.fileTree(maxDepth)
}
