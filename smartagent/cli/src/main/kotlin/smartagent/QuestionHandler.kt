package smartagent

import java.io.File

internal data class RankedChunk(val chunk: Chunk, val score: Double?)

internal class QuestionHandler(
    private val session: ChatSession,
    private val client: ChatClient,
    private val rerankerClient: RerankerClient? = null
) {
    private var vectorStore: VectorStore? = null

    fun handle(input: String, ragMode: RagMode) {
        print("${Colors.DARK_GRAY}Processing question...${Colors.RESET}")

        val baseSystemPrompt = loadSystemPrompt()

        val chunks: List<RankedChunk> = when (ragMode) {
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

        if (ragMode != RagMode.NO && chunks.isEmpty()) {
            println("${Colors.LIGHT_YELLOW}Запрашиваемые данные не найдены. Попробуйте переформулировать вопрос.${Colors.RESET}")
            return
        }

        val fullPrompt = buildPrompt(baseSystemPrompt, chunks)
        client.sendMessage(input, systemPromptOverride = fullPrompt, includeHistory = true)
    }

    private fun retrieveChunks(query: String, useRerank: Boolean): List<RankedChunk> {
        val generator = OpenRouterEmbeddingGenerator()
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
                val reranked = rerankerClient?.rerank(query, texts, FINAL_TOP_K) ?: emptyList()
                val ranked = reranked.mapNotNull { r ->
                    thresholdFiltered.getOrNull(r.index)?.let { RankedChunk(it.chunk, r.score) }
                }
                val scoreFiltered = ranked.filter { (it.score ?: -1.0) >= RERANK_SCORE_THRESHOLD }
                println(" ${Colors.DARK_GRAY}(Chunks after rerank score threshold ($RERANK_SCORE_THRESHOLD) - ${scoreFiltered.size})${Colors.RESET}")
                scoreFiltered
            }
        } else {
            val results = store.search(embedding.vector, SIMPLE_TOP_K)
            println(" ${Colors.DARK_GRAY}(Chunks found - ${results.size})${Colors.RESET}")
            results.map { RankedChunk(it.chunk, it.score.toDouble()) }
        }
    }

    private fun buildPrompt(basePrompt: String, chunks: List<RankedChunk>): String {
        if (chunks.isEmpty()) {
            println(" ${Colors.DARK_GRAY}(no context)${Colors.RESET}")
            return basePrompt
        }
        val contextBlock = chunks.joinToString("\n\n") { formatChunkBlock(it) }
        println(" ${Colors.DARK_GRAY}(RAG: ${chunks.size} chunks)${Colors.RESET}")
        return "$basePrompt\n\n<context>\n$contextBlock\n</context>"
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

    internal fun formatChunkBlock(ranked: RankedChunk): String {
        val chunk = ranked.chunk
        val section = chunk.metadata.sectionPath.joinToString(" > ")
        val score = ranked.score?.let { "%.4f".format(java.util.Locale.ROOT, it) } ?: "n/a"
        return buildString {
            appendLine("<chunk>")
            append("  <source")
            append(" file=\"${chunk.metadata.documentSource}\"")
            append(" title=\"${chunk.metadata.documentTitle}\"")
            chunk.metadata.extension?.let { append(" ext=\"$it\"") }
            if (section.isNotEmpty()) append(" section=\"$section\"")
            append(" index=\"${chunk.metadata.chunkIndex}\"")
            appendLine("/>")
            appendLine("  <score>$score</score>")
            appendLine("  <content>")
            append(chunk.content.trimEnd())
            appendLine()
            appendLine("  </content>")
            append("</chunk>")
        }
    }

    private companion object {
        private const val SEARCH_TOP_K = 30
        private const val SIMPLE_TOP_K = 8
        private const val FINAL_TOP_K = 5
        private const val SIMILARITY_THRESHOLD = 0.65
        private const val RERANK_SCORE_THRESHOLD = 0.01

        private val FALLBACK_QUESTION_PROMPT = """
            Ты — агент для ответов на вопросы строго по предоставленному контексту.

            Правила:
            1. Отвечай ТОЛЬКО на основе информации из блоков <context>. Никогда не используй свои знания.
            2. Если в контексте нет релевантной информации — ответь ровно одной фразой: «В контексте не найдено подходящей информации».
            3. Не домысливай, не интерпретируй шире контекста, не добавляй факты из своих знаний.

            Формат ответа (когда ответ найден):
            **Источник:** file="<documentSource>" index=<chunkIndex>
            **Цитата:** «<дословная цитата из контекста>»
            **Ответ:** <твой ответ, основанный строго на цитате>

            Если релевантных чанков несколько — приведи источник и цитату для каждого. Ответ верни всегда только один, используя все знания из релевантных чанков.

            Запрещено:
            - Использовать знания вне контекста
            - Говорить «на основе моих знаний», «я думаю», «вероятно»
            - Возвращать пустой ответ без фразы-заглушки
        """.trimIndent()
    }
}
