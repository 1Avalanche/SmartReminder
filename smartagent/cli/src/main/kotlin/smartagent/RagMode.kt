package smartagent

/**
 * Режимы работы RAG-пайплайна в QUESTION mode.
 *
 * Используются для сравнения качества ответов с разными уровнями обработки:
 * - [NO] — без RAG: вопрос отправляется напрямую в LLM без контекста.
 * - [SIMPLE] — базовый RAG: поиск top-K чанков -> контекст -> LLM.
 * - [RERANK] — улучшенный RAG: поиск -> порог -> реранкер -> топ-3 -> контекст -> LLM.
 */
enum class RagMode {
    /** Вопрос отправляется в LLM без какого-либо контекста. */
    NO,

    /** Базовый RAG: поиск, топ-3 чанка, контекст */
    SIMPLE,

    /** Улучшенный RAG: поиск -> порог -> реранкер -> топ-3 -> контекст */
    RERANK;

    companion object {
        /**
         * Парсит строковое значение в [RagMode].
         * Регистронезависимый: "no", "No", "NO" -> [NO].
         *
         * @param value строковое представление режима
         * @return [RagMode] или `null`, если значение не распознано
         */
        fun fromString(value: String): RagMode? {
            return entries.find { it.name.equals(value, ignoreCase = true) }
        }
    }

    /**
     * Возвращает человекочитаемое название для вывода в CLI.
     */
    fun displayName(): String = when (this) {
        NO -> "no-rag"
        SIMPLE -> "simple-rag"
        RERANK -> "rerank-rag"
    }

    /**
     * Возвращает краткое описание режима.
     */
    fun description(): String = when (this) {
        NO -> "только LLM, без контекста"
        SIMPLE -> "поиск + топ-3 чанка + контекст"
        RERANK -> "поиск + порог + реранкер + топ-3 + контекст"
    }
}
