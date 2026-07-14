package smartagent.doc

import smartagent.Chunk
import smartagent.Chunker
import smartagent.EmbeddingGenerator
import smartagent.InMemoryVectorStore
import smartagent.StructuredChunker
import smartagent.tools.index.IndexMetadata

class IndexBuilder(
    private val chunker: Chunker = StructuredChunker(),
    private val embeddingGenerator: EmbeddingGenerator,
    private val indexStorage: IndexStorage,
    private val metadataStorage: MetadataStorage
) {
    fun clearStorage() {
        indexStorage.clear()
    }

    fun build(source: DocumentSource) {
        DocScanLogger.start(source.owner, source.repo, source.branch)
        val documents = source.loadDocuments()

        if (documents.isEmpty()) {
            println("[DocIndex] No documents fetched. Check paths and GitHub token.")
            return
        }

        val chunks = chunker.chunk(documents)
        println("[DocIndex] Indexing ${chunks.size} chunks from ${documents.size} docs...")

        val store = InMemoryVectorStore()
        val pairs = mutableListOf<Pair<FloatArray, Chunk>>()
        for ((i, chunk) in chunks.withIndex()) {
            print("\r  [${i + 1}/${chunks.size}] индексирую...")
            System.out.flush()
            val embedding = embeddingGenerator.embed(chunk.content).vector
            store.add(embedding, chunk)
            pairs.add(embedding to chunk)
        }
        println("\r  [${chunks.size}/${chunks.size}] готово           ")

        indexStorage.save(pairs)

        val metadata = IndexMetadata(
            indexedAt = System.currentTimeMillis(),
            owner = source.owner,
            repo = source.repo,
            docPaths = source.docPaths,
            currentBranch = source.branch,
            fileList = documents.map { it.metadata.source },
            docCount = documents.size,
            chunkCount = chunks.size
        )
        metadataStorage.save(metadata)

        DocScanLogger.logSummary(documents.size, chunks.size)
        println("[DocIndex] Done: ${chunks.size} chunks | branch: ${source.branch} | docs: ${documents.size}")
    }
}
