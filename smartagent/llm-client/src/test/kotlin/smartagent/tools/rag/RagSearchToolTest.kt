package smartagent.tools.rag

import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import smartagent.Chunk
import smartagent.ChunkMetadata
import smartagent.doc.FakeEmbeddingGenerator
import smartagent.doc.FileIndexStorage
import smartagent.doc.GitHubDocumentSource
import smartagent.doc.IndexBuilder
import smartagent.doc.JsonMetadataStorage
import smartagent.doc.MinimalChunker
import smartagent.doc.PreloadedIndexStorage
import smartagent.doc.ProjectKnowledgeService
import smartagent.doc.RagSearcher
import smartagent.JsonVectorIndexPersistence
import smartagent.tools.ToolRegistry
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RagSearchToolTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @After
    fun tearDown() {
        ToolRegistry.clear()
    }

    private fun makeService(chunks: List<Chunk>): ProjectKnowledgeService {
        val embedder = FakeEmbeddingGenerator()
        val indexStorage = PreloadedIndexStorage(chunks, embedder)
        val indexDir = tmp.newFolder("idx").absolutePath
        val metadataStorage = JsonMetadataStorage("$indexDir/metadata.json")
        val indexBuilder = IndexBuilder(MinimalChunker(), embedder, indexStorage, metadataStorage)
        val ragSearcher = RagSearcher(embedder, indexStorage)
        return ProjectKnowledgeService(indexBuilder, ragSearcher, metadataStorage) { owner, repo, branch, paths ->
            GitHubDocumentSource(ToolRegistry, owner, repo, branch, paths)
        }
    }

    @Test
    fun `id and description are set correctly`() {
        val tool = RagSearchTool(makeService(emptyList()))
        assertEquals("rag_search", tool.id)
        assertTrue(tool.description.isNotBlank())
    }

    @Test
    fun `execute returns formatted chunks joined by separator`() {
        val chunks = listOf(
            makeChunk("chunk-1", "First content", "doc1"),
            makeChunk("chunk-2", "Second content", "doc2")
        )
        val tool = RagSearchTool(makeService(chunks))

        val result = tool.execute(mapOf("query" to "test query"))

        assertTrue(result.contains("First content"))
        assertTrue(result.contains("Second content"))
    }

    @Test
    fun `execute returns empty string when no chunks found`() {
        val tool = RagSearchTool(makeService(emptyList()))

        val result = tool.execute(mapOf("query" to "nothing"))

        assertEquals("", result)
    }

    @Test
    fun `execute returns error message when query arg is missing`() {
        val tool = RagSearchTool(makeService(emptyList()))
        val result = tool.execute(emptyMap())
        assertTrue(result.contains("Missing"))
    }

    private fun makeChunk(id: String, content: String, docTitle: String) = Chunk(
        id = id,
        content = content,
        documentId = "doc-$id",
        chunkIndex = 0,
        metadata = ChunkMetadata(
            documentTitle = docTitle,
            documentSource = "test",
            extension = "md",
            sectionPath = emptyList(),
            chunkIndex = 0
        )
    )
}
