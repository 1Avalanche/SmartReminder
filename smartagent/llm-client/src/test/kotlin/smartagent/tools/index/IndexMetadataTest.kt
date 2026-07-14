package smartagent.tools.index

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IndexMetadataTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sample() = IndexMetadata(
        indexedAt = System.currentTimeMillis(),
        owner = "myorg",
        repo = "myrepo",
        docPaths = listOf("docs/", "README.md"),
        currentBranch = "main",
        fileList = listOf("README.md", "docs/guide.md"),
        docCount = 2,
        chunkCount = 42
    )

    @Test
    fun `save and load round-trip`() {
        val file = tmp.newFile("metadata.json").absolutePath
        val original = sample()

        IndexMetadata.save(original, file)
        val loaded = IndexMetadata.load(file)

        assertEquals(original, loaded)
    }

    @Test
    fun `load returns null for missing file`() {
        assertNull(IndexMetadata.load("/no/such/file.json"))
    }

    @Test
    fun `isStale returns false for fresh index`() {
        val fresh = sample().copy(indexedAt = System.currentTimeMillis())
        assertFalse(fresh.isStale(ttlHours = 12))
    }

    @Test
    fun `isStale returns true for old index`() {
        val old = sample().copy(indexedAt = System.currentTimeMillis() - 13 * 3_600_000L)
        assertTrue(old.isStale(ttlHours = 12))
    }

    @Test
    fun `isStale uses custom ttlHours`() {
        val twoHoursOld = sample().copy(indexedAt = System.currentTimeMillis() - 2 * 3_600_000L)
        assertTrue(twoHoursOld.isStale(ttlHours = 1))
        assertFalse(twoHoursOld.isStale(ttlHours = 3))
    }
}
