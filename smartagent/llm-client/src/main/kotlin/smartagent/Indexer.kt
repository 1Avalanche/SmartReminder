package smartagent

class Indexer(
    private val loader: DocumentLoader
) {
    fun index(): List<Document> = loader.load()
}
