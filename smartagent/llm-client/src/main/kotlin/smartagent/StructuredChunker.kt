package smartagent

import kotlin.math.min

class StructuredChunker(
    private val normalizer: TextNormalizer = DefaultTextNormalizer(),
    private val maxChunkSize: Int = 1500,
    private val minChunkSize: Int = 200,
    private val overlapSize: Int = 100,
    private val preserveDeclarations: Boolean = true
) : Chunker {

    private val headerRegex = Regex("^(#{1,6})\\s+(.+)")

    private val declarationRegex = Regex(
        """^\s*(?:(?:private|public|internal|protected|override|suspend|abstract|open|sealed|data|inline|actual|const)\s+)*""" +
                """(?:fun|class|object|interface|enum\s+class|enum|data\s+class|sealed\s+class|annotation\s+class|import|package|def)\b"""
    )

    private val markdownExtensions = setOf("md", "markdown")
    private val codeExtensions = setOf(
        "kt", "kts", "java", "py", "js", "ts", "jsx", "tsx",
        "go", "rs", "cpp", "c", "cs", "swift", "scala", "rb", "sh"
    )

    override fun chunk(documents: List<Document>): List<Chunk> {
        return documents.flatMap { document -> chunkDocument(document) }
    }

    private fun chunkDocument(document: Document): List<Chunk> {
        val ext = document.metadata.extension
        val cleanContent = normalizer.normalize(document.content)
        val sections = when {
            ext in markdownExtensions -> extractMarkdownSections(cleanContent)
            ext in codeExtensions -> extractCodeSectionsOptimized(cleanContent)
            else -> extractParagraphSections(cleanContent)
        }

        val sizedSections = sections.flatMap { (path, text) ->
            if (text.length > maxChunkSize) {
                splitByFixedSize(text).map { path to it.second }
            } else {
                listOf(path to text)
            }
        }

        return sizedSections.mapIndexed { index, (path, text) ->
            Chunk(
                id = "${document.id}_$index",
                content = text,
                documentId = document.id,
                index = index,
                metadata = ChunkMetadata(
                    documentTitle = document.title,
                    documentSource = document.metadata.source,
                    extension = document.metadata.extension,
                    sectionPath = path
                )
            )
        }
    }

    private fun extractCodeSectionsOptimized(content: String): List<Pair<List<String>, String>> {
        val lines = content.lines()
        if (lines.isEmpty()) return emptyList()

        val sections = mutableListOf<Pair<List<String>, String>>()
        val currentChunk = StringBuilder()
        var currentDeclaration = ""
        val currentPath = mutableListOf<String>()
        var wasPreviousLineBlank = false
        var lineIndex = 0

        while (lineIndex < lines.size) {
            val line = lines[lineIndex]
            val trimmed = line.trim()

            if (trimmed.isEmpty() && currentChunk.isEmpty()) {
                lineIndex++
                continue
            }

            if (trimmed.isEmpty()) {
                wasPreviousLineBlank = true
                currentChunk.appendLine(line)
                lineIndex++
                continue
            }

            val isDeclaration = declarationRegex.containsMatchIn(line)
            val isImportOrPackage = trimmed.startsWith("import ") || trimmed.startsWith("package ")

            if (isDeclaration && !isImportOrPackage && currentChunk.isNotEmpty()) {
                val indentation = line.length - trimmed.length
                val isTopLevel = indentation == 0

                if (isTopLevel || wasPreviousLineBlank) {
                    if (currentChunk.length >= minChunkSize) {
                        sections.add(currentPath.toList() to currentChunk.toString().trim())
                    }

                    currentChunk.clear()
                    currentPath.clear()
                    currentDeclaration = line

                    val declName = extractDeclarationName(line)
                    if (declName.isNotBlank()) {
                        currentPath.add(declName)
                    }
                }
            }

            wasPreviousLineBlank = false
            currentChunk.appendLine(line)

            if (currentChunk.length > maxChunkSize) {
                val chunkText = currentChunk.toString()
                val splitPoint = findBestSplitPoint(chunkText, maxChunkSize)

                val part = chunkText.substring(0, splitPoint).trim()
                if (part.length >= minChunkSize) {
                    sections.add(currentPath.toList() to part)
                }

                val remaining = chunkText.substring(splitPoint)
                currentChunk.clear()

                if (preserveDeclarations && currentDeclaration.isNotBlank()) {
                    currentChunk.appendLine(currentDeclaration)
                    currentChunk.appendLine("// ... продолжение ...")
                }

                if (overlapSize > 0) {
                    val overlapStart = maxOf(splitPoint - overlapSize, 0)
                    val overlap = chunkText.substring(overlapStart, splitPoint).trimStart()
                    if (overlap.isNotBlank()) {
                        currentChunk.appendLine(overlap)
                    }
                }

                currentChunk.append(remaining.trimStart())
            }

            lineIndex++
        }

        if (currentChunk.isNotEmpty()) {
            val finalText = currentChunk.toString().trim()
            if (finalText.length >= minChunkSize) {
                sections.add(currentPath.toList() to finalText)
            } else if (sections.isNotEmpty() && finalText.isNotEmpty()) {
                val lastIndex = sections.lastIndex
                val (lastPath, lastText) = sections[lastIndex]
                sections[lastIndex] = lastPath to (lastText + "\n" + finalText)
            } else if (finalText.isNotEmpty()) {
                sections.add(currentPath.toList() to finalText)
            }
        }

        if (sections.isEmpty() && content.isNotBlank()) {
            return splitByFixedSize(content)
        }

        return sections
    }

    private fun findBestSplitPoint(text: String, maxSize: Int): Int {
        if (text.length <= maxSize) return text.length

        val searchStart = maxOf(0, maxSize - 100)
        val searchEnd = minOf(text.length, maxSize + 100)

        val patterns = listOf(
            "\n}\n",        // Конец блока
            "\n)",          // Конец вызова
            ";\n",          // Конец оператора
            ".\n",          // Конец предложения
            "\n\n",         // Пустая строка
            "\n",           // Любая новая строка
        )

        for (pattern in patterns) {
            val index = text.lastIndexOf(pattern, searchEnd)
            if (index > searchStart) {
                return index + pattern.length
            }
        }

        val lastSpace = text.lastIndexOf(' ', searchEnd)
        if (lastSpace > searchStart) {
            return lastSpace
        }

        return maxSize
    }

    private fun extractDeclarationName(line: String): String {
        val trimmed = line.trim()

        val namePatterns = listOf(
            Regex("""(?:class|interface|object|enum class|data class|sealed class)\s+(\w+)"""),
            Regex("""fun\s+(?:\w+\.)?(\w+)\s*\("""),
            Regex("""val\s+(\w+)"""),
            Regex("""var\s+(\w+)"""),
        )

        for (pattern in namePatterns) {
            val match = pattern.find(trimmed)
            if (match != null) {
                return match.groupValues[1]
            }
        }

        val genericPattern = Regex("""(?:def|class|function)\s+(\w+)""")
        val match = genericPattern.find(trimmed)
        return match?.groupValues?.get(1) ?: ""
    }

    private fun splitByFixedSize(content: String): List<Pair<List<String>, String>> {
        val chunks = mutableListOf<Pair<List<String>, String>>()
        var start = 0

        while (start < content.length) {
            val end = minOf(start + maxChunkSize, content.length)
            var chunk = content.substring(start, end)

            if (end < content.length) {
                val lastSpace = chunk.lastIndexOf(' ')
                if (lastSpace > minChunkSize) {
                    chunk = chunk.substring(0, lastSpace)
                }
            }

            chunks.add(emptyList<String>() to chunk.trim())
            val advance = maxOf(chunk.length - overlapSize, maxChunkSize / 4)
            start += advance
        }

        return chunks
    }

    private fun extractMarkdownSections(content: String): List<Pair<List<String>, String>> {
        val lines = content.lines()
        if (lines.none { headerRegex.containsMatchIn(it) }) {
            return extractParagraphSections(content)
        }

        val sections = mutableListOf<Pair<List<String>, String>>()
        val hierarchy = arrayOfNulls<String>(6)
        var currentPath = emptyList<String>()
        val currentContent = StringBuilder()

        fun flush() {
            val trimmed = currentContent.toString().trim()
            if (trimmed.isNotEmpty()) sections.add(currentPath to trimmed)
            currentContent.clear()
        }

        for (line in lines) {
            val match = headerRegex.find(line)
            if (match != null) {
                flush()
                val level = match.groupValues[1].length
                hierarchy[level - 1] = match.groupValues[2].trim()
                for (i in level until hierarchy.size) hierarchy[i] = null
                currentPath = hierarchy.take(level).filterNotNull()
            } else {
                currentContent.appendLine(line)
            }
        }
        flush()
        return sections
    }

    private fun extractParagraphSections(content: String): List<Pair<List<String>, String>> =
        content.split("\n\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { emptyList<String>() to it }
}