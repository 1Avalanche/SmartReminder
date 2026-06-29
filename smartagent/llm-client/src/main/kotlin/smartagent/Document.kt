package smartagent

data class Document(
    val id: String,
    val title: String,
    val content: String,
    val metadata: DocumentMetadata
)
