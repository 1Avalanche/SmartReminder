package smartagent

import java.io.File

class IndexCommandHandler {

    fun handle(args: Array<String>) {
        val params = parseIndexArgs(args)
        if (params == null) {
            println("Usage: index --path <dir> --strategy fixed|structured")
            return
        }

        val dir = File(params.path).canonicalFile
        if (!dir.isDirectory) {
            println("${Colors.LIGHT_YELLOW}Error: path not found or not a directory: ${params.path}${Colors.RESET}")
            return
        }

        val outputFile = File(dir, ".indexed/${params.strategy}.json")
        outputFile.parentFile.mkdirs()

        println("${Colors.LIGHT_GREEN}Indexing: ${dir.absolutePath}${Colors.RESET}")
        println("${Colors.DARK_GRAY}Strategy: ${params.strategy} | Output: ${outputFile.absolutePath}${Colors.RESET}\n")

        val loader: DocumentLoader = RepositoryDocumentLoader(FileScanner(dir))
        val chunker: Chunker = when (params.strategy) {
            "structured" -> StructuredChunker()
            else -> FixedChunker(DEFAULT_CHUNK_SIZE)
        }
        val generator: EmbeddingGenerator = OllamaEmbeddingGenerator()
        val store = InMemoryVectorStore()

        print("Loading documents...")
        val documents = loader.load()
        println(" ${documents.size} found")


        print("Chunking...")
        val chunks = chunker.chunk(documents)
        println(" ${chunks.size} chunks")

        print("Embedding and indexing...")
        var errors = 0
        var totalTokens = 0
        val startTime = System.nanoTime()
        chunks.forEach { chunk ->
            try {
                val result = generator.embed(chunk.content)
                store.add(result.vector, chunk)
                totalTokens += result.promptTokens
            } catch (e: Exception) {
                errors++
                println()
                println("${Colors.LIGHT_YELLOW}  skip chunk ${chunk.id} (${chunk.content.length} chars): ${e.message}${Colors.RESET}")
            }
        }
        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
        if (errors > 0) {
            println("${Colors.LIGHT_YELLOW}  ($errors chunks skipped due to errors)${Colors.RESET}")
        }
        println(" done")

        print("Saving...")
        JsonVectorIndexPersistence().save(store.entries(), outputFile.absolutePath)
        println(" done\n")

        println("${Colors.LIGHT_GREEN}Index saved: ${outputFile.absolutePath}${Colors.RESET}")
        println("${Colors.DARK_GRAY}Documents : ${documents.size}")
        println("Chunks    : ${chunks.size}")
        println("Index size: ${store.size()} entries")
        println("Time      : ${"%.1f".format(elapsedMs / 1000.0)}s")
        println("Tokens    : ${totalTokens}${Colors.RESET}")
    }

    private data class IndexParams(val path: String, val strategy: String)

    private fun parseIndexArgs(args: Array<String>): IndexParams? {
        var path: String? = null
        var strategy = "fixed"
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--path"     -> { path = args.getOrNull(++i); i++ }
                "--strategy" -> { strategy = args.getOrNull(++i) ?: "fixed"; i++ }
                else -> i++
            }
        }
        if (path == null) return null
        return IndexParams(path, strategy)
    }

    companion object {
        private const val DEFAULT_CHUNK_SIZE = 500
    }
}
