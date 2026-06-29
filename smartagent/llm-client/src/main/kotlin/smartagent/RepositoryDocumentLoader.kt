package smartagent

class RepositoryDocumentLoader(
    private val scanner: FileScanner
) : DocumentLoader {

    override fun load(): List<Document> =
        scanner.collectWithContent().map { (path, content) ->
            Document(
                id = path,
                title = path.substringAfterLast('/').substringAfterLast('\\'),
                content = content,
                metadata = DocumentMetadata(
                    source = path,
                    extension = path.substringAfterLast('.', "").ifEmpty { null }
                )
            )
        }
}
