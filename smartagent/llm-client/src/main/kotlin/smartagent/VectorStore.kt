package smartagent

interface VectorStore {
    fun add(embedding: FloatArray, chunk: Chunk)

    /**
     * Поиск топ-K наиболее релевантных векторов.
     *
     * @param queryEmbedding Вектор запроса
     * @param topK Количество возвращаемых результатов
     * @param threshold Минимальный порог косинусного сходства (0.0..1.0).
     *                 Результаты с score < threshold отфильтровываются.
     *                 Значение 0.0 означает "без фильтрации" (поведение по умолчанию).
     * @return Список результатов, отсортированных по убыванию score
     */
    fun search(queryEmbedding: FloatArray, topK: Int, threshold: Double = 0.0): List<SearchResult>
    fun size(): Int
}
