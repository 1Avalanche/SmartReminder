package smartagent.doc

import smartagent.tools.index.IndexMetadata
import java.io.File

interface MetadataStorage {
    fun save(metadata: IndexMetadata)
    fun load(): IndexMetadata?
    fun clear()
}

class JsonMetadataStorage(private val filePath: String) : MetadataStorage {
    override fun save(metadata: IndexMetadata) = IndexMetadata.save(metadata, filePath)
    override fun load(): IndexMetadata? = IndexMetadata.load(filePath)
    override fun clear() { File(filePath).delete() }
}
