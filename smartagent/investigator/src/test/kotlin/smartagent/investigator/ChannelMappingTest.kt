package smartagent.investigator

import smartagent.investigator.model.ChannelMapping
import smartagent.investigator.model.resolveRepo
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChannelMappingTest {

    private val mappings = listOf(
        ChannelMapping(
            alias = listOf("productsearch", "peach", "peachinternal", "peachorchestrator"),
            repoName = "peach-repo"
        ),
        ChannelMapping(
            alias = listOf("mango", "mangointernal"),
            repoName = "mango-repo"
        )
    )

    @Test
    fun `resolves primary alias`() {
        assertEquals("peach-repo", mappings.resolveRepo("peach"))
    }

    @Test
    fun `resolves secondary alias`() {
        assertEquals("peach-repo", mappings.resolveRepo("peachorchestrator"))
    }

    @Test
    fun `resolves alias case-insensitive`() {
        assertEquals("peach-repo", mappings.resolveRepo("PEACH"))
    }

    @Test
    fun `resolves mango alias`() {
        assertEquals("mango-repo", mappings.resolveRepo("mangointernal"))
    }

    @Test
    fun `returns null for unknown alias`() {
        assertNull(mappings.resolveRepo("unknown"))
    }

    @Test
    fun `returns null for empty list`() {
        assertNull(emptyList<ChannelMapping>().resolveRepo("peach"))
    }

    @Test
    fun `resolves productsearch alias`() {
        assertEquals("peach-repo", mappings.resolveRepo("productsearch"))
    }
}
