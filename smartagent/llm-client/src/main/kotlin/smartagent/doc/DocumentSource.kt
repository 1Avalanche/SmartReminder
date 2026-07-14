package smartagent.doc

import smartagent.Document

interface DocumentSource {
    val owner: String
    val repo: String
    val branch: String
    val docPaths: List<String>

    fun loadDocuments(): List<Document>
}
