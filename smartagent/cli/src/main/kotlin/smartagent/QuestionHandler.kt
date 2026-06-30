package smartagent

import java.io.File

internal class QuestionHandler(private val session: ChatSession, private val client: ChatClient) {

    private var vectorStore: VectorStore? = null

    fun handle(input: String, ragEnabled: Boolean) {
        print("${Colors.DARK_GRAY}Processing question...${Colors.RESET}")

        val baseSystemPrompt = loadSystemPrompt()
        var usedRag = ragEnabled
        var contextBlock: String? = null

        if (ragEnabled) {
            try {
                val store = getVectorStore()
                if (store != null) {
                    val generator = OllamaEmbeddingGenerator()
                    val embedding = generator.embed(input)
                    val results = store.search(embedding.vector, 20)
                    if (results.isNotEmpty()) {
                        contextBlock = formatContextBlock(results)
                    }
                }
            } catch (e: Exception) {
                println()
                println("${Colors.LIGHT_YELLOW}RAG unavailable: ${e.message}. Falling back to base prompt.${Colors.RESET}")
                usedRag = false
            }
        }

        val fullPrompt = if (contextBlock != null) {
            println(" ${Colors.DARK_GRAY}(RAG: ${resultsCount(contextBlock)} chunks)${Colors.RESET}")
            "$baseSystemPrompt\n\nКонтекст:\n$contextBlock"
        } else {
            if (usedRag) println(" ${Colors.DARK_GRAY}(no relevant chunks found)${Colors.RESET}")
            else println()
            baseSystemPrompt
        }

        client.sendMessage(input, systemPromptOverride = fullPrompt, includeHistory = false)
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

    internal fun resultsCount(contextBlock: String): Int {
        return contextBlock.count { it == '\n' } + 1
    }

    internal fun formatContextBlock(results: List<SearchResult>): String =
        results.joinToString("\n") { formatChunkLine(it.chunk) }

    internal fun formatChunkLine(chunk: Chunk): String =
        "[id: ${chunk.documentId}] [title: ${chunk.metadata.documentTitle}] [${chunk.documentId}_${chunk.index}]: \"${chunk.content}\""

    private companion object {
        private val FALLBACK_QUESTION_PROMPT = """
            Ты — агент для ответов на вопросы.

            Правила:
            1. Если контекст содержит ответ — используй его.
            2. Если контекст НЕ содержит ответа — ответь, используя свои знания.
            3. Ты ОБЯЗАН дать ответ. Никогда не отказывайся отвечать.
            4. Начинай ответ с одной из строк:
               - "Источник: контекст" — если ответ на основе контекста
               - "Источник: знания" — если ответ на основе твоих знаний
               - "Источник: контекст и знания" — если комбинируешь

            Запрещено:
            - Говорить «нет в контексте», «не могу ответить», «недостаточно информации»
            - Возвращать пустой ответ
        """.trimIndent()
    }
}
