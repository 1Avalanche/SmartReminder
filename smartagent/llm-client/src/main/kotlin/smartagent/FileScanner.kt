package smartagent

import java.io.File

class FileScanner(
    val root: File,
    val ignoredDirs: Set<String> = DEFAULT_IGNORED_DIRS,
    val binaryExtensions: Set<String> = DEFAULT_BINARY_EXTENSIONS
) {
    companion object {
        val DEFAULT_IGNORED_DIRS = setOf(
            ".git", "build", ".gradle", "node_modules", ".idea",
            "__pycache__", "out", ".dart_tool", ".pub-cache", ".build",
            "DerivedData", ".swiftpm", "Pods"
        )
        val DEFAULT_BINARY_EXTENSIONS = setOf(
            "png", "jpg", "jpeg", "gif", "ico", "bmp", "webp",
            "pdf", "zip", "jar", "class", "apk", "ipa", "so", "dylib",
            "exe", "bin", "o", "a", "lib"
        )

        fun resolvePath(rawPath: String, repoRoot: File?): File? {
            val abs = File(rawPath)
            if (abs.isAbsolute && abs.exists()) return abs
            if (repoRoot != null) {
                val relative = File(repoRoot, rawPath)
                if (relative.exists()) return relative
            }
            val cwd = File(rawPath).canonicalFile
            if (cwd.exists()) return cwd
            return null
        }
    }

    fun listFiles(pattern: String? = null): List<String> =
        root.walkTopDown()
            .onEnter { it.name !in ignoredDirs }
            .filter { it.isFile && it.extension !in binaryExtensions }
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
            ?.filter { it.name !in ignoredDirs }
            ?.sortedWith(compareBy({ it.isFile }, { it.name }))
            ?: return
        entries.forEachIndexed { i, file ->
            val isLast = i == entries.lastIndex
            sb.appendLine("$prefix${if (isLast) "└── " else "├── "}${file.name}")
            if (file.isDirectory)
                buildTree(file, prefix + if (isLast) "    " else "│   ", depth + 1, maxDepth, sb)
        }
    }

    fun collectWithContent(): List<Pair<String, String>> {
        if (root.isFile) {
            if (root.extension in binaryExtensions) return emptyList()
            val content = runCatching { root.readText() }.getOrNull() ?: return emptyList()
            return listOf(Pair(root.name, content))
        }
        return root.walkTopDown()
            .onEnter { it.name !in ignoredDirs }
            .filter { it.isFile && it.extension !in binaryExtensions }
            .sortedBy { it.relativeTo(root).path }
            .mapNotNull { file ->
                val content = runCatching { file.readText() }.getOrNull() ?: return@mapNotNull null
                Pair(file.relativeTo(root).path, content)
            }
            .toList()
    }
}
