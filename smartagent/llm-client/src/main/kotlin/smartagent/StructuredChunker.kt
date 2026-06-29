package smartagent

class StructuredChunker : Chunker {

    private val headerRegex = Regex("^(#{1,6})\\s+(.+)")

    override fun chunk(documents: List<Document>): List<Chunk> =
        documents.flatMap { document -> chunkDocument(document) }

    private fun chunkDocument(document: Document): List<Chunk> =
        extractSections(document.content).mapIndexed { index, (path, text) ->
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

    private fun extractSections(content: String): List<Pair<List<String>, String>> {
        val lines = content.lines()

        if (lines.none { headerRegex.containsMatchIn(it) }) {
            return content.split("\n\n")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { emptyList<String>() to it }
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
                for (i in level until 6) hierarchy[i] = null
                currentPath = hierarchy.take(level).filterNotNull()
            } else {
                currentContent.appendLine(line)
            }
        }
        flush()

        return sections
    }
}
