package smartagent.doc

import smartagent.Chunk
import smartagent.JsonVectorIndexPersistence
import smartagent.VectorStore
import java.io.File

interface IndexStorage {
    fun save(entries: List<Pair<FloatArray, Chunk>>)
    fun load(): VectorStore?
    fun exists(): Boolean
    fun clear()
}

class FileIndexStorage(
    private val persistence: JsonVectorIndexPersistence,
    private val filePath: String
) : IndexStorage {

    override fun save(entries: List<Pair<FloatArray, Chunk>>) {
        File(filePath).parentFile?.mkdirs()
        persistence.save(entries, filePath)
    }

    override fun load(): VectorStore? {
        if (!File(filePath).exists()) return null
        return runCatching { persistence.load(filePath) }.getOrNull()
    }

    override fun exists(): Boolean = File(filePath).exists()

    override fun clear() {
        File(filePath).delete()
    }
}
