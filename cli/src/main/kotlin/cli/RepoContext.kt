package cli

import java.io.File

internal class RepoContext(val root: File) {
    companion object {
        private val IGNORED_DIRS = setOf(
            ".git", "build", ".gradle", "node_modules", ".idea",
            "__pycache__", "out", ".dart_tool", ".pub-cache", ".build",
            "DerivedData", ".swiftpm", "Pods"
        )
        private val BINARY_EXTENSIONS = setOf(
            "png", "jpg", "jpeg", "gif", "ico", "bmp", "webp",
            "pdf", "zip", "jar", "class", "apk", "ipa", "so", "dylib",
            "exe", "bin", "o", "a", "lib"
        )
    }

    fun listFiles(pattern: String? = null): List<String> =
        root.walkTopDown()
            .onEnter { it.name !in IGNORED_DIRS }
            .filter { it.isFile && it.extension !in BINARY_EXTENSIONS }
            .map { it.relativeTo(root).path }
            .filter { pattern == null || it.contains(pattern, ignoreCase = true) }
            .sorted()
            .toList()

    fun readFile(relativePath: String): String? {
        val file = File(root, relativePath)
        return if (file.exists() && file.isFile) file.readText() else null
    }

    fun fileTree(maxDepth: Int = 3): String {
        val sb = StringBuilder()
        sb.appendLine(root.absolutePath)
        buildTree(root, "", 0, maxDepth, sb)
        return sb.toString().trimEnd()
    }

    private fun buildTree(dir: File, prefix: String, depth: Int, maxDepth: Int, sb: StringBuilder) {
        if (depth >= maxDepth) return
        val entries = dir.listFiles()
            ?.filter { it.name !in IGNORED_DIRS }
            ?.sortedWith(compareBy({ it.isFile }, { it.name }))
            ?: return
        entries.forEachIndexed { i, file ->
            val isLast = i == entries.lastIndex
            sb.appendLine("$prefix${if (isLast) "└── " else "├── "}${file.name}")
            if (file.isDirectory)
                buildTree(file, prefix + if (isLast) "    " else "│   ", depth + 1, maxDepth, sb)
        }
    }
}
