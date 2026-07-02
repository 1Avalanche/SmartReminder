package smartagent

import java.io.File

internal class QuestionHandler(
    private val session: ChatSession,
    private val client: ChatClient,
    private val rerankerClient: RerankerClient? = null
) {
    private var vectorStore: VectorStore? = null

    fun handle(input: String, ragMode: RagMode) {
        print("${Colors.DARK_GRAY}Processing question...${Colors.RESET}")

        val baseSystemPrompt = loadSystemPrompt()

        val chunks = when (ragMode) {
            RagMode.NO -> {
                println()
                emptyList()
            }
            RagMode.SIMPLE -> {
                val store = getVectorStore()
                if (store == null) {
                    println(" ${Colors.LIGHT_YELLOW}(index not loaded)${Colors.RESET}")
                    emptyList()
                } else {
                    retrieveChunks(input, useRerank = false)
                }
            }
            RagMode.RERANK -> {
                if (rerankerClient == null) {
                    println(" ${Colors.LIGHT_YELLOW}(reranker not configured, falling back to simple)${Colors.RESET}")
                    val store = getVectorStore()
                    if (store == null) {
                        println(" ${Colors.LIGHT_YELLOW}(index not loaded)${Colors.RESET}")
                        emptyList()
                    } else {
                        retrieveChunks(input, useRerank = false)
                    }
                } else {
                    val store = getVectorStore()
                    if (store == null) {
                        println(" ${Colors.LIGHT_YELLOW}(index not loaded)${Colors.RESET}")
                        emptyList()
                    } else {
                        retrieveChunks(input, useRerank = true)
                    }
                }
            }
        }

        val fullPrompt = buildPrompt(baseSystemPrompt, chunks)
        client.sendMessage(input, systemPromptOverride = fullPrompt, includeHistory = false)
    }

    private fun retrieveChunks(query: String, useRerank: Boolean): List<Chunk> {
        val generator = OllamaEmbeddingGenerator()
        val embedding = try {
            generator.embed(query)
        } catch (e: Exception) {
            println()
            println("${Colors.LIGHT_YELLOW}Embedding unavailable: ${e.message}${Colors.RESET}")
            return emptyList()
        }

        val store = getVectorStore() ?: return emptyList()

        return if (useRerank) {
            val found = store.search(embedding.vector, SEARCH_TOP_K)
            println(" ${Colors.DARK_GRAY}(Chunks found - ${found.size})${Colors.RESET}")
            val thresholdFiltered = found.filter { it.score >= SIMILARITY_THRESHOLD.toFloat() }
            println(" ${Colors.DARK_GRAY}(Chunks after similarity threshold ($SIMILARITY_THRESHOLD) - ${thresholdFiltered.size})${Colors.RESET}")
            if (thresholdFiltered.isEmpty()) {
                emptyList()
            } else {
                val texts = thresholdFiltered.map { it.chunk.content }
                val reranked = rerankerClient?.rerank(query, texts, FINAL_TOP_K)
                if (reranked.isNullOrEmpty()) {
                    println(" ${Colors.DARK_GRAY}(rerank returned empty, using top-${FINAL_TOP_K} after threshold)${Colors.RESET}")
                    thresholdFiltered.take(FINAL_TOP_K).map { it.chunk }
                } else {
                    reranked.mapNotNull { r ->
                        thresholdFiltered.getOrNull(r.index)?.chunk
                    }
                }
            }
        } else {
            val results = store.search(embedding.vector, SIMPLE_TOP_K)
            println(" ${Colors.DARK_GRAY}(Chunks found - ${results.size})${Colors.RESET}")
            results.map { it.chunk }
        }
    }

    private fun buildPrompt(basePrompt: String, chunks: List<Chunk>): String {
        if (chunks.isEmpty()) {
            println(" ${Colors.DARK_GRAY}(no context)${Colors.RESET}")
            return basePrompt
        }
        val contextBlock = chunks.joinToString("\n") { formatChunkLine(it) }
        println(" ${Colors.DARK_GRAY}(RAG: ${chunks.size} chunks)${Colors.RESET}")
        return "$basePrompt\n\nКонтекст:\n$contextBlock"
    }

    private fun getVectorStore(): VectorStore? {
        if (vectorStore == null) {
            val paths = listOf(
                ".indexed/fixed.json",
                "smartagent/.indexed/fixed.json"
            )
            val indexFile = paths.firstOrNull { File(it).exists() }
            if (indexFile != null) {
                try {
                    vectorStore = JsonVectorIndexPersistence().load(indexFile)
                } catch (e: Exception) {
                    println()
                    println("${Colors.LIGHT_YELLOW}Error loading index: ${e.message}${Colors.RESET}")
                }
            } else {
                println()
                println("${Colors.LIGHT_YELLOW}Index file not found. Run /index-run first.${Colors.RESET}")
            }
        }
        return vectorStore
    }

    internal fun loadSystemPrompt(): String {
        val paths = listOf(
            "cli/src/main/kotlin/prompts/question/system.md",
            "../cli/src/main/kotlin/prompts/question/system.md",
            "smartagent/cli/src/main/kotlin/prompts/question/system.md"
        )
        val baseContent = paths.firstOrNull { File(it).exists() }?.let { File(it).readText().trim() }
            ?: FALLBACK_QUESTION_PROMPT

        return baseContent
    }

    internal fun formatChunkLine(chunk: Chunk): String =
        "[id: ${chunk.documentId}] [title: ${chunk.metadata.documentTitle}] [${chunk.documentId}_${chunk.index}]: \"${chunk.content}\""

    private companion object {
        private const val SEARCH_TOP_K = 30
        private const val SIMPLE_TOP_K = 8
        private const val FINAL_TOP_K = 5
        private const val SIMILARITY_THRESHOLD = 0.68

        private val FALLBACK_QUESTION_PROMPT = """
            Ты — агент для ответов на вопросы.

            Правила:
            1. Если контекст содержит ответ — используй его.
            2. Если контекст НЕ содержит ответа — ответь, используя свои знания.
            3. Ты ОБЯЗАН дать ответ. Никогда не отказывайся отвечать.

            Формат ответа:
            Обязательно начинай ответ с одной из строк:
               - "Источник: контекст" — если ответ на основе контекста
               - "Источник: знания" — если ответ на основе твоих знаний
               - "Источник: контекст и знания" — если комбинируешь

            Запрещено:
            - Говорить «нет в контексте», «не могу ответить», «недостаточно информации»
            - Возвращать пустой ответ
        """.trimIndent()
    }
}
