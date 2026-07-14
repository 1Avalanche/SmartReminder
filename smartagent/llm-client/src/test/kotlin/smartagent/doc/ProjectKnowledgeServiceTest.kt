package smartagent.doc

import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import smartagent.Chunk
import smartagent.ChunkMetadata
import smartagent.Chunker
import smartagent.Document
import smartagent.EmbeddingGenerator
import smartagent.EmbeddingResult
import smartagent.InMemoryVectorStore
import smartagent.JsonVectorIndexPersistence
import smartagent.VectorStore
import smartagent.tools.Tool
import smartagent.tools.ToolRegistry
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectKnowledgeServiceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @After
    fun tearDown() {
        ToolRegistry.clear()
    }

    private fun makeService(
        fileContent: String = "# Hello",
        commitsContent: String = "commit1"
    ): ProjectKnowledgeService {
        ToolRegistry.register(stubTool("github_get_file_contents", fileContent))
        ToolRegistry.register(stubTool("github_list_commits", commitsContent))

        val indexDir = tmp.newFolder("index").absolutePath
        val indexStorage = FileIndexStorage(JsonVectorIndexPersistence(), "$indexDir/fixed.json")
        val metadataStorage = JsonMetadataStorage("$indexDir/metadata.json")
        val embedder = FakeEmbeddingGenerator()
        val indexBuilder = IndexBuilder(
            chunker = MinimalChunker(),
            embeddingGenerator = embedder,
            indexStorage = indexStorage,
            metadataStorage = metadataStorage
        )
        val ragSearcher = RagSearcher(embedder, indexStorage)
        return ProjectKnowledgeService(indexBuilder, ragSearcher, metadataStorage) { owner, repo, branch, paths ->
            GitHubDocumentSource(ToolRegistry, owner, repo, branch, paths)
        }
    }

    @Test
    fun `init saves metadata with correct values`() {
        val service = makeService()

        service.init("myorg", "myrepo", "main", listOf("README.md"))

        val stats = service.getStats()
        assertNotNull(stats)
        assertEquals("myorg", stats.owner)
        assertEquals("myrepo", stats.repo)
        assertEquals("main", stats.currentBranch)
        assertTrue(stats.chunkCount > 0)
    }

    @Test
    fun `isStale returns false immediately after init`() {
        val service = makeService()
        service.init("org", "repo", "main", listOf("README.md"))

        assertFalse(service.isStale())
    }

    @Test
    fun `isStale returns false when no metadata exists`() {
        val indexDir = tmp.newFolder("empty").absolutePath
        val indexStorage = FileIndexStorage(JsonVectorIndexPersistence(), "$indexDir/fixed.json")
        val metadataStorage = JsonMetadataStorage("$indexDir/metadata.json")
        val embedder = FakeEmbeddingGenerator()
        val service = ProjectKnowledgeService(
            IndexBuilder(MinimalChunker(), embedder, indexStorage, metadataStorage),
            RagSearcher(embedder, indexStorage),
            metadataStorage
        ) { owner, repo, branch, paths -> GitHubDocumentSource(ToolRegistry, owner, repo, branch, paths) }

        assertFalse(service.isStale())
    }

    @Test
    fun `getContext returns non-blank ragContext after init`() {
        val service = makeService(fileContent = "Kotlin is a JVM language")
        service.init("org", "repo", "main", listOf("README.md"))

        val ctx = service.getContext("Kotlin")

        assertTrue(ctx.ragContext.isNotBlank())
    }

    @Test
    fun `getContext ragContext is empty when not initialized`() {
        val indexDir = tmp.newFolder("no-index").absolutePath
        val indexStorage = FileIndexStorage(JsonVectorIndexPersistence(), "$indexDir/fixed.json")
        val metadataStorage = JsonMetadataStorage("$indexDir/metadata.json")
        val embedder = FakeEmbeddingGenerator()
        val service = ProjectKnowledgeService(
            IndexBuilder(MinimalChunker(), embedder, indexStorage, metadataStorage),
            RagSearcher(embedder, indexStorage),
            metadataStorage
        ) { owner, repo, branch, paths -> GitHubDocumentSource(ToolRegistry, owner, repo, branch, paths) }

        assertEquals("", service.getContext("anything").ragContext)
    }

    @Test
    fun `getContext gitContext is null when no metadata`() {
        val indexDir = tmp.newFolder("ctx-empty").absolutePath
        val indexStorage = FileIndexStorage(JsonVectorIndexPersistence(), "$indexDir/fixed.json")
        val metadataStorage = JsonMetadataStorage("$indexDir/metadata.json")
        val embedder = FakeEmbeddingGenerator()
        val service = ProjectKnowledgeService(
            IndexBuilder(MinimalChunker(), embedder, indexStorage, metadataStorage),
            RagSearcher(embedder, indexStorage),
            metadataStorage
        ) { owner, repo, branch, paths -> GitHubDocumentSource(ToolRegistry, owner, repo, branch, paths) }

        assertNull(service.getContext("anything").gitContext)
    }

    @Test
    fun `getContext returns gitContext with branch and fileList after init`() {
        val service = makeService()
        service.init("org", "repo", "feature/x", listOf("README.md"))

        val ctx = service.getContext("something")

        assertNotNull(ctx.gitContext)
        assertEquals("feature/x", ctx.gitContext?.branch)
        assertTrue(ctx.gitContext?.fileList?.isNotEmpty() == true)
    }

    @Test
    fun `getStats returns null before init`() {
        val service = makeService()
        assertNull(service.getStats())
    }

    @Test
    fun `isInitialized returns false before init`() {
        val service = makeService()
        assertFalse(service.isInitialized())
    }

    @Test
    fun `isInitialized returns true after init`() {
        val service = makeService()
        service.init("org", "repo", "main", listOf("README.md"))
        assertTrue(service.isInitialized())
    }

    private fun stubTool(id: String, result: String) = object : Tool {
        override val id = id
        override val description = "stub"
        override fun execute(args: Map<String, Any>) = result
    }
}

internal class FakeEmbeddingGenerator : EmbeddingGenerator {
    override val dimension = 4
    override fun embed(text: String): EmbeddingResult {
        val hash = text.hashCode()
        return EmbeddingResult(floatArrayOf(
            (hash and 0xFF).toFloat() / 255f,
            ((hash shr 8) and 0xFF).toFloat() / 255f,
            ((hash shr 16) and 0xFF).toFloat() / 255f,
            ((hash shr 24) and 0xFF).toFloat() / 255f
        ))
    }
}

internal class MinimalChunker : Chunker {
    override fun chunk(documents: List<Document>): List<Chunk> =
        documents.map { doc ->
            Chunk(
                id = doc.id,
                content = doc.content,
                documentId = doc.id,
                chunkIndex = 0,
                metadata = ChunkMetadata(
                    documentTitle = doc.title,
                    documentSource = doc.metadata.source,
                    extension = doc.metadata.extension,
                    sectionPath = emptyList(),
                    chunkIndex = 0
                )
            )
        }
}

internal class PreloadedIndexStorage(
    chunks: List<Chunk>,
    embedder: EmbeddingGenerator
) : IndexStorage {
    private val store = InMemoryVectorStore().also { store ->
        chunks.forEach { chunk -> store.add(embedder.embed(chunk.content).vector, chunk) }
    }

    override fun save(entries: List<Pair<FloatArray, Chunk>>) {}
    override fun load(): VectorStore = store
    override fun exists(): Boolean = true
    override fun clear() {}
}
