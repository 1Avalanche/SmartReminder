package smartagent

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

class FileScanner(
    val root: File,
    val ignoredDirs: Set<String> = DEFAULT_IGNORED_DIRS,
    val binaryExtensions: Set<String> = DEFAULT_BINARY_EXTENSIONS,
    val allowedTextExtensions: Set<String> = DEFAULT_ALLOWED_TEXT_EXTENSIONS,
    val allowedTextFilenames: Set<String> = DEFAULT_ALLOWED_TEXT_FILENAMES
) {
    companion object {
        val DEFAULT_IGNORED_DIRS = setOf(
            ".git", "build", ".gradle", "node_modules", ".idea",
            "__pycache__", "out", ".dart_tool", ".pub-cache", ".build",
            "DerivedData", ".swiftpm", "Pods", ".indexed"
        )
        val DEFAULT_BINARY_EXTENSIONS = setOf(
            "png", "jpg", "jpeg", "gif", "ico", "bmp", "webp",
            "pdf", "zip", "jar", "class", "apk", "ipa", "so", "dylib",
            "exe", "bin", "o", "a", "lib"
        )
        val DEFAULT_ALLOWED_TEXT_EXTENSIONS = setOf(
            "kt", "kts", "java", "py", "js", "ts", "jsx", "tsx",
            "go", "rs", "cpp", "c", "cs", "swift", "scala", "rb",
            "sh", "bash", "zsh", "dart",
            "h", "hpp", "php", "pl", "pm", "lua", "r",
            "json", "xml", "yaml", "yml", "toml", "csv", "tsv",
            "ini", "cfg", "conf", "properties", "env",
            "md", "markdown", "txt", "html", "htm", "css", "scss", "sass", "less",
            "rst", "adoc", "tex",
            "gradle", "cmake",
            "sql", "ps1", "bat", "cmd",
            "log"
        )
        val DEFAULT_ALLOWED_TEXT_FILENAMES = setOf(
            "makefile", "dockerfile", "vagrantfile",
            "gradle.properties", "local.properties",
            "proguard-rules.pro"
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
            .filter { it.isFile && isAllowedTextFile(it) }
            .map { it.relativeTo(root).path }
            .filter { pattern == null || it.contains(pattern, ignoreCase = true) }
            .sorted()
            .toList()

    fun readFile(relativePath: String): String? {
        val file = File(root, relativePath)
        return if (file.exists() && file.isFile) safeReadTextFile(file) else null
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
            val content = safeReadTextFile(root) ?: return emptyList()
            return listOf(Pair(root.name, content))
        }
        return root.walkTopDown()
            .onEnter { it.name !in ignoredDirs }
            .filter { it.isFile && isAllowedTextFile(it) }
            .sortedBy { it.relativeTo(root).path }
            .mapNotNull { file ->
                val content = safeReadTextFile(file) ?: return@mapNotNull null
                Pair(file.relativeTo(root).path, content)
            }
            .toList()
    }

    private fun isAllowedTextFile(file: File): Boolean {
        val ext = file.extension.lowercase()
        val name = file.name.lowercase()
        return ext in allowedTextExtensions || name in allowedTextFilenames
    }

    private fun safeReadTextFile(file: File): String? {
        val ext = file.extension.lowercase()
        if (ext in binaryExtensions) {
            println("[FileScanner] skipping blacklisted extension: ${file.path}")
            return null
        }

        val bytes = runCatching { file.readBytes() }
            .onFailure { e -> System.err.println("[FileScanner] error reading ${file.path}: ${e.message}") }
            .getOrNull() ?: return null

        if (bytes.contains(0.toByte())) {
            println("[FileScanner] skipping binary file (null byte detected): ${file.path}")
            return null
        }

        return runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.onFailure { e -> System.err.println("[FileScanner] error decoding ${file.path}: ${e.message}") }
            .getOrNull()
    }
}
