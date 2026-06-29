package smartagent

import java.io.File

class StructuredChunker : Chunker {

    private val headerRegex = Regex("^(#{1,6})\\s+(.+)")

    private val declarationRegex = Regex(
        """^\s*(?:(?:private|public|internal|protected|override|suspend|abstract|open|sealed|data|inline|actual|const)\s+)*""" +
        """(?:fun|class|object|interface|enum\s+class|enum|def)\b"""
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
        val sections = when {
            ext in markdownExtensions -> extractMarkdownSections(document.content)
            ext in codeExtensions     -> extractCodeSections(document.content)
            else                      -> extractParagraphSections(document.content)
        }
        return sections.mapIndexed { index, (path, text) ->
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

    private fun extractCodeSections(content: String): List<Pair<List<String>, String>> {
        val sections = mutableListOf<String>()
        val current = StringBuilder()
        var prevBlank = false

        for (line in content.lines()) {
            val isBlank = line.isBlank()
            val isDeclaration = !isBlank && declarationRegex.containsMatchIn(line)
            if (prevBlank && isDeclaration && current.isNotBlank()) {
                sections.add(current.toString().trim())
                current.clear()
            }
            current.appendLine(line)
            prevBlank = isBlank
        }
        val last = current.toString().trim()
        if (last.isNotEmpty()) sections.add(last)

        return sections.map { emptyList<String>() to it }
    }

    private fun extractParagraphSections(content: String): List<Pair<List<String>, String>> =
        content.split("\n\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { emptyList<String>() to it }
}
